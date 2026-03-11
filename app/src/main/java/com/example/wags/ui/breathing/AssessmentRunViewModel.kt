package com.example.wags.ui.breathing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.data.repository.RfAssessmentRepository
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
        val latestEpochScore: RfEpochResult? = null,
        val qualityWarning: String? = null,
        val isComplete: Boolean = false,
        val sessionId: Long? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var rrPollingJob: Job? = null

    init {
        orchestrator.start(protocol = protocol, scope = viewModelScope)
        collectOrchestratorState()
        startRrPolling()
    }

    // -------------------------------------------------------------------------
    // Orchestrator state → UiState mapping
    // -------------------------------------------------------------------------

    private fun collectOrchestratorState() {
        viewModelScope.launch {
            orchestrator.state.collect { orchState ->
                when (orchState) {
                    is RfOrchestratorState.Idle -> {
                        _uiState.value = _uiState.value.copy(phase = "IDLE")
                    }

                    is RfOrchestratorState.Active -> {
                        val phaseLabel = phaseLabel(orchState.phase, orchState.rateBpm)
                        val warning = qualityWarning(orchState.latestEpoch)
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
                        _uiState.value = _uiState.value.copy(
                            phase            = "TESTING %.1f BPM".format(orchState.pacerState.instantBpm),
                            currentBpm       = orchState.pacerState.instantBpm,
                            progress         = orchState.progress,
                            refWave          = orchState.pacerState.refWave
                        )
                    }

                    is RfOrchestratorState.Complete -> {
                        saveSteppedSession(orchState.epochs)
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
                            leaderboardJson = buildSlidingLeaderboardJson(result)
                        )
                        val id = saveEntity(entity)
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
            leaderboardJson = buildSteppedLeaderboardJson(epochs)
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
    // Cancel
    // -------------------------------------------------------------------------

    fun cancel() {
        rrPollingJob?.cancel()
        orchestrator.stop()
    }

    override fun onCleared() {
        super.onCleared()
        rrPollingJob?.cancel()
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
