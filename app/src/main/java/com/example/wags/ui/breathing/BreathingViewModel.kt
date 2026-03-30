package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.AccRespirationEngine
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ResonanceSessionRepository
import com.example.wags.data.repository.RfAssessmentRepository
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.usecase.breathing.CoherenceScoreCalculator
import com.example.wags.domain.usecase.breathing.ContinuousPacerEngine
import com.example.wags.domain.usecase.breathing.RateRecommendation
import com.example.wags.domain.usecase.breathing.ResonanceRateRecommender
import com.example.wags.domain.usecase.breathing.RfAssessmentOrchestrator
import com.example.wags.domain.usecase.breathing.RfEpochResult
import com.example.wags.domain.usecase.breathing.RfOrchestratorState
import com.example.wags.domain.usecase.breathing.RfPhase
import com.example.wags.domain.usecase.breathing.RfProtocol
import com.example.wags.domain.usecase.breathing.SlidingWindowResult
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Session phase for the resonance breathing screen.
 */
enum class BreathingSessionPhase {
    /** Not started yet — controls visible */
    IDLE,
    /** 10-second countdown before breathing begins */
    PREPARING,
    /** Active guided breathing session */
    BREATHING,
    /** Session complete — showing summary before navigating */
    COMPLETE
}

data class BreathingUiState(
    val breathingRateBpm: Float = 5.5f,
    val ieRatio: Float = 1.0f,
    val isHrDeviceConnected: Boolean = false,
    val pacerRadius: Float = 0f,
    val isInhaling: Boolean = true,
    val breathPhaseLabel: String = "INHALE",
    val coherenceScore: Float = 0f,
    val isSessionActive: Boolean = false,
    val sessionPhase: BreathingSessionPhase = BreathingSessionPhase.IDLE,
    val prepCountdownSeconds: Int = 0,
    val rfPhase: RfPhase = RfPhase.IDLE,
    val currentTestRateBpm: Float = 0f,
    val remainingSeconds: Long = 0L,
    val epochResults: List<RfEpochResult> = emptyList(),
    val slidingWindowResult: SlidingWindowResult? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    // Live HRV metrics (computed from RR intervals during session)
    val liveRmssd: Float? = null,
    val liveSdnn: Float? = null,
    val rrCount: Int = 0,
    /** Recent RR intervals (ms) for the scrolling chart — last ~45 values. */
    val liveRrIntervals: List<Double> = emptyList(),

    // ── Coherence ratio (real-time) ──────────────────────────────────────────
    /** Live coherence ratio updated every 5 seconds during session. */
    val liveCoherenceRatio: Float = 0f,
    /** History of coherence ratio samples for the coherence-over-time chart. */
    val coherenceHistory: List<Float> = emptyList(),

    // ── Session points / gamification ────────────────────────────────────────
    /** Accumulated session points (1 pt per second in high coherence, 0.5 in medium). */
    val sessionPoints: Float = 0f,
    /** Elapsed session time in seconds. */
    val sessionElapsedSeconds: Int = 0,

    // ── Duration / timer ─────────────────────────────────────────────────────
    /** Duration in minutes for the session timer. */
    val sessionDurationMinutes: Int = 5,
    /** Whether infinity mode is enabled (no timer limit). */
    val infinityMode: Boolean = false,
    /** The auto-determined best breathing rate from last 2 months. Null if no data. */
    val bestRateBpm: Float? = null,
    /** Remaining seconds on the session timer (only used when not in infinity mode). */
    val sessionRemainingSeconds: Int = 0,

    // ── Session complete data ────────────────────────────────────────────────
    /** Summary data available when sessionPhase == COMPLETE. */
    val sessionSummary: SessionSummary? = null
)

/**
 * Summary of a completed breathing session.
 */
