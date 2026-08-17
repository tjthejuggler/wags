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
 * Emits remaining seconds and fires the warning callback once per second so
 * consumers (voice announcements, vibration warnings) can filter their own
 * trigger points — including user-configurable warning windows.
 */
class ApneaCountdownTimer @Inject constructor() {

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private var timerJob: Job? = null

    /**
     * Start countdown from [durationMs].
     * [onWarning] fires every second with the remaining seconds
     * (consumers filter their own trigger points).
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
                onWarning(remaining)
            }
            onComplete()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        _remainingSeconds.value = 0L
    }
}
