package com.example.wags.domain.usecase.apnea

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Countdown timer for apnea state transitions.
 * Emits remaining seconds and fires callbacks at 10s, 5s, 3s, 2s, 1s before transition.
 */
class ApneaCountdownTimer @Inject constructor() {

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private var timerJob: Job? = null

    companion object {
        private val WARNING_POINTS = setOf(10L, 5L, 3L, 2L, 1L)
    }

    /**
     * Start countdown from [durationMs].
     * [onWarning] fires at 10s, 5s, 3s, 2s, 1s remaining.
     * [onComplete] fires when countdown reaches 0.
     */
    fun start(
        durationMs: Long,
        scope: CoroutineScope,
        onWarning: (remainingSeconds: Long) -> Unit,
        onComplete: () -> Unit
    ) {
        timerJob?.cancel()
        timerJob = scope.launch {
            var remaining = durationMs / 1000L
            _remainingSeconds.value = remaining
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _remainingSeconds.value = remaining
                if (remaining in WARNING_POINTS) {
                    onWarning(remaining)
                }
            }
            onComplete()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        _remainingSeconds.value = 0L
    }
}
