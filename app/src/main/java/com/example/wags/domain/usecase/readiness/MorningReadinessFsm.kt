package com.example.wags.domain.usecase.readiness

import com.example.wags.domain.model.RrInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Orchestrates the full morning readiness session across 6 states.
 * Delegates timing to [MorningReadinessTimer] and state to [MorningReadinessStateHandler].
 *
 * Flow: IDLE → INIT → SUPINE_HRV → STAND_PROMPT → STANDING → QUESTIONNAIRE → CALCULATING → COMPLETE
 *
 * Raw data is collected in supine and standing buffers. All metric calculations
 * (HRV, orthostatic, respiratory rate, etc.) happen after data collection in CALCULATING.
 */
class MorningReadinessFsm @Inject constructor(
    private val timer: MorningReadinessTimer,
    private val stateHandler: MorningReadinessStateHandler
) {
    val state: StateFlow<MorningReadinessState> = stateHandler.state
    val remainingSeconds: StateFlow<Int> = timer.remainingSeconds
    val errorMessage: StateFlow<String?> = stateHandler.errorMessage

    // Data buffers — ViewModel reads these after CALCULATING.
    // Access is synchronized because addRrInterval() is called from the RR polling
    // coroutine while supineBuffer/standingBuffer snapshots are taken in launchCalculation().
    private val _supineBuffer = mutableListOf<RrInterval>()
    private val _standingBuffer = mutableListOf<RrInterval>()
    val supineBuffer: List<RrInterval> get() = synchronized(this) { _supineBuffer.toList() }
    val standingBuffer: List<RrInterval> get() = synchronized(this) { _standingBuffer.toList() }

    // Peak stand HR tracking (minimum RR = maximum HR during standing)
    private var _peakStandHr: Int = 0
    val peakStandHr: Int get() = _peakStandHr

    /** Wall-clock ms when the STANDING phase began (stand cue was given). Null until that transition. */
    private var _standTimestampMs: Long? = null
    val standTimestampMs: Long? get() = _standTimestampMs

    // Callbacks set by ViewModel
    var onStandPromptReady: (() -> Unit)? = null
    var onQuestionnaireRequired: (() -> Unit)? = null
    var onReadyToCalculate: (() -> Unit)? = null

    companion object {
        const val SUPINE_HRV_SECONDS = 120    // 120s supine raw data collection
        const val STANDING_SECONDS = 120       // 120s standing raw data collection (peak HR + all standing data)
        const val INIT_DURATION_SECONDS = 30   // 30s prep
        private const val STAND_PROMPT_DURATION_SECONDS = 3
    }

    /**
     * Start the FSM. Transitions IDLE → INIT → SUPINE_HRV automatically.
     * Total timed duration: 30s prep + 120s supine + 3s prompt + 120s standing = ~273s.
     */
    fun start(scope: CoroutineScope) {
        synchronized(this) {
            _supineBuffer.clear()
            _standingBuffer.clear()
            _peakStandHr = 0
        }
        _standTimestampMs = null

        stateHandler.transitionTo(MorningReadinessState.INIT)

        timer.start(scope, durationSeconds = INIT_DURATION_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.INIT) {
                enterSupineHrv(scope)
            }
        }
    }

    private fun enterSupineHrv(scope: CoroutineScope) {
        stateHandler.transitionTo(MorningReadinessState.SUPINE_HRV)
        timer.start(scope, durationSeconds = SUPINE_HRV_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.SUPINE_HRV) {
                enterStandPrompt(scope)
            }
        }
    }

    private fun enterStandPrompt(scope: CoroutineScope) {
        stateHandler.transitionTo(MorningReadinessState.STAND_PROMPT)
        onStandPromptReady?.invoke()
        timer.start(scope, durationSeconds = STAND_PROMPT_DURATION_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.STAND_PROMPT) {
                enterStanding(scope)
            }
        }
    }

    private fun enterStanding(scope: CoroutineScope) {
        _standTimestampMs = System.currentTimeMillis()
        stateHandler.transitionTo(MorningReadinessState.STANDING)
        timer.start(scope, durationSeconds = STANDING_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.STANDING) {
                enterQuestionnaire()
            }
        }
    }

    /**
     * Called by the ViewModel's StandDetector once the precise stand moment is
     * confirmed from accelerometer data. Overwrites the coarse FSM timestamp.
     * Only accepted while in STANDING state (or STAND_PROMPT, in case detection
     * fires very quickly).
     */
    fun updateStandTimestamp(timestampMs: Long) {
        val s = stateHandler.state.value
        if (s == MorningReadinessState.STANDING || s == MorningReadinessState.STAND_PROMPT) {
            _standTimestampMs = timestampMs
        }
    }

    private fun enterQuestionnaire() {
        stateHandler.transitionTo(MorningReadinessState.QUESTIONNAIRE)
        onQuestionnaireRequired?.invoke()
        // ViewModel calls submitHooper() to advance from here
    }

    /**
     * Called by ViewModel when user submits Hooper questionnaire answers.
     * Advances to CALCULATING state where all metrics are derived from raw buffers.
     */
    fun submitHooper() {
        if (stateHandler.state.value == MorningReadinessState.QUESTIONNAIRE) {
            stateHandler.transitionTo(MorningReadinessState.CALCULATING)
            onReadyToCalculate?.invoke()
        }
    }

    /**
     * Called when the user taps "No Standing" during STAND_PROMPT.
     * Cancels the standing phase entirely — the session proceeds directly to
     * QUESTIONNAIRE with an empty standing buffer. The report is still saved,
     * just without any orthostatic data.
     */
    fun skipStanding() {
        val s = stateHandler.state.value
        if (s == MorningReadinessState.STAND_PROMPT || s == MorningReadinessState.STANDING) {
            timer.cancel()
            enterQuestionnaire()
        }
    }

    /**
     * Called by ViewModel to feed RR intervals into the appropriate buffer.
     * Records during SUPINE_HRV and STANDING states.
     * Tracks peak stand HR during STANDING.
     */
    fun addRrInterval(rr: RrInterval) {
        synchronized(this) {
            when (stateHandler.state.value) {
                MorningReadinessState.SUPINE_HRV -> _supineBuffer.add(rr)
                MorningReadinessState.STANDING -> {
                    _standingBuffer.add(rr)
                    // Track peak HR (minimum RR = maximum HR)
                    val hrBpm = if (rr.intervalMs > 0) (60_000.0 / rr.intervalMs).toInt() else 0
                    if (hrBpm > _peakStandHr) _peakStandHr = hrBpm
                }
                else -> { /* Discard data in other states */ }
            }
        }
    }

    /**
     * Called by ViewModel when calculations are complete.
     */
    fun markComplete() {
        if (stateHandler.state.value == MorningReadinessState.CALCULATING) {
            stateHandler.transitionTo(MorningReadinessState.COMPLETE)
        }
    }

    /**
     * Signal an error from any state.
     */
    fun signalError(message: String) {
        timer.cancel()
        stateHandler.setError(message)
    }

    /**
     * Reset the FSM to IDLE.
     */
    fun reset() {
        timer.cancel()
        synchronized(this) {
            _supineBuffer.clear()
            _standingBuffer.clear()
            _peakStandHr = 0
        }
        _standTimestampMs = null
        stateHandler.reset()
    }
}
