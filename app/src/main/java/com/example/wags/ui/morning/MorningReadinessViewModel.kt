package com.example.wags.ui.morning

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.db.entity.MorningReadinessTelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
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
import com.example.wags.domain.usecase.readiness.StandDetector
import kotlin.math.roundToInt
import kotlin.math.sqrt
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

data class MorningReadinessUiState(
    val fsmState: MorningReadinessState = MorningReadinessState.IDLE,
    val remainingSeconds: Int = 0,
    val liveRmssd: Double = 0.0,
    val liveSdnn: Double = 0.0,
    val rrCount: Int = 0,
    val peakStandHr: Int? = null,
    val hooperSleep: Float = 50f,
    val hooperFatigue: Float = 50f,
    val hooperSoreness: Float = 50f,
    val hooperStress: Float = 50f,
    val result: MorningReadinessResult? = null,
    val errorMessage: String? = null,
    val noHrmDialogVisible: Boolean = false,
    val isCalculating: Boolean = false,
    val triggerStandAlert: Boolean = false,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Recent RR intervals (ms) for the scrolling chart — last ~30 s worth. */
    val liveRrIntervals: List<Double> = emptyList()
)

@HiltViewModel
class MorningReadinessViewModel @Inject constructor(
    private val fsm: MorningReadinessFsm,
    private val orchestrator: MorningReadinessOrchestrator,
    private val repository: MorningReadinessRepository,
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val habitRepo: HabitIntegrationRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    // Captured at session-start so we record the device that was connected when the
    // assessment began, even if it disconnects before the result is saved.
    private var sessionHrDeviceLabel: String? = null

    private val _uiState = MutableStateFlow(MorningReadinessUiState())
    val uiState: StateFlow<MorningReadinessUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MorningReadinessUiState()
    )

    private var rrPollingJob: Job? = null
    private var accPollingJob: Job? = null
    private var lastRrBufferSize = 0
    private var storedHooper: HooperIndex? = null
    private val standDetector = StandDetector()

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
            // Arm the stand detector with the last ~1 second of supine ACC data
            val supineSamples = deviceManager.accBuffer.readLast(200)
            standDetector.arm(supineSamples)
            // The FSM timestamp is the fallback if ACC detection times out
            standDetector.setFallbackTimestamp(System.currentTimeMillis())
            startAccPolling()
        }
        fsm.onQuestionnaireRequired = {
            // UI already reacts to fsmState == QUESTIONNAIRE
        }
        fsm.onReadyToCalculate = {
            launchCalculation()
        }
    }

    fun startSession() {
        // Morning readiness requires an H10 (chest strap with ACC for stand detection).
        // The device type is determined by name — if the connected Polar device's name
        // contains "H10", it's an H10 regardless of which slot it was assigned to.
        val h10Id = deviceManager.polarBleManager.connectedH10DeviceId()
        if (h10Id == null) {
            _uiState.update { it.copy(noHrmDialogVisible = true) }
            return
        }

        sessionHrDeviceLabel = hrDataSource.activeHrDeviceLabel()
        lastRrBufferSize = 0
        standDetector.reset()
        // Start ACC stream so the stand detector has data
        deviceManager.startAccStream(h10Id)
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
                delay(500L) // Poll at 2 Hz for smoother chart updates
                val snapshot = deviceManager.rrBuffer.readLast(60)
                val currentSize = deviceManager.rrBuffer.readLast(512).size

                // Feed new intervals to FSM with reconstructed timestamps.
                // The BLE buffer only stores raw RR interval durations (ms), not
                // wall-clock timestamps. We reconstruct proper timestamps by
                // working backwards from "now": the last beat in the batch ends
                // at the current time, and each preceding beat's timestamp is
                // offset by the cumulative RR durations that follow it.
                // This is critical for the OhrrCalculator which needs accurate
                // timestamps to find HR at 20 s and 60 s post-peak.
                if (currentSize > lastRrBufferSize) {
                    val newCount = currentSize - lastRrBufferSize
                    val allRecent = deviceManager.rrBuffer.readLast(newCount.coerceAtMost(512))
                    val now = System.currentTimeMillis()
                    // Total duration of all intervals in this batch
                    val totalDurationMs = allRecent.sumOf { it }.toLong()
                    // The first beat's timestamp = now - totalDuration;
                    // each subsequent beat advances by its own interval.
                    var cumulativeMs = now - totalDurationMs
                    allRecent.forEach { rrMs ->
                        cumulativeMs += rrMs.toLong()
                        fsm.addRrInterval(
                            RrInterval(
                                timestampMs = cumulativeMs,
                                intervalMs = rrMs
                            )
                        )
                    }
                    lastRrBufferSize = currentSize
                }

                // Grab ~30 s worth of RR intervals for the scrolling chart.
                // At a typical resting HR of ~60 bpm that's ~30 beats; at 80 bpm ~40 beats.
                // We read the last 45 to cover a range of heart rates.
                val chartRr = deviceManager.rrBuffer.readLast(45)

                // Update live RMSSD, SDNN, chart data, and counts
                val liveRmssd = computeRmssd(snapshot)
                val liveSdnn = computeSdnn(snapshot)
                _uiState.update {
                    it.copy(
                        liveRmssd = liveRmssd,
                        liveSdnn = liveSdnn,
                        rrCount = fsm.supineBuffer.size + fsm.standingBuffer.size,
                        peakStandHr = fsm.peakStandHr.takeIf { it > 0 },
                        liveRrIntervals = chartRr
                    )
                }
            }
        }
    }

    /**
     * Polls the ACC buffer every 100 ms while the stand detector is armed.
     * Once a stand is detected (or times out), updates the FSM's stand timestamp
     * and stops polling.
     */
    private fun startAccPolling() {
        accPollingJob?.cancel()
        accPollingJob = viewModelScope.launch {
            while (isActive && !standDetector.isDetected) {
                delay(100L)
                val samples = deviceManager.accBuffer.readLast(20)  // last ~100ms at 200 Hz
                val detectedTs = standDetector.checkSamples(samples)
                if (detectedTs != null) {
                    // Inform the FSM of the precise stand timestamp
                    fsm.updateStandTimestamp(detectedTs)
                    break
                }
            }
            accPollingJob = null
        }
    }

    private fun computeRmssd(rr: List<Double>): Double {
        if (rr.size < 2) return 0.0
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    private fun computeSdnn(rr: List<Double>): Double {
        if (rr.size < 2) return 0.0
        val mean = rr.average()
        val variance = rr.sumOf { (it - mean) * (it - mean) } / rr.size
        return sqrt(variance)
    }

    private fun launchCalculation() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCalculating = true) }
            Log.d(TAG, "launchCalculation: starting")

            // Track whether we successfully reached COMPLETE so the catch block
            // never corrupts state after the user already sees results.
            var completedSuccessfully = false

            try {
                // Cancel polling jobs BEFORE snapshotting the FSM buffers.
                // The rrPollingJob runs on the main dispatcher and calls fsm.addRrInterval()
                // which mutates _supineBuffer/_standingBuffer. If we don't stop it first,
                // we get a ConcurrentModificationException when the orchestrator iterates
                // those lists on the math dispatcher.
                rrPollingJob?.cancel()
                rrPollingJob = null
                accPollingJob?.cancel()
                accPollingJob = null

                // Snapshot the buffers now that no writer can mutate them.
                val supineBuffer   = fsm.supineBuffer
                val standingBuffer = fsm.standingBuffer
                val standTs        = fsm.standTimestampMs

                Log.d(TAG, "launchCalculation: supine=${supineBuffer.size} standing=${standingBuffer.size} peakHr=${fsm.peakStandHr}")

                val input = MorningReadinessOrchestrator.Input(
                    supineBuffer   = supineBuffer,
                    standingBuffer = standingBuffer,
                    peakStandHr    = fsm.peakStandHr,
                    hooperIndex    = storedHooper
                )

                val result = withContext(mathDispatcher) {
                    orchestrator.compute(input)
                }
                Log.d(TAG, "launchCalculation: compute done, score=${result.readinessScore}")

                withContext(ioDispatcher) {
                    // Save the main entity first to get its auto-generated id
                    val savedId = repository.save(
                        result.toEntity(sessionHrDeviceLabel, standTs)
                    )
                    Log.d(TAG, "launchCalculation: saved entity id=$savedId")
                    // Build and save per-beat telemetry rows
                    val telemetryRows = buildTelemetryRows(
                        readingId      = savedId,
                        supineBuffer   = supineBuffer,
                        standingBuffer = standingBuffer
                    )
                    repository.saveTelemetry(telemetryRows)
                    Log.d(TAG, "launchCalculation: saved ${telemetryRows.size} telemetry rows")
                }

                // Transition the FSM to COMPLETE and set the result in _uiState
                // atomically in a single update so the screen never sees fsmState=COMPLETE
                // with result=null.
                //
                // Background: uiState is a combine() of _uiState + liveHr + liveSpO2.
                // If we called fsm.markComplete() first, the FSM state collector in init{}
                // would update _uiState.fsmState=COMPLETE in a separate coroutine, and the
                // combine() emission the screen observes could arrive before the result
                // update — causing the screen to briefly show CalculatingContent() or crash
                // on a null result dereference.
                //
                // By setting both result AND fsmState=COMPLETE in one _uiState.update FIRST,
                // and then calling fsm.markComplete() afterwards, we guarantee the screen
                // always sees them together. The FSM collector may fire redundantly but
                // fsmState is already COMPLETE so it's a no-op.
                _uiState.update {
                    it.copy(
                        fsmState = MorningReadinessState.COMPLETE,
                        result = result,
                        isCalculating = false
                    )
                }
                fsm.markComplete()
                completedSuccessfully = true
                Log.d(TAG, "launchCalculation: COMPLETE — score=${result.readinessScore} color=${result.readinessColor}")

            } catch (e: Exception) {
                Log.e(TAG, "launchCalculation: FAILED — ${e.javaClass.simpleName}: ${e.message}", e)
                // Only transition to ERROR if we haven't already shown results.
                // If completedSuccessfully is true, the user already sees the result
                // screen — transitioning to ERROR would corrupt the UI.
                if (!completedSuccessfully) {
                    fsm.signalError(e.message ?: "Calculation failed")
                    _uiState.update {
                        it.copy(
                            isCalculating = false,
                            errorMessage = e.message ?: "Calculation failed"
                        )
                    }
                }
            }

            // Fire-and-forget: signal the Habit app OUTSIDE the try/catch.
            // This must never affect the success/error path above.
            // sendHabitIncrement has its own internal try/catch for SecurityException.
            if (completedSuccessfully) {
                try {
                    habitRepo.sendHabitIncrement(Slot.MORNING_READINESS)
                } catch (e: Exception) {
                    Log.w(TAG, "launchCalculation: habit increment failed (non-fatal): ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MorningReadinessVM"
    }

    fun updateHooper(sleep: Float, fatigue: Float, soreness: Float, stress: Float) {
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
        // UI sliders run 1–100; HooperIndex expects 1–5.
        // Normalize: hooper1to5 = (raw - 1) / 99 * 4 + 1
        fun norm(v: Float): Float = (v - 1f) / 99f * 4f + 1f
        storedHooper = HooperIndex(
            sleep    = norm(state.hooperSleep),
            fatigue  = norm(state.hooperFatigue),
            soreness = norm(state.hooperSoreness),
            stress   = norm(state.hooperStress)
        )
        fsm.submitHooper()
    }

    fun acknowledgeStandAlert() {
        _uiState.update { it.copy(triggerStandAlert = false) }
    }

    /**
     * Called when the user taps "No Standing" during the stand prompt.
     * Cancels the standing phase and jumps straight to the questionnaire.
     * The report is still saved — just without any orthostatic / standing data.
     */
    fun skipStanding() {
        accPollingJob?.cancel()
        accPollingJob = null
        fsm.skipStanding()
    }

    fun reset() {
        rrPollingJob?.cancel()
        rrPollingJob = null
        accPollingJob?.cancel()
        accPollingJob = null
        lastRrBufferSize = 0
        storedHooper = null
        standDetector.reset()
        fsm.reset()
        _uiState.value = MorningReadinessUiState()
    }

    override fun onCleared() {
        super.onCleared()
        rrPollingJob?.cancel()
        accPollingJob?.cancel()
    }
}

// Extension to map MorningReadinessResult → MorningReadinessEntity for persistence
private fun MorningReadinessResult.toEntity(
    hrDeviceId: String?,
    standTimestampMs: Long? = null
) = MorningReadinessEntity(
    timestamp = timestamp,
    supineRmssdMs = supineHrvMetrics.rmssdMs,
    supineLnRmssd = supineHrvMetrics.lnRmssd,
    supineSdnnMs = supineHrvMetrics.sdnnMs,
    supineRhr = supineRhr,
    standingRmssdMs = standingHrvMetrics?.rmssdMs,
    standingLnRmssd = standingHrvMetrics?.lnRmssd,
    standingSdnnMs = standingHrvMetrics?.sdnnMs,
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
    rhrLimiterApplied = rhrLimiterApplied,
    hrDeviceId = hrDeviceId,
    standTimestampMs = standTimestampMs
)

/**
 * Converts the raw supine + standing RR buffers into per-beat telemetry rows.
 *
 * For each beat we record:
 *  - timestampMs   : from the RrInterval
 *  - hrBpm         : 60_000 / rrMs, with motion-artifact spike suppression
 *  - rollingRmssdMs: RMSSD over the preceding 20-beat sliding window
 *  - phase         : "SUPINE" or "STANDING"
 *
 * HR spike suppression:
 *   A single beat whose HR is >40 bpm above the median of its ±5-beat neighbourhood
 *   is almost certainly a motion artifact (e.g. the H10 strap shifting as the user
 *   stands). We replace such spikes with the neighbourhood median so the chart
 *   remains readable. The raw RR interval is still used for HRV calculations
 *   (artifact correction happens separately in the orchestrator).
 */
private fun buildTelemetryRows(
    readingId: Long,
    supineBuffer: List<RrInterval>,
    standingBuffer: List<RrInterval>
): List<MorningReadinessTelemetryEntity> {
    val windowSize = 20
    val spikeNeighbourhood = 5   // ±5 beats for spike detection
    val spikeThresholdBpm = 40   // bpm above neighbourhood median → artifact

    // Combine both phases into a single ordered list with phase tags
    val combined: List<Pair<RrInterval, String>> =
        supineBuffer.map { it to "SUPINE" } + standingBuffer.map { it to "STANDING" }

    // Pre-compute raw HR for every beat
    val rawHr: List<Int> = combined.map { (rr, _) ->
        if (rr.intervalMs > 0) (60_000.0 / rr.intervalMs).roundToInt().coerceIn(20, 300)
        else 0
    }

    // Spike-suppressed HR: replace single-beat outliers with neighbourhood median
    val smoothHr: List<Int> = rawHr.mapIndexed { idx, hr ->
        val lo = (idx - spikeNeighbourhood).coerceAtLeast(0)
        val hi = (idx + spikeNeighbourhood + 1).coerceAtMost(rawHr.size)
        val neighbours = rawHr.subList(lo, hi).filter { it > 0 }.sorted()
        val median = if (neighbours.isNotEmpty()) neighbours[neighbours.size / 2] else hr
        if (hr - median > spikeThresholdBpm) median else hr.coerceIn(20, 250)
    }

    val result = mutableListOf<MorningReadinessTelemetryEntity>()

    // Sliding-window RMSSD over the combined stream
    combined.forEachIndexed { idx, (rr, phase) ->
        // Build window of up to `windowSize` preceding intervals (including current)
        val windowStart = (idx - windowSize + 1).coerceAtLeast(0)
        val window = combined.subList(windowStart, idx + 1).map { it.first.intervalMs }
        val rollingRmssd = if (window.size >= 2) {
            val diffs = (1 until window.size).map { window[it] - window[it - 1] }
            sqrt(diffs.sumOf { it * it } / diffs.size)
        } else 0.0

        result += MorningReadinessTelemetryEntity(
            readingId      = readingId,
            timestampMs    = rr.timestampMs,
            hrBpm          = smoothHr[idx],
            rollingRmssdMs = rollingRmssd,
            phase          = phase
        )
    }
    return result
}
