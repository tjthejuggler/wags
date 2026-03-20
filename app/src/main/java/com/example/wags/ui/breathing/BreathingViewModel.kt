package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.AccRespirationEngine
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.usecase.breathing.CoherenceScoreCalculator
import com.example.wags.domain.usecase.breathing.ContinuousPacerEngine
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
    val breathingRateBpm: Float
)

@HiltViewModel
class BreathingViewModel @Inject constructor(
    private val bleManager: PolarBleManager,
    private val hrDataSource: HrDataSource,
    private val accEngine: AccRespirationEngine,
    private val pacerEngine: ContinuousPacerEngine,
    private val coherenceCalculator: CoherenceScoreCalculator,
    private val rfOrchestrator: RfAssessmentOrchestrator,
    private val habitRepo: HabitIntegrationRepository,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val timeDomainCalc: TimeDomainHrvCalculator,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(BreathingUiState())
    val uiState: StateFlow<BreathingUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BreathingUiState()
    )

    private var lastCoherenceScore = 0f
    private var lastCoherenceUpdateMs = 0L

    private var pacerJob: Job? = null
    private var coherenceJob: Job? = null
    private var rfCollectorJob: Job? = null
    private var prepJob: Job? = null
    private var rrPollingJob: Job? = null
    private var timerJob: Job? = null

    /** Buffer size at the moment the session started — used to ignore pre-session RR intervals */
    private var rrBufferSizeAtStart: Int = 0

    /** All RR intervals collected during this session (for post-session analytics). */
    private val allSessionRrIntervals = mutableListOf<Double>()
    private var sessionStartTimeMs = 0L

    // Coherence zone time tracking
    private var highCoherenceSeconds = 0
    private var mediumCoherenceSeconds = 0
    private var lowCoherenceSeconds = 0
    private var sessionPoints = 0f

    fun setBreathingRate(rateBpm: Float) {
        _uiState.update { it.copy(breathingRateBpm = rateBpm.coerceIn(4.0f, 7.0f)) }
    }

    fun setIeRatio(ratio: Float) {
        _uiState.update { it.copy(ieRatio = ratio.coerceIn(0.5f, 3.0f)) }
    }

    fun startSession(deviceId: String) {
        if (_uiState.value.isSessionActive) return
        bleManager.startRrStream(deviceId)
        rrBufferSizeAtStart = bleManager.rrBuffer.size()
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

    fun stopSession() {
        prepJob?.cancel()
        pacerJob?.cancel()
        coherenceJob?.cancel()
        rrPollingJob?.cancel()
        timerJob?.cancel()

        val currentState = _uiState.value

        // If we were actually breathing (not just preparing), compute summary
        if (currentState.sessionPhase == BreathingSessionPhase.BREATHING && allSessionRrIntervals.size >= 10) {
            val summary = computeSessionSummary(currentState)
            _uiState.update {
                it.copy(
                    isSessionActive = false,
                    sessionPhase = BreathingSessionPhase.COMPLETE,
                    sessionSummary = summary
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isSessionActive = false,
                    sessionPhase = BreathingSessionPhase.IDLE
                )
            }
        }

        // Signal the Habit app that a Resonance Breathing session was completed
        habitRepo.sendHabitIncrement(Slot.RESONANCE_BREATHING)
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
            breathingRateBpm = currentState.breathingRateBpm
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
                    val rrAll = bleManager.rrBuffer.readLast(256)
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

    /** Tracks elapsed session time. */
    private fun startSessionTimer() {
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val elapsed = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()
                _uiState.update { it.copy(sessionElapsedSeconds = elapsed) }
            }
        }
    }

    /**
     * Polls the BLE RR buffer at 2 Hz to update live HRV metrics and the RR chart.
     */
    private fun startRrPollingLoop() {
        rrPollingJob = viewModelScope.launch {
            var lastSeenCount = bleManager.rrBuffer.size()
            while (isActive) {
                delay(500L) // 2 Hz for smooth chart updates
                val currentCount = bleManager.rrBuffer.size()
                val newCount = currentCount - lastSeenCount
                if (newCount > 0) {
                    val newBeats = bleManager.rrBuffer.readLast(newCount)
                    synchronized(allSessionRrIntervals) {
                        newBeats.forEach { rrMs -> allSessionRrIntervals.add(rrMs) }
                    }
                    lastSeenCount = currentCount
                }

                val totalNew = currentCount - rrBufferSizeAtStart
                val snapshot = if (totalNew > 0) bleManager.rrBuffer.readLast(totalNew) else emptyList()
                val liveRmssd = computeLiveRmssd(snapshot)
                val liveSdnn = computeLiveSdnn(snapshot)
                val chartRr = if (totalNew > 0) bleManager.rrBuffer.readLast(45.coerceAtMost(totalNew)) else emptyList()
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
        bleManager.startRrStream(deviceId)
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
                var lastSeenCount = bleManager.rrBuffer.size()
                while (isActive) {
                    delay(200L)
                    val currentCount = bleManager.rrBuffer.size()
                    val newCount = currentCount - lastSeenCount
                    if (newCount > 0) {
                        bleManager.rrBuffer.readLast(newCount).forEach { rrMs ->
                            rfOrchestrator.feedRr(rrMs.toFloat())
                        }
                        lastSeenCount = currentCount
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
