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
import com.example.wags.domain.usecase.breathing.ContinuousPacerEngine
import com.example.wags.domain.usecase.breathing.RfAssessmentOrchestrator
import com.example.wags.domain.usecase.breathing.RfEpochResult
import com.example.wags.domain.usecase.breathing.RfOrchestratorState
import com.example.wags.domain.usecase.breathing.RfPhase
import com.example.wags.domain.usecase.breathing.RfProtocol
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
        val sessionId: Long? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var rrPollingJob: Job? = null
    private var pacerJob: Job? = null

    // Tracks the current pacer rate/ratio so the pacer loop can read them
    @Volatile private var pacerRateBpm: Float = 5.5f
    @Volatile private var pacerIeRatio: Float = 1.0f
    @Volatile private var pacerActive: Boolean = false

    // Captured when the session starts so the device label is stored even if it disconnects
    private val sessionHrDeviceLabel: String? = hrDataSource.activeHrDeviceLabel()

    init {
        pacerEngine.reset()
        orchestrator.start(protocol = protocol, scope = viewModelScope)
        collectOrchestratorState()
        startRrPolling()
        startPacerLoop()
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
                        val entity = RfAssessmentEntity(
                            timestamp       = System.currentTimeMillis(),
                            protocolType    = protocol.name,
                            optimalBpm      = result.resonanceFrequencyBpm,
                            optimalIeRatio  = 1.0f,
                            compositeScore  = result.peakResonanceIndex,
                            isValid         = result.isValid,
                            leaderboardJson = buildSlidingLeaderboardJson(result),
                            hrDeviceId      = sessionHrDeviceLabel
                        )
                        val id = saveEntity(entity)
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
        val entity = RfAssessmentEntity(
            timestamp       = System.currentTimeMillis(),
            protocolType    = protocol.name,
            optimalBpm      = best?.rateBpm ?: 0f,
            optimalIeRatio  = best?.ieRatio ?: 1.0f,
            compositeScore  = best?.compositeScore ?: 0f,
            isValid         = best?.isValid ?: false,
            leaderboardJson = buildSteppedLeaderboardJson(epochs),
            hrDeviceId      = sessionHrDeviceLabel
        )
        val id = saveEntity(entity)
        _uiState.value = _uiState.value.copy(
            phase      = "COMPLETE",
            isComplete = true,
            sessionId  = id
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
    // JSON serialization (minimal, no external library needed)
    // -------------------------------------------------------------------------

    private fun buildSteppedLeaderboardJson(epochs: List<RfEpochResult>): String {
        val entries = epochs.joinToString(",") { e ->
            """{"bpm":${e.rateBpm},"ie":${e.ieRatio},"score":${e.compositeScore},"valid":${e.isValid}}"""
        }
        return "[$entries]"
    }

    private fun buildSlidingLeaderboardJson(result: com.example.wags.domain.usecase.breathing.SlidingWindowResult): String {
        return """{"resonanceBpm":${result.resonanceFrequencyBpm},"peakIndex":${result.peakResonanceIndex},"peakPlv":${result.peakPlv},"valid":${result.isValid}}"""
    }

    // -------------------------------------------------------------------------
    // RR polling
    // -------------------------------------------------------------------------

    private fun startRrPolling() {
        rrPollingJob = viewModelScope.launch {
            var lastSeenCount = bleManager.rrBuffer.size()
            while (isActive) {
                delay(200L)
                val currentCount = bleManager.rrBuffer.size()
                val newCount = currentCount - lastSeenCount
                if (newCount > 0) {
                    bleManager.rrBuffer.readLast(newCount).forEach { rrMs ->
                        orchestrator.feedRr(rrMs.toFloat())
                    }
                    lastSeenCount = currentCount
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
        orchestrator.stop()
    }

    override fun onCleared() {
        super.onCleared()
        rrPollingJob?.cancel()
        pacerJob?.cancel()
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
