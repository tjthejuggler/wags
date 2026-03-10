package com.example.wags.ui.morning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.repository.MorningReadinessRepository
import com.example.wags.di.IoDispatcher
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HooperIndex
import com.example.wags.domain.model.MorningReadinessResult
import com.example.wags.domain.model.RrInterval
import com.example.wags.domain.usecase.readiness.MorningReadinessFsm
import com.example.wags.domain.usecase.readiness.MorningReadinessOrchestrator
import com.example.wags.domain.usecase.readiness.MorningReadinessState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MorningReadinessUiState(
    val fsmState: MorningReadinessState = MorningReadinessState.IDLE,
    val remainingSeconds: Int = 0,
    val liveRmssd: Double = 0.0,
    val rrCount: Int = 0,
    val peakStandHr: Int = 0,
    val hooperSleep: Int = 3,
    val hooperFatigue: Int = 3,
    val hooperSoreness: Int = 3,
    val hooperStress: Int = 3,
    val result: MorningReadinessResult? = null,
    val errorMessage: String? = null,
    val noHrmDialogVisible: Boolean = false,
    val isCalculating: Boolean = false,
    val triggerStandAlert: Boolean = false
)

@HiltViewModel
class MorningReadinessViewModel @Inject constructor(
    private val fsm: MorningReadinessFsm,
    private val orchestrator: MorningReadinessOrchestrator,
    private val repository: MorningReadinessRepository,
    private val bleManager: PolarBleManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(MorningReadinessUiState())
    val uiState: StateFlow<MorningReadinessUiState> = _uiState.asStateFlow()

    private var rrPollingJob: Job? = null
    private var lastRrBufferSize = 0
    private var storedHooper: HooperIndex? = null

    init {
        // Collect FSM state into UI state
        viewModelScope.launch {
            fsm.state.collect { fsmState ->
                _uiState.update { it.copy(fsmState = fsmState) }
            }
        }
        // Collect remaining seconds into UI state
        viewModelScope.launch {
            fsm.remainingSeconds.collect { secs ->
                _uiState.update { it.copy(remainingSeconds = secs) }
            }
        }
        // Collect FSM error messages
        viewModelScope.launch {
            fsm.errorMessage.collect { msg ->
                if (msg != null) {
                    _uiState.update { it.copy(errorMessage = msg) }
                }
            }
        }

        // Set FSM callbacks
        fsm.onStandPromptReady = {
            _uiState.update { it.copy(triggerStandAlert = true) }
        }
        fsm.onQuestionnaireRequired = {
            // UI already reacts to fsmState == QUESTIONNAIRE
        }
        fsm.onReadyToCalculate = {
            launchCalculation()
        }
    }

    fun startSession() {
        if (bleManager.h10State.value !is BleConnectionState.Connected) {
            _uiState.update { it.copy(noHrmDialogVisible = true) }
            return
        }

        lastRrBufferSize = 0
        fsm.start(viewModelScope)
        startRrPolling()
    }

    fun dismissNoHrmDialog() {
        _uiState.update { it.copy(noHrmDialogVisible = false) }
    }

    private fun startRrPolling() {
        rrPollingJob?.cancel()
        rrPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val snapshot = bleManager.rrBuffer.readLast(60)
                val currentSize = bleManager.rrBuffer.readLast(512).size

                // Feed new intervals to FSM
                if (currentSize > lastRrBufferSize) {
                    val newCount = currentSize - lastRrBufferSize
                    val allRecent = bleManager.rrBuffer.readLast(newCount.coerceAtMost(512))
                    allRecent.forEach { rrMs ->
                        fsm.addRrInterval(
                            RrInterval(
                                timestampMs = System.currentTimeMillis(),
                                intervalMs = rrMs
                            )
                        )
                    }
                    lastRrBufferSize = currentSize
                }

                // Update live RMSSD and counts
                val liveRmssd = computeRmssd(snapshot)
                _uiState.update {
                    it.copy(
                        liveRmssd = liveRmssd,
                        rrCount = fsm.supineBuffer.size + fsm.standingBuffer.size,
                        peakStandHr = fsm.peakStandHr
                    )
                }
            }
        }
    }

    private fun computeRmssd(rr: List<Double>): Double {
        if (rr.size < 2) return 0.0
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    private fun launchCalculation() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCalculating = true) }
            try {
                val input = MorningReadinessOrchestrator.Input(
                    supineBuffer = fsm.supineBuffer,
                    standingBuffer = fsm.standingBuffer,
                    peakStandHr = fsm.peakStandHr,
                    hooperIndex = storedHooper
                )

                val result = withContext(mathDispatcher) {
                    orchestrator.compute(input)
                }

                withContext(ioDispatcher) {
                    repository.save(result.toEntity())
                }

                fsm.markComplete()
                _uiState.update {
                    it.copy(
                        result = result,
                        isCalculating = false
                    )
                }
            } catch (e: Exception) {
                fsm.signalError(e.message ?: "Calculation failed")
                _uiState.update {
                    it.copy(
                        isCalculating = false,
                        errorMessage = e.message ?: "Calculation failed"
                    )
                }
            }
        }
    }

    fun updateHooper(sleep: Int, fatigue: Int, soreness: Int, stress: Int) {
        _uiState.update {
            it.copy(
                hooperSleep = sleep,
                hooperFatigue = fatigue,
                hooperSoreness = soreness,
                hooperStress = stress
            )
        }
    }

    fun submitHooper() {
        val state = _uiState.value
        storedHooper = HooperIndex(
            sleep = state.hooperSleep,
            fatigue = state.hooperFatigue,
            soreness = state.hooperSoreness,
            stress = state.hooperStress
        )
        fsm.submitHooper()
    }

    fun acknowledgeStandAlert() {
        _uiState.update { it.copy(triggerStandAlert = false) }
    }

    fun reset() {
        rrPollingJob?.cancel()
        rrPollingJob = null
        lastRrBufferSize = 0
        storedHooper = null
        fsm.reset()
        _uiState.value = MorningReadinessUiState()
    }

    override fun onCleared() {
        super.onCleared()
        rrPollingJob?.cancel()
    }
}

