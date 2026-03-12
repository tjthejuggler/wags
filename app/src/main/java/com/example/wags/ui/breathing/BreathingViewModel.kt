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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BreathingUiState(
    val breathingRateBpm: Float = 5.5f,
    val ieRatio: Float = 1.0f,
    val pacerRadius: Float = 0f,
    val breathPhaseLabel: String = "INHALE",
    val coherenceScore: Float = 0f,
    val isSessionActive: Boolean = false,
    val rfPhase: RfPhase = RfPhase.IDLE,
    val currentTestRateBpm: Float = 0f,
    val remainingSeconds: Long = 0L,
    val epochResults: List<RfEpochResult> = emptyList(),
    val slidingWindowResult: SlidingWindowResult? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null
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

    fun setBreathingRate(rateBpm: Float) {
        _uiState.update { it.copy(breathingRateBpm = rateBpm.coerceIn(4.0f, 7.0f)) }
    }

    fun setIeRatio(ratio: Float) {
        _uiState.update { it.copy(ieRatio = ratio.coerceIn(0.5f, 3.0f)) }
    }

    fun startSession(deviceId: String) {
        if (_uiState.value.isSessionActive) return
        bleManager.startRrStream(deviceId)
        pacerEngine.reset()
        _uiState.update { it.copy(isSessionActive = true) }
        startPacerLoop()
        startCoherenceLoop()
    }

    fun stopSession() {
        pacerJob?.cancel()
        coherenceJob?.cancel()
        _uiState.update { it.copy(isSessionActive = false) }
        // Signal the Habit app that a Resonance Breathing session was completed
        habitRepo.sendHabitIncrement(Slot.RESONANCE_BREATHING)
    }

    private fun startPacerLoop() {
        pacerJob = viewModelScope.launch {
            while (isActive) {
                delay(16L) // ~60 FPS
                val state = _uiState.value
                pacerEngine.tick(state.breathingRateBpm, state.ieRatio)
                _uiState.update {
                    it.copy(
                        pacerRadius = pacerEngine.getPacerRadius(state.ieRatio),
                        breathPhaseLabel = pacerEngine.breathPhaseLabel.value
                    )
                }
            }
        }
    }

    private fun startCoherenceLoop() {
        coherenceJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                if (now - lastCoherenceUpdateMs < 1_000L) continue
                lastCoherenceUpdateMs = now

                val rrSnapshot = bleManager.rrBuffer.readLast(256)
                if (rrSnapshot.size < 32) continue

                val newScore = withContext(mathDispatcher) {
                    val state = _uiState.value
                    val targetFreq = accEngine.breathRateBpm.value?.let { it / 60f }
                        ?: (state.breathingRateBpm / 60f)
                    coherenceCalculator.calculateFft(
                        nn = rrSnapshot.toDoubleArray(),
                        targetFreqHz = targetFreq.toDouble()
                    )
                }

                if (newScore > 0f) lastCoherenceScore = newScore
                _uiState.update { it.copy(coherenceScore = lastCoherenceScore) }
            }
        }
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
        pacerJob?.cancel()
        coherenceJob?.cancel()
        rfCollectorJob?.cancel()
        rfOrchestrator.stop()
    }
}
