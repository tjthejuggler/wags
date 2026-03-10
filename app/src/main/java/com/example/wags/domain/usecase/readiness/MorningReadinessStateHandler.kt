package com.example.wags.domain.usecase.readiness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Manages morning readiness session state transitions.
 * States: IDLE → INIT → SUPINE_REST → SUPINE_HRV → STAND_PROMPT →
 *         STAND_CAPTURE → STAND_HRV → QUESTIONNAIRE → CALCULATING → COMPLETE
 * Can transition to ERROR from any state.
 */
class MorningReadinessStateHandler @Inject constructor() {

    private val _state = MutableStateFlow(MorningReadinessState.IDLE)
    val state: StateFlow<MorningReadinessState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun transitionTo(newState: MorningReadinessState) {
        _state.value = newState
    }

    fun setError(message: String) {
        _errorMessage.value = message
        _state.value = MorningReadinessState.ERROR
    }

    fun reset() {
        _state.value = MorningReadinessState.IDLE
        _errorMessage.value = null
    }

    /** Returns true if the transition from the current state to [newState] is valid. */
    fun canTransitionTo(newState: MorningReadinessState): Boolean {
        val current = _state.value
        return when (newState) {
            MorningReadinessState.IDLE -> true
            MorningReadinessState.INIT -> current == MorningReadinessState.IDLE
            MorningReadinessState.SUPINE_REST -> current == MorningReadinessState.INIT
            MorningReadinessState.SUPINE_HRV -> current == MorningReadinessState.SUPINE_REST
            MorningReadinessState.STAND_PROMPT -> current == MorningReadinessState.SUPINE_HRV
            MorningReadinessState.STAND_CAPTURE -> current == MorningReadinessState.STAND_PROMPT
            MorningReadinessState.STAND_HRV -> current == MorningReadinessState.STAND_CAPTURE
            MorningReadinessState.QUESTIONNAIRE -> current == MorningReadinessState.STAND_HRV
            MorningReadinessState.CALCULATING -> current == MorningReadinessState.QUESTIONNAIRE
            MorningReadinessState.COMPLETE -> current == MorningReadinessState.CALCULATING
            MorningReadinessState.ERROR -> true // Can error from any state
        }
    }
}