// Extension to map MorningReadinessResult → MorningReadinessEntity for persistence
private fun MorningReadinessResult.toEntity() = MorningReadinessEntity(
    timestamp = timestamp,
    supineRmssdMs = supineHrvMetrics.rmssdMs,
    supineLnRmssd = supineHrvMetrics.lnRmssd,
    supineSdnnMs = supineHrvMetrics.sdnnMs,
    supineRhr = supineRhr,
    standingRmssdMs = standingHrvMetrics.rmssdMs,
    standingLnRmssd = standingHrvMetrics.lnRmssd,
    standingSdnnMs = standingHrvMetrics.sdnnMs,
    peakStandHr = peakStandHr,
    thirtyFifteenRatio = thirtyFifteenRatio,
    ohrrAt20sPercent = ohrrAt20s,
    ohrrAt60sPercent = ohrrAt60s,
    respiratoryRateBpm = respiratoryRateBpm,
    slowBreathingFlagged = slowBreathingFlagged,
    hooperSleep = hooperSleep,
    hooperFatigue = hooperFatigue,
    hooperSoreness = hooperSoreness,
    hooperStress = hooperStress,
    hooperTotal = hooperIndex,
    artifactPercentSupine = artifactPercentSupine,
    artifactPercentStanding = artifactPercentStanding,
    readinessScore = readinessScore,
    readinessColor = readinessColor.name,
    hrvBaseScore = hvBaseScore,
    orthoMultiplier = orthoMultiplier,
    cvPenaltyApplied = cvPenaltyApplied,
    rhrLimiterApplied = rhrLimiterApplied
)
