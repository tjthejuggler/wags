package com.example.wags.domain.usecase.apnea

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class ApneaState { IDLE, VENTILATION, APNEA, RECOVERY, COMPLETE }

/**
 * Manages apnea session state transitions.
 * States: IDLE → VENTILATION → APNEA → RECOVERY → COMPLETE
 */
class ApneaStateTransitionHandler @Inject constructor() {

    private val _state = MutableStateFlow(ApneaState.IDLE)
    val state: StateFlow<ApneaState> = _state.asStateFlow()

    private val _currentRound = MutableStateFlow(0)
    val currentRound: StateFlow<Int> = _currentRound.asStateFlow()

    private var totalRounds = 0

    fun initialize(rounds: Int) {
        totalRounds = rounds
        _currentRound.value = 0
        _state.value = ApneaState.IDLE
    }

    fun startSession() {
        _currentRound.value = 1
        _state.value = ApneaState.VENTILATION
    }

    fun onVentilationComplete() {
        if (_state.value == ApneaState.VENTILATION) {
            _state.value = ApneaState.APNEA
        }
    }

    fun onApneaComplete() {
        if (_state.value == ApneaState.APNEA) {
            _state.value = ApneaState.RECOVERY
        }
    }

    fun onRecoveryComplete() {
        if (_state.value == ApneaState.RECOVERY) {
            val nextRound = _currentRound.value + 1
            if (nextRound > totalRounds) {
                _state.value = ApneaState.COMPLETE
            } else {
                _currentRound.value = nextRound
                _state.value = ApneaState.VENTILATION
            }
        }
    }

    fun reset() {
        _state.value = ApneaState.IDLE
        _currentRound.value = 0
        totalRounds = 0
    }
}
