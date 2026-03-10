package com.example.wags.domain.usecase.readiness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Countdown timer for morning readiness state transitions.
 * Counts down 1 second at a time and fires [onComplete] when reaching 0.
 */
class MorningReadinessTimer @Inject constructor() {

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private var timerJob: Job? = null

    fun start(scope: CoroutineScope, durationSeconds: Int, onComplete: () -> Unit) {
        timerJob?.cancel()
        _remainingSeconds.value = durationSeconds
        timerJob = scope.launch {
            var remaining = durationSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _remainingSeconds.value = remaining
            }
            onComplete()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
    }

    fun reset(durationSeconds: Int = 0) {
        cancel()
        _remainingSeconds.value = durationSeconds
    }
}
