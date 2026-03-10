package com.example.wags.domain.usecase.readiness

import com.example.wags.domain.model.RrInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Orchestrates the full morning readiness session across 8 states.
 * Delegates timing to [MorningReadinessTimer] and state to [MorningReadinessStateHandler].
 *
 * The ViewModel feeds RR intervals via [addRrInterval] and triggers audio/haptic
 * via the [onStandPromptReady] callback. No Android framework imports here.
 */
class MorningReadinessFsm @Inject constructor(
    private val timer: MorningReadinessTimer,
    private val stateHandler: MorningReadinessStateHandler
) {
    // Expose state and timer
    val state: StateFlow<MorningReadinessState> = stateHandler.state
    val remainingSeconds: StateFlow<Int> = timer.remainingSeconds
    val errorMessage: StateFlow<String?> = stateHandler.errorMessage

    // Data buffers — ViewModel reads these after CALCULATING
    private val _supineBuffer = mutableListOf<RrInterval>()
    private val _standingBuffer = mutableListOf<RrInterval>()
    val supineBuffer: List<RrInterval> get() = _supineBuffer.toList()
    val standingBuffer: List<RrInterval> get() = _standingBuffer.toList()

    // Peak stand HR tracking
    private var _peakStandHr: Int = 0
    val peakStandHr: Int get() = _peakStandHr

    // Callbacks set by ViewModel
    var onStandPromptReady: (() -> Unit)? = null
    var onQuestionnaireRequired: (() -> Unit)? = null
    var onReadyToCalculate: (() -> Unit)? = null

    companion object {
        const val SUPINE_HRV_SECONDS = 120   // 120s supine measurement
        const val STAND_CAPTURE_SECONDS = 60  // 60s orthostatic capture (peak HR + 30:15)
        const val STAND_HRV_SECONDS = 60      // 60s standing HRV → 120s total on feet
        const val INIT_DURATION_SECONDS = 60  // 60s prep
        private const val STAND_PROMPT_DURATION_SECONDS = 3
    }

    /**
     * Start the FSM. Transitions IDLE → INIT → SUPINE_HRV automatically.
     * Total timed duration: 60s prep + 120s supine + 3s prompt + 120s standing = ~303s.
     * The ViewModel must call this after confirming BLE is connected.
     */
    fun start(scope: CoroutineScope) {
        _supineBuffer.clear()
        _standingBuffer.clear()
        _peakStandHr = 0

        stateHandler.transitionTo(MorningReadinessState.INIT)

        // INIT: 60s prep, then go directly to supine HRV recording
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
        // Auto-advance to STAND_CAPTURE after 3s (gives user time to read the prompt)
        timer.start(scope, durationSeconds = STAND_PROMPT_DURATION_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.STAND_PROMPT) {
                enterStandCapture(scope)
            }
        }
    }

    private fun enterStandCapture(scope: CoroutineScope) {
        stateHandler.transitionTo(MorningReadinessState.STAND_CAPTURE)
        timer.start(scope, durationSeconds = STAND_CAPTURE_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.STAND_CAPTURE) {
                enterStandHrv(scope)
            }
        }
    }

    private fun enterStandHrv(scope: CoroutineScope) {
        stateHandler.transitionTo(MorningReadinessState.STAND_HRV)
        timer.start(scope, durationSeconds = STAND_HRV_SECONDS) {
            if (stateHandler.state.value == MorningReadinessState.STAND_HRV) {
                enterQuestionnaire()
            }
        }
    }

    private fun enterQuestionnaire() {
        stateHandler.transitionTo(MorningReadinessState.QUESTIONNAIRE)
        onQuestionnaireRequired?.invoke()
        // ViewModel calls submitHooper() to advance from here
    }

    /**
     * Called by ViewModel when user submits Hooper questionnaire answers.
     * Advances to CALCULATING state.
     */
    fun submitHooper() {
        if (stateHandler.state.value == MorningReadinessState.QUESTIONNAIRE) {
            stateHandler.transitionTo(MorningReadinessState.CALCULATING)
            onReadyToCalculate?.invoke()
        }
    }

    /**
     * Called by ViewModel to feed RR intervals into the appropriate buffer.
     * Only records during SUPINE_HRV, STAND_CAPTURE, and STAND_HRV states.
     * Updates peak stand HR during STAND_CAPTURE.
     */
    fun addRrInterval(rr: RrInterval) {
        when (stateHandler.state.value) {
            MorningReadinessState.SUPINE_HRV -> _supineBuffer.add(rr)
            MorningReadinessState.STAND_CAPTURE -> {
                _standingBuffer.add(rr)
                // Track peak HR (minimum RR = maximum HR)
                val hrBpm = if (rr.intervalMs > 0) (60_000.0 / rr.intervalMs).toInt() else 0
                if (hrBpm > _peakStandHr) _peakStandHr = hrBpm
            }
            MorningReadinessState.STAND_HRV -> _standingBuffer.add(rr)
            else -> { /* Discard data in other states */ }
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
        _supineBuffer.clear()
        _standingBuffer.clear()
        _peakStandHr = 0
        stateHandler.reset()
    }
}
