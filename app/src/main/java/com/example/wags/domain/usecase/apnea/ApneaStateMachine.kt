package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.ApneaTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

private const val RECOVERY_DURATION_MS = 30_000L

/**
 * Orchestrates the full apnea session: table steps → countdown → state transitions.
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

    fun start(scope: CoroutineScope) {
        val t = table ?: return
        stateHandler.startSession()
        stateChangeCallback?.invoke(ApneaState.VENTILATION)
        startVentilation(scope, t, 0)
    }

    private fun startVentilation(scope: CoroutineScope, table: ApneaTable, stepIndex: Int) {
        if (stepIndex >= table.steps.size) {
            stateHandler.onRecoveryComplete()
            stateChangeCallback?.invoke(ApneaState.COMPLETE)
            return
        }
        val step = table.steps[stepIndex]
        timer.start(
            durationMs = step.ventilationDurationMs,
            scope = scope,
            onWarning = { warningCallback?.invoke(it) },
            onComplete = {
                stateHandler.onVentilationComplete()
                stateChangeCallback?.invoke(ApneaState.APNEA)
                startApnea(scope, table, stepIndex)
            }
        )
    }

    private fun startApnea(scope: CoroutineScope, table: ApneaTable, stepIndex: Int) {
        val step = table.steps[stepIndex]
        timer.start(
            durationMs = step.apneaDurationMs,
            scope = scope,
            onWarning = { warningCallback?.invoke(it) },
            onComplete = {
                stateHandler.onApneaComplete()
                stateChangeCallback?.invoke(ApneaState.RECOVERY)
                startRecovery(scope, table, stepIndex)
            }
        )
    }

    private fun startRecovery(scope: CoroutineScope, table: ApneaTable, stepIndex: Int) {
        timer.start(
            durationMs = RECOVERY_DURATION_MS,
            scope = scope,
            onWarning = { warningCallback?.invoke(it) },
            onComplete = {
                stateHandler.onRecoveryComplete()
                val nextStep = stepIndex + 1
                if (nextStep < table.steps.size) {
                    stateChangeCallback?.invoke(ApneaState.VENTILATION)
                    startVentilation(scope, table, nextStep)
                } else {
                    stateChangeCallback?.invoke(ApneaState.COMPLETE)
                }
            }
        )
    }

    fun stop() {
        timer.cancel()
        stateHandler.reset()
    }
}