data class SessionSummary(
    val durationSeconds: Int,
    val totalBeats: Int,
    val meanCoherenceRatio: Float,
    val maxCoherenceRatio: Float,
    val timeInHighCoherence: Int,
    val timeInMediumCoherence: Int,
    val timeInLowCoherence: Int,
    val meanRmssdMs: Float,
    val meanSdnnMs: Float,
    val artifactPercent: Float,
    val totalPoints: Float,
    val coherenceHistory: List<Float>,
    val breathingRateBpm: Float,
    val ieRatio: Float
)

@HiltViewModel
class BreathingViewModel @Inject constructor(
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val accEngine: AccRespirationEngine,
    private val pacerEngine: ContinuousPacerEngine,
    private val coherenceCalculator: CoherenceScoreCalculator,
    private val rfOrchestrator: RfAssessmentOrchestrator,
    private val habitRepo: HabitIntegrationRepository,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val timeDomainCalc: TimeDomainHrvCalculator,
    private val rfAssessmentRepo: RfAssessmentRepository,
    private val resonanceSessionRepo: ResonanceSessionRepository,
    private val rateRecommender: ResonanceRateRecommender,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(BreathingUiState())
    val uiState: StateFlow<BreathingUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        hrDataSource.isAnyHrDeviceConnected
    ) { state, hr, spo2, hrConnected ->
        state.copy(liveHr = hr, liveSpO2 = spo2, isHrDeviceConnected = hrConnected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BreathingUiState()
    )

    private var lastCoherenceScore = 0f
    private var lastCoherenceUpdateMs = 0L

    /**
     * True once [setBreathingRate] has been called explicitly (e.g. from a nav-arg
     * or slider).  Prevents the async [loadBestBreathingRate] from overwriting a
     * user-chosen rate after the fact.
     */
    private var rateManuallySet = false

    private var pacerJob: Job? = null
    private var coherenceJob: Job? = null
    private var rfCollectorJob: Job? = null
    private var prepJob: Job? = null
    private var rrPollingJob: Job? = null
    private var timerJob: Job? = null

    /** Total writes at the moment the session started — used to ignore pre-session RR intervals */
    private var rrWritesAtStart: Long = 0L

    /** All RR intervals collected during this session (for post-session analytics). */
    private val allSessionRrIntervals = mutableListOf<Double>()
    private var sessionStartTimeMs = 0L

    /** The last computed recommendation — exposed so the explanation screen can read it. */
    private val _recommendation = MutableStateFlow<RateRecommendation?>(null)
    val recommendation: StateFlow<RateRecommendation?> = _recommendation

    init {
        loadBestBreathingRate()
    }

    /**
     * Loads the best breathing rate from the last 2 months of assessment + session data
     * using the [ResonanceRateRecommender] algorithm (recency-weighted, confidence-scaled).
     */
    private fun loadBestBreathingRate() {
        viewModelScope.launch {
            val result = rateRecommender.recommend()
            _recommendation.value = result
            val bestRate = result.recommendedBpm
            if (bestRate != null) {
                _uiState.update {
                    it.copy(
                        // Only overwrite the breathing rate if the user hasn't
                        // already set one explicitly (e.g. via the slider or nav-arg).
                        breathingRateBpm = if (rateManuallySet) it.breathingRateBpm else bestRate,
                        bestRateBpm = bestRate
                    )
                }
            }
        }
    }

    // Coherence zone time tracking
    private var highCoherenceSeconds = 0
    private var mediumCoherenceSeconds = 0
    private var lowCoherenceSeconds = 0
    private var sessionPoints = 0f

    fun setBreathingRate(rateBpm: Float) {
        rateManuallySet = true
        _uiState.update { it.copy(breathingRateBpm = rateBpm.coerceIn(4.0f, 7.0f)) }
    }

    fun setIeRatio(ratio: Float) {
        _uiState.update { it.copy(ieRatio = ratio.coerceIn(0.5f, 3.0f)) }
    }

    fun setSessionDuration(minutes: Int) {
        _uiState.update { it.copy(sessionDurationMinutes = minutes.coerceIn(1, 60)) }
    }

    fun setInfinityMode(enabled: Boolean) {
        _uiState.update { it.copy(infinityMode = enabled) }
    }

    fun startSession(deviceId: String) {
        if (_uiState.value.isSessionActive) return
        deviceManager.startRrStream(deviceId)
        rrWritesAtStart = deviceManager.rrBuffer.totalWrites()
        pacerEngine.reset()
        allSessionRrIntervals.clear()
        sessionStartTimeMs = System.currentTimeMillis()
        highCoherenceSeconds = 0
        mediumCoherenceSeconds = 0
        lowCoherenceSeconds = 0
        sessionPoints = 0f
        _uiState.update {
            it.copy(
                isSessionActive = true,
                sessionPhase = BreathingSessionPhase.PREPARING,
                prepCountdownSeconds = 10,
                liveRmssd = null,
                liveSdnn = null,
                rrCount = 0,
                liveRrIntervals = emptyList(),
                liveCoherenceRatio = 0f,
                coherenceHistory = emptyList(),
                sessionPoints = 0f,
                sessionElapsedSeconds = 0,
                sessionRemainingSeconds = if (it.infinityMode) 0 else it.sessionDurationMinutes * 60,
                sessionSummary = null
            )
        }
        startPreparationCountdown()
    }

    private fun startPreparationCountdown() {
        prepJob = viewModelScope.launch {
            for (i in 10 downTo 1) {
                _uiState.update { it.copy(prepCountdownSeconds = i) }
                delay(1_000L)
            }
            // Preparation complete — start breathing
            sessionStartTimeMs = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    sessionPhase = BreathingSessionPhase.BREATHING,
                    prepCountdownSeconds = 0
                )
            }
            startPacerLoop()
            startCoherenceLoop()
            startRrPollingLoop()
            startSessionTimer()
        }
    }

    /**
     * Ends the session and saves it. Called from the "Stop & Save" / "End Session"
     * button, or when the timer expires.
     */
    fun stopSession() {
        prepJob?.cancel()
        pacerJob?.cancel()
        coherenceJob?.cancel()
        rrPollingJob?.cancel()
        timerJob?.cancel()

        val currentState = _uiState.value

        // Save session if we were actually breathing (even if stopped early)
        if (currentState.sessionPhase == BreathingSessionPhase.BREATHING) {
            val summary = computeSessionSummary(currentState)

            // Persist to database
            viewModelScope.launch {
                saveSessionToDb(summary, currentState)
            }

            _uiState.update {
                it.copy(
                    isSessionActive = false,
                    sessionPhase = BreathingSessionPhase.COMPLETE,
                    sessionSummary = summary
                )
            }

            // Signal the Habit app that a Resonance Breathing session was completed
            habitRepo.sendHabitIncrement(Slot.RESONANCE_BREATHING)
        } else {
            _uiState.update {
                it.copy(
                    isSessionActive = false,
                    sessionPhase = BreathingSessionPhase.IDLE
                )
            }
        }
    }

    /**
     * Cancels the session without saving. Called when the user backs out
     * (via back gesture/button or the back arrow) and confirms the discard dialog.
     */
    fun cancelSession() {
        prepJob?.cancel()
        pacerJob?.cancel()
        coherenceJob?.cancel()
        rrPollingJob?.cancel()
        timerJob?.cancel()

        _uiState.update {
            it.copy(
                isSessionActive = false,
                sessionPhase = BreathingSessionPhase.IDLE,
                sessionSummary = null
            )
        }
    }

    private suspend fun saveSessionToDb(summary: SessionSummary, state: BreathingUiState) {
        val coherenceHistoryJson = summary.coherenceHistory
            .joinToString(",", "[", "]") { "%.2f".format(it) }
        val entity = ResonanceSessionEntity(
            timestamp = System.currentTimeMillis(),
            breathingRateBpm = summary.breathingRateBpm,
            ieRatio = summary.ieRatio,
            durationSeconds = summary.durationSeconds,
            totalBeats = summary.totalBeats,
            meanCoherenceRatio = summary.meanCoherenceRatio,
            maxCoherenceRatio = summary.maxCoherenceRatio,
            timeInHighCoherence = summary.timeInHighCoherence,
            timeInMediumCoherence = summary.timeInMediumCoherence,
            timeInLowCoherence = summary.timeInLowCoherence,
            meanRmssdMs = summary.meanRmssdMs,
            meanSdnnMs = summary.meanSdnnMs,
            artifactPercent = summary.artifactPercent,
            totalPoints = summary.totalPoints,
            coherenceHistoryJson = coherenceHistoryJson,
            hrDeviceId = hrDataSource.activeHrDeviceLabel()
        )
        resonanceSessionRepo.save(entity)
    }

    /** Called from the session complete screen to return to idle. */
    fun dismissSessionComplete() {
        _uiState.update {
            it.copy(
                sessionPhase = BreathingSessionPhase.IDLE,
                sessionSummary = null
            )
        }
    }

    private fun computeSessionSummary(currentState: BreathingUiState): SessionSummary {
        val rrSnapshot = allSessionRrIntervals.toDoubleArray()
        val correctionResult = if (rrSnapshot.size >= 10) artifactCorrection.execute(rrSnapshot.toList()) else null
        val correctedNn = correctionResult?.correctedNn ?: rrSnapshot
        val artifactPct = if (rrSnapshot.isNotEmpty() && correctionResult != null)
            correctionResult.artifactCount.toFloat() / rrSnapshot.size * 100f else 0f

        val timeDomain = if (correctedNn.size >= 4) timeDomainCalc.calculate(correctedNn) else null

        val coherenceHistory = currentState.coherenceHistory
        val meanCoherence = if (coherenceHistory.isNotEmpty()) coherenceHistory.average().toFloat() else 0f
        val maxCoherence = if (coherenceHistory.isNotEmpty()) coherenceHistory.max() else 0f

        val durationSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()

        return SessionSummary(
            durationSeconds = durationSec,
            totalBeats = rrSnapshot.size,
            meanCoherenceRatio = meanCoherence,
            maxCoherenceRatio = maxCoherence,
            timeInHighCoherence = highCoherenceSeconds,
            timeInMediumCoherence = mediumCoherenceSeconds,
            timeInLowCoherence = lowCoherenceSeconds,
            meanRmssdMs = timeDomain?.rmssdMs?.toFloat() ?: 0f,
            meanSdnnMs = timeDomain?.sdnnMs?.toFloat() ?: 0f,
            artifactPercent = artifactPct,
            totalPoints = sessionPoints,
            coherenceHistory = coherenceHistory,
            breathingRateBpm = currentState.breathingRateBpm,
            ieRatio = currentState.ieRatio
        )
    }

    private fun startPacerLoop() {
        pacerJob = viewModelScope.launch {
            while (isActive) {
                delay(16L) // ~60 FPS
                val state = _uiState.value
                pacerEngine.tick(state.breathingRateBpm, state.ieRatio)
                val radius = pacerEngine.getPacerRadius(state.ieRatio)
                val label = pacerEngine.breathPhaseLabel.value
                _uiState.update {
                    it.copy(
                        pacerRadius = radius,
                        isInhaling = label == "INHALE",
                        breathPhaseLabel = label
                    )
                }
            }
        }
    }

    private fun startCoherenceLoop() {
        coherenceJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000L)
                val now = System.currentTimeMillis()
                lastCoherenceUpdateMs = now

                val rrSnapshot = synchronized(allSessionRrIntervals) {
                    if (allSessionRrIntervals.size >= 32) allSessionRrIntervals.takeLast(256).toDoubleArray()
                    else null
                }

                if (rrSnapshot != null) {
                    val result = withContext(mathDispatcher) {
                        coherenceCalculator.calculateCoherenceRatio(rrSnapshot)
                    }
                    val ratio = result.coherenceRatio

                    // Also compute the old FFT-based score for backward compat display
                    val fftScore = withContext(mathDispatcher) {
                        val state = _uiState.value
                        val targetFreq = accEngine.breathRateBpm.value?.let { it / 60f }
                            ?: (state.breathingRateBpm / 60f)
                        coherenceCalculator.calculateFft(
                            nn = rrSnapshot,
                            targetFreqHz = targetFreq.toDouble()
                        )
                    }
                    if (fftScore > 0f) lastCoherenceScore = fftScore

                    // Track coherence zone time (5 seconds per update)
                    when {
                        ratio >= 3f -> {
                            highCoherenceSeconds += 5
                            sessionPoints += 5f  // 1 pt/sec in high
                        }
                        ratio >= 1f -> {
                            mediumCoherenceSeconds += 5
                            sessionPoints += 2.5f  // 0.5 pt/sec in medium
                        }
                        else -> {
                            lowCoherenceSeconds += 5
                            // No points in low coherence
                        }
                    }

                    _uiState.update {
                        it.copy(
                            coherenceScore = lastCoherenceScore,
                            liveCoherenceRatio = ratio,
                            coherenceHistory = it.coherenceHistory + ratio,
                            sessionPoints = sessionPoints
                        )
                    }
                } else {
                    // Not enough data yet — still update FFT score
                    val rrAll = deviceManager.rrBuffer.readLast(256)
                    if (rrAll.size >= 32) {
                        val newScore = withContext(mathDispatcher) {
                            val state = _uiState.value
                            val targetFreq = accEngine.breathRateBpm.value?.let { it / 60f }
                                ?: (state.breathingRateBpm / 60f)
                            coherenceCalculator.calculateFft(
                                nn = rrAll.toDoubleArray(),
                                targetFreqHz = targetFreq.toDouble()
                            )
                        }
                        if (newScore > 0f) lastCoherenceScore = newScore
                        _uiState.update { it.copy(coherenceScore = lastCoherenceScore) }
                    }
                }
            }
        }
    }

    /** Tracks elapsed session time and handles countdown / auto-stop. */
    private fun startSessionTimer() {
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val elapsed = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()
                val currentState = _uiState.value

                if (!currentState.infinityMode) {
                    val remaining = (currentState.sessionDurationMinutes * 60) - elapsed
                    _uiState.update {
                        it.copy(
                            sessionElapsedSeconds = elapsed,
                            sessionRemainingSeconds = remaining.coerceAtLeast(0)
                        )
                    }
                    if (remaining <= 0) {
                        // Timer expired — stop session (which will save it)
                        stopSession()
                        return@launch
                    }
                } else {
                    _uiState.update { it.copy(sessionElapsedSeconds = elapsed) }
                }
            }
        }
    }

    /**
     * Polls the BLE RR buffer at 2 Hz to update live HRV metrics and the RR chart.
     */
    private fun startRrPollingLoop() {
        rrPollingJob = viewModelScope.launch {
            var lastSeenWrites = deviceManager.rrBuffer.totalWrites()
            while (isActive) {
                delay(500L) // 2 Hz for smooth chart updates
                val currentWrites = deviceManager.rrBuffer.totalWrites()
                val newCount = (currentWrites - lastSeenWrites).toInt()
                    .coerceAtMost(deviceManager.rrBuffer.capacity)
                if (newCount > 0) {
                    val newBeats = deviceManager.rrBuffer.readLast(newCount)
                    synchronized(allSessionRrIntervals) {
                        newBeats.forEach { rrMs -> allSessionRrIntervals.add(rrMs) }
                    }
                    lastSeenWrites = currentWrites
                }

                val totalNew = (currentWrites - rrWritesAtStart).toInt()
                    .coerceAtMost(deviceManager.rrBuffer.capacity)
                val snapshot = if (totalNew > 0) deviceManager.rrBuffer.readLast(totalNew) else emptyList()
                val liveRmssd = computeLiveRmssd(snapshot)
                val liveSdnn = computeLiveSdnn(snapshot)
                val chartRr = if (totalNew > 0) deviceManager.rrBuffer.readLast(45.coerceAtMost(totalNew)) else emptyList()
                _uiState.update {
                    it.copy(
                        liveRmssd = liveRmssd,
                        liveSdnn = liveSdnn,
                        rrCount = allSessionRrIntervals.size,
                        liveRrIntervals = chartRr
                    )
                }
            }
        }
    }

    private fun computeLiveRmssd(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size).toFloat()
    }

    private fun computeLiveSdnn(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val mean = rr.average()
        val variance = rr.sumOf { (it - mean) * (it - mean) } / rr.size
        return Math.sqrt(variance).toFloat()
    }

    fun startRfAssessment(protocol: RfProtocol, deviceId: String) {
        deviceManager.startRrStream(deviceId)
        rfCollectorJob?.cancel()

        _uiState.update { it.copy(slidingWindowResult = null) }

        rfOrchestrator.start(
            protocol = protocol,
            scope = viewModelScope,
            onEpochComplete = { /* epoch stored internally by orchestrator */ },
            onComplete = { results ->
                _uiState.update { it.copy(epochResults = results) }
            }
        )

        // Feed incoming RR intervals into the orchestrator
        rfCollectorJob = viewModelScope.launch {
            // Poll rrBuffer and forward new beats to orchestrator
            launch {
                var lastSeenWrites = deviceManager.rrBuffer.totalWrites()
                while (isActive) {
                    delay(200L)
                    val currentWrites = deviceManager.rrBuffer.totalWrites()
                    val newCount = (currentWrites - lastSeenWrites).toInt()
                        .coerceAtMost(deviceManager.rrBuffer.capacity)
                    if (newCount > 0) {
                        deviceManager.rrBuffer.readLast(newCount).forEach { rrMs ->
                            rfOrchestrator.feedRr(rrMs.toFloat())
                        }
                        lastSeenWrites = currentWrites
                    }
                }
            }

            // Collect legacy StateFlows (backward compat)
            launch {
                rfOrchestrator.phase.collect { phase ->
                    _uiState.update { it.copy(rfPhase = phase) }
                }
            }
            launch {
                rfOrchestrator.currentRateBpm.collect { rate ->
                    _uiState.update { it.copy(currentTestRateBpm = rate) }
                }
            }
            launch {
                rfOrchestrator.remainingSeconds.collect { secs ->
                    _uiState.update { it.copy(remainingSeconds = secs) }
                }
            }
            launch {
                rfOrchestrator.epochResults.collect { results ->
                    _uiState.update { it.copy(epochResults = results) }
                }
            }

            // Collect unified state for SLIDING_WINDOW result
            launch {
                rfOrchestrator.state.collect { orchState ->
                    when (orchState) {
                        is RfOrchestratorState.SlidingDone ->
                            _uiState.update { it.copy(slidingWindowResult = orchState.result) }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun stopRfAssessment() {
        rfCollectorJob?.cancel()
        rfOrchestrator.stop()
        _uiState.update { it.copy(rfPhase = RfPhase.IDLE, remainingSeconds = 0L) }
    }

    override fun onCleared() {
        super.onCleared()
        prepJob?.cancel()
        pacerJob?.cancel()
        coherenceJob?.cancel()
        rfCollectorJob?.cancel()
        rrPollingJob?.cancel()
        timerJob?.cancel()
        rfOrchestrator.stop()
    }
}
