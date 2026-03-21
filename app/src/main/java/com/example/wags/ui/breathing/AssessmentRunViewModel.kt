package com.example.wags.ui.breathing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.RfAssessmentRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssessmentRunViewModel @Inject constructor(
    private val orchestrator: RfAssessmentOrchestrator,
    private val repository: RfAssessmentRepository,
    private val bleManager: PolarBleManager,
    private val pacerEngine: ContinuousPacerEngine,
    private val hrDataSource: HrDataSource,
    private val habitRepo: HabitIntegrationRepository,
    private val coherenceCalc: CoherenceScoreCalculator,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val timeDomainCalc: TimeDomainHrvCalculator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Protocol passed as nav argument
    val protocol: RfProtocol = RfProtocol.valueOf(
        savedStateHandle.get<String>("protocol") ?: RfProtocol.EXPRESS.name
    )

    data class UiState(
        val phase: String = "IDLE",
        val currentBpm: Float = 0f,
        val remainingSeconds: Int = 0,
        val progress: Float = 0f,
        val refWave: Float = 0f,
        /** Previous refWave value — used to determine direction (inhale vs exhale). */
        val lastRefWave: Float = 0f,
        /** Whether the pacer is currently in the inhale phase. */
        val isInhaling: Boolean = true,
        val latestEpochScore: RfEpochResult? = null,
        val qualityWarning: String? = null,
        val isComplete: Boolean = false,
        val sessionId: Long? = null,
        /** Live coherence ratio (updated every 5s during assessment). */
        val liveCoherenceRatio: Float = 0f,
        /** Live HR from sensor. */
        val liveHr: Int? = null,
        /** Count of RR intervals collected so far. */
        val rrCount: Int = 0,
        /** Recent RR intervals (ms) for the scrolling chart — last ~45 values. */
        val liveRrIntervals: List<Double> = emptyList(),
        /** Live RMSSD (ms) computed from recent RR intervals. */
        val liveRmssd: Float? = null,
        /** Live SDNN (ms) computed from recent RR intervals. */
        val liveSdnn: Float? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var rrPollingJob: Job? = null
    private var pacerJob: Job? = null
    private var coherenceJob: Job? = null

    // Tracks the current pacer rate/ratio so the pacer loop can read them
    @Volatile private var pacerRateBpm: Float = 5.5f
    @Volatile private var pacerIeRatio: Float = 1.0f
    @Volatile private var pacerActive: Boolean = false

    // Captured when the session starts so the device label is stored even if it disconnects
    private val sessionHrDeviceLabel: String? = hrDataSource.activeHrDeviceLabel()

    // Track all RR intervals for post-session analytics
    private val allSessionRrIntervals = mutableListOf<Double>()
    private val sessionStartTimeMs = System.currentTimeMillis()

    init {
        pacerEngine.reset()
        orchestrator.start(protocol = protocol, scope = viewModelScope)
        collectOrchestratorState()
        startRrPolling()
        startPacerLoop()
        startLiveCoherenceLoop()
    }

    // -------------------------------------------------------------------------
    // Orchestrator state → UiState mapping
    // -------------------------------------------------------------------------

    private fun collectOrchestratorState() {
        viewModelScope.launch {
            orchestrator.state.collect { orchState ->
                when (orchState) {
                    is RfOrchestratorState.Idle -> {
                        pacerActive = false
                        _uiState.value = _uiState.value.copy(phase = "IDLE")
                    }

                    is RfOrchestratorState.Active -> {
                        val phaseLabel = phaseLabel(orchState.phase, orchState.rateBpm)
                        val warning = qualityWarning(orchState.latestEpoch)

                        // Only animate the pacer during TEST_BLOCK — BASELINE and WASHOUT
                        // are unguided phases where the user breathes freely.
                        when (orchState.phase) {
                            RfPhase.TEST_BLOCK -> {
                                pacerRateBpm = orchState.rateBpm.takeIf { it > 0f } ?: 5.5f
                                pacerIeRatio = orchState.ieRatio.takeIf { it > 0f } ?: 1.0f
                                pacerActive = true
                            }
                            else -> {
                                pacerActive = false
                            }
                        }

                        _uiState.value = _uiState.value.copy(
                            phase             = phaseLabel,
                            currentBpm        = orchState.rateBpm,
                            remainingSeconds  = orchState.remainingSeconds.toInt(),
                            progress          = orchState.progress,
                            latestEpochScore  = orchState.latestEpoch,
                            qualityWarning    = warning
                        )
                    }

                    is RfOrchestratorState.SlidingTick -> {
                        pacerActive = false   // sliding window drives its own refWave
                        val prevWave = _uiState.value.refWave
                        val newWave = orchState.pacerState.refWave
                        _uiState.value = _uiState.value.copy(
                            phase            = "TESTING %.1f BPM".format(orchState.pacerState.instantBpm),
                            currentBpm       = orchState.pacerState.instantBpm,
                            progress         = orchState.progress,
                            lastRefWave      = prevWave,
                            refWave          = newWave,
                            isInhaling       = newWave > prevWave || newWave > 0.95f
                        )
                    }

                    is RfOrchestratorState.Complete -> {
                        pacerActive = false
                        saveSteppedSession(orchState.epochs)
                        // Signal the Habit app that a Resonance Breathing assessment completed
                        habitRepo.sendHabitIncrement(Slot.RESONANCE_BREATHING)
                    }

                    is RfOrchestratorState.SlidingDone -> {
                        val result = orchState.result
                        val enrichedEntity = buildEnrichedSlidingEntity(result)
                        val id = saveEntity(enrichedEntity)
                        // Signal the Habit app that a Resonance Breathing assessment completed
                        habitRepo.sendHabitIncrement(Slot.RESONANCE_BREATHING)
                        _uiState.value = _uiState.value.copy(
                            phase      = "COMPLETE",
                            isComplete = true,
                            sessionId  = id
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DB save helpers
    // -------------------------------------------------------------------------

    private suspend fun saveSteppedSession(epochs: List<RfEpochResult>) {
        val validEpochs = epochs.filter { it.isValid }
        val best = validEpochs.maxByOrNull { it.compositeScore } ?: epochs.firstOrNull()

        // Compute enriched metrics from all collected RR intervals
        val rrSnapshot = allSessionRrIntervals.toDoubleArray()
        val correctionResult = if (rrSnapshot.size >= 10) artifactCorrection.execute(rrSnapshot.toList()) else null
        val correctedNn = correctionResult?.correctedNn ?: rrSnapshot
        val artifactPct = if (rrSnapshot.isNotEmpty() && correctionResult != null)
            correctionResult.artifactCount.toFloat() / rrSnapshot.size * 100f else 0f

        val timeDomain = if (correctedNn.size >= 4) timeDomainCalc.calculate(correctedNn) else null
        val coherenceResult = coherenceCalc.calculateCoherenceRatio(correctedNn)
        val absLfPower = coherenceCalc.calculateAbsoluteLfPower(correctedNn)
        val powerSpectrum = coherenceCalc.extractPowerSpectrum(correctedNn)

        val durationSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()

        // Build resonance curve JSON from epochs
        val resonanceCurveJson = buildResonanceCurveJson(epochs)

        // Build HR waveform from the best epoch's RR data (approximate from all data)
        val hrWaveformJson = buildHrWaveformJson(correctedNn)

        // Build power spectrum JSON
        val powerSpectrumJson = buildPowerSpectrumJson(powerSpectrum)

        val entity = RfAssessmentEntity(
            timestamp           = System.currentTimeMillis(),
            protocolType        = protocol.name,
            optimalBpm          = best?.rateBpm ?: 0f,
            optimalIeRatio      = best?.ieRatio ?: 1.0f,
            compositeScore      = best?.compositeScore ?: 0f,
            isValid             = best?.isValid ?: false,
            leaderboardJson     = buildSteppedLeaderboardJson(epochs),
            hrDeviceId          = sessionHrDeviceLabel,
            peakToTroughBpm     = best?.ptAmplitude ?: 0f,
            maxLfPowerMs2       = absLfPower,
            maxCoherenceRatio   = coherenceResult.coherenceRatio,
            meanRmssdMs         = timeDomain?.rmssdMs?.toFloat() ?: 0f,
            meanSdnnMs          = timeDomain?.sdnnMs?.toFloat() ?: 0f,
            durationSeconds     = durationSec,
            totalBeats          = rrSnapshot.size,
            artifactPercent     = artifactPct,
            resonanceCurveJson  = resonanceCurveJson,
            hrWaveformJson      = hrWaveformJson,
            powerSpectrumJson   = powerSpectrumJson
        )
        val id = saveEntity(entity)
        _uiState.value = _uiState.value.copy(
            phase      = "COMPLETE",
            isComplete = true,
            sessionId  = id
        )
    }

    private fun buildEnrichedSlidingEntity(result: SlidingWindowResult): RfAssessmentEntity {
        val rrSnapshot = allSessionRrIntervals.toDoubleArray()
        val correctionResult = if (rrSnapshot.size >= 10) artifactCorrection.execute(rrSnapshot.toList()) else null
        val correctedNn = correctionResult?.correctedNn ?: rrSnapshot
        val artifactPct = if (rrSnapshot.isNotEmpty() && correctionResult != null)
            correctionResult.artifactCount.toFloat() / rrSnapshot.size * 100f else 0f

        val timeDomain = if (correctedNn.size >= 4) timeDomainCalc.calculate(correctedNn) else null
        val coherenceResult = coherenceCalc.calculateCoherenceRatio(correctedNn)
        val absLfPower = coherenceCalc.calculateAbsoluteLfPower(correctedNn)
        val powerSpectrum = coherenceCalc.extractPowerSpectrum(correctedNn)

        val durationSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()

        // Build resonance curve from sliding window series
        val resonanceCurveJson = buildSlidingResonanceCurveJson(result)
        val hrWaveformJson = buildHrWaveformJson(correctedNn)
        val powerSpectrumJson = buildPowerSpectrumJson(powerSpectrum)

        return RfAssessmentEntity(
            timestamp           = System.currentTimeMillis(),
            protocolType        = protocol.name,
            optimalBpm          = result.resonanceFrequencyBpm,
            optimalIeRatio      = 1.0f,
            compositeScore      = result.peakResonanceIndex,
            isValid             = result.isValid,
            leaderboardJson     = buildSlidingLeaderboardJson(result),
            hrDeviceId          = sessionHrDeviceLabel,
            peakToTroughBpm     = if (result.ptAmpSeries.isNotEmpty()) result.ptAmpSeries.max() / 10f else 0f,
            maxLfPowerMs2       = absLfPower,
            maxCoherenceRatio   = coherenceResult.coherenceRatio,
            meanRmssdMs         = timeDomain?.rmssdMs?.toFloat() ?: 0f,
            meanSdnnMs          = timeDomain?.sdnnMs?.toFloat() ?: 0f,
            durationSeconds     = durationSec,
            totalBeats          = rrSnapshot.size,
            artifactPercent     = artifactPct,
            resonanceCurveJson  = resonanceCurveJson,
            hrWaveformJson      = hrWaveformJson,
            powerSpectrumJson   = powerSpectrumJson
        )
    }

    /**
     * Saves entity via repository. Returns the entity's timestamp as a stable
     * session identifier (repository.saveSession() does not return the row ID).
     */
    private suspend fun saveEntity(entity: RfAssessmentEntity): Long {
        repository.saveSession(entity)
        return entity.timestamp
    }

    // -------------------------------------------------------------------------
    // JSON serialization
    // -------------------------------------------------------------------------

    private fun buildSteppedLeaderboardJson(epochs: List<RfEpochResult>): String {
        val entries = epochs.joinToString(",") { e ->
            """{"bpm":${e.rateBpm},"ie":${e.ieRatio},"score":${e.compositeScore},"valid":${e.isValid},"ptAmp":${e.ptAmplitude},"lfNu":${e.lfNu},"phase":${e.phaseSynchrony}}"""
        }
        return "[$entries]"
    }

    private fun buildSlidingLeaderboardJson(result: SlidingWindowResult): String {
        return """{"resonanceBpm":${result.resonanceFrequencyBpm},"peakIndex":${result.peakResonanceIndex},"peakPlv":${result.peakPlv},"valid":${result.isValid}}"""
    }

    private fun buildResonanceCurveJson(epochs: List<RfEpochResult>): String {
        val entries = epochs.joinToString(",") { e ->
            """{"bpm":${e.rateBpm},"score":${e.compositeScore},"ptAmp":${e.ptAmplitude},"lfNu":${e.lfNu},"phase":${e.phaseSynchrony},"valid":${e.isValid}}"""
        }
        return "[$entries]"
    }

    private fun buildSlidingResonanceCurveJson(result: SlidingWindowResult): String {
        if (result.timeGrid.isEmpty()) return "[]"
        val entries = result.timeGrid.indices.joinToString(",") { i ->
            """{"bpm":${result.pacingBpmSeries[i]},"lfPower":${result.lfPowerSeries[i]},"ptAmp":${result.ptAmpSeries[i]},"plv":${result.plvSeries[i]}}"""
        }
        return "[$entries]"
    }

    private fun buildHrWaveformJson(correctedNn: DoubleArray): String {
        if (correctedNn.size < 4) return "[]"
        // Take last 60 seconds worth of data (approx 60-80 beats)
        val beatsFor60s = minOf(correctedNn.size, 80)
        val slice = correctedNn.takeLast(beatsFor60s)
        var timeMs = 0.0
        val entries = slice.joinToString(",") { rr ->
            val hrBpm = 60000.0 / rr.coerceAtLeast(1.0)
            timeMs += rr
            """{"t":${timeMs.toInt()},"hr":${"%.1f".format(hrBpm)}}"""
        }
        return "[$entries]"
    }

    private fun buildPowerSpectrumJson(spectrum: List<com.example.wags.domain.usecase.breathing.PowerSpectrumPoint>): String {
        if (spectrum.isEmpty()) return "[]"
        val entries = spectrum.joinToString(",") { p ->
            """{"f":${p.frequencyHz},"p":${p.powerMs2}}"""
        }
        return "[$entries]"
    }

    // -------------------------------------------------------------------------
    // RR polling
    // -------------------------------------------------------------------------

    private fun startRrPolling() {
        val rrBufferSizeAtStart = bleManager.rrBuffer.size()
        rrPollingJob = viewModelScope.launch {
            var lastSeenCount = bleManager.rrBuffer.size()
            while (isActive) {
                delay(500L) // 2 Hz — matches BreathingViewModel cadence
                val currentCount = bleManager.rrBuffer.size()
                val newCount = currentCount - lastSeenCount
                if (newCount > 0) {
                    val newBeats = bleManager.rrBuffer.readLast(newCount)
                    newBeats.forEach { rrMs ->
                        orchestrator.feedRr(rrMs.toFloat())
                        allSessionRrIntervals.add(rrMs)
                    }
                    lastSeenCount = currentCount
                }

                // Compute live metrics from all session RR data
                val totalNew = currentCount - rrBufferSizeAtStart
                val snapshot = if (totalNew > 0) bleManager.rrBuffer.readLast(totalNew) else emptyList()
                val chartRr = if (totalNew > 0) bleManager.rrBuffer.readLast(45.coerceAtMost(totalNew)) else emptyList()
                val liveRmssd = computeLiveRmssd(snapshot)
                val liveSdnn = computeLiveSdnn(snapshot)

                _uiState.value = _uiState.value.copy(
                    rrCount = allSessionRrIntervals.size,
                    liveHr = hrDataSource.liveHr.value,
                    liveRrIntervals = chartRr,
                    liveRmssd = liveRmssd,
                    liveSdnn = liveSdnn
                )
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

    // -------------------------------------------------------------------------
    // Live coherence loop — updates every 5 seconds
    // -------------------------------------------------------------------------

    private fun startLiveCoherenceLoop() {
        coherenceJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000L)
                val rrSnapshot = synchronized(allSessionRrIntervals) {
                    if (allSessionRrIntervals.size >= 32) allSessionRrIntervals.takeLast(256).toDoubleArray()
                    else null
                }
                if (rrSnapshot != null) {
                    val result = coherenceCalc.calculateCoherenceRatio(rrSnapshot)
                    _uiState.value = _uiState.value.copy(
                        liveCoherenceRatio = result.coherenceRatio
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pacer loop — drives refWave for stepped protocol phases
    // -------------------------------------------------------------------------

    private fun startPacerLoop() {
        pacerJob = viewModelScope.launch {
            while (isActive) {
                delay(16L) // ~60 FPS
                if (!pacerActive) continue
                val rate = pacerRateBpm
                val ie   = pacerIeRatio
                pacerEngine.tick(rate, ie)
                val wave = pacerEngine.getPacerRadius(ie)
                val prevWave = _uiState.value.refWave
                val isInhaling = pacerEngine.breathPhaseLabel.value == "INHALE"
                _uiState.value = _uiState.value.copy(
                    lastRefWave = prevWave,
                    refWave = wave,
                    isInhaling = isInhaling
                )
            }
        }
    }

    fun cancel() {
        rrPollingJob?.cancel()
        pacerJob?.cancel()
        coherenceJob?.cancel()
        orchestrator.stop()
    }

    override fun onCleared() {
        super.onCleared()
        rrPollingJob?.cancel()
        pacerJob?.cancel()
        coherenceJob?.cancel()
        orchestrator.stop()
    }

    // -------------------------------------------------------------------------
    // Label helpers
    // -------------------------------------------------------------------------

    private fun phaseLabel(phase: RfPhase, rateBpm: Float): String = when (phase) {
        RfPhase.IDLE      -> "IDLE"
        RfPhase.BASELINE  -> "BASELINE"
        RfPhase.TEST_BLOCK -> "TESTING %.1f BPM".format(rateBpm)
        RfPhase.WASHOUT   -> "WASHOUT"
        RfPhase.COMPLETE  -> "COMPLETE"
    }

    private fun qualityWarning(epoch: RfEpochResult?): String? {
        if (epoch == null || epoch.isValid) return null
        return when {
            epoch.phaseSynchrony < 0.25f ->
                "Low phase synchrony (%.2f) — try to follow the pacer".format(epoch.phaseSynchrony)
            epoch.ptAmplitude < 1.5f ->
                "Low PT amplitude (%.1f BPM) — breathe more deeply".format(epoch.ptAmplitude)
            else -> "Epoch invalid — data quality insufficient"
        }
    }
}
