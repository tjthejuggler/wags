package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.ApneaTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Orchestrates the full apnea table session: APNEA → VENTILATION → APNEA → … → COMPLETE.
 *
 * No warm-up or recovery phases — just Hold and Breath, alternating.
 * Delegates timing to [ApneaCountdownTimer] and state to [ApneaStateTransitionHandler].
 */
class ApneaStateMachine @Inject constructor(
    private val timer: ApneaCountdownTimer,
    private val stateHandler: ApneaStateTransitionHandler
) {
    val state: StateFlow<ApneaState> = stateHandler.state
    val currentRound: StateFlow<Int> = stateHandler.currentRound
    val remainingSeconds: StateFlow<Long> = timer.remainingSeconds

    private var table: ApneaTable? = null
    private var warningCallback: ((Long) -> Unit)? = null
    private var stateChangeCallback: ((ApneaState) -> Unit)? = null

    fun load(apneaTable: ApneaTable) {
        table = apneaTable
        stateHandler.initialize(apneaTable.steps.size)
    }

    fun setCallbacks(
        onWarning: (Long) -> Unit,
        onStateChange: (ApneaState) -> Unit
    ) {
        warningCallback = onWarning
        stateChangeCallback = onStateChange
    }

    /** Start the session — begins immediately with the first APNEA (hold) phase. */
    fun start(scope: CoroutineScope) {
        val t = table ?: return
        stateHandler.startSession()
        stateChangeCallback?.invoke(ApneaState.APNEA)
        startApnea(scope, t, 0)
    }

    private fun startApnea(scope: CoroutineScope, table: ApneaTable, stepIndex: Int) {
        val step = table.steps[stepIndex]
        timer.start(
            durationMs = step.apneaDurationMs,
            scope = scope,
            onWarning = { warningCallback?.invoke(it) },
            onComplete = {
                stateHandler.onApneaComplete()
                val nextState = stateHandler.state.value
                stateChangeCallback?.invoke(nextState)
                if (nextState == ApneaState.VENTILATION) {
                    startVentilation(scope, table, stepIndex)
                }
                // If COMPLETE, the callback above already fired
            }
        )
    }

    private fun startVentilation(scope: CoroutineScope, table: ApneaTable, stepIndex: Int) {
        val step = table.steps[stepIndex]
        timer.start(
            durationMs = step.ventilationDurationMs,
            scope = scope,
            onWarning = { warningCallback?.invoke(it) },
            onComplete = {
                stateHandler.onVentilationComplete()
                stateChangeCallback?.invoke(ApneaState.APNEA)
                startApnea(scope, table, stepIndex + 1)
            }
        )
    }

    fun stop() {
        timer.cancel()
        stateHandler.reset()
    }
}
