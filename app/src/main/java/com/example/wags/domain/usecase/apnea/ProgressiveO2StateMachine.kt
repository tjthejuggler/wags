package com.example.wags.domain.usecase.apnea

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ── Phase enum ──────────────────────────────────────────────────────────────

enum class ProgressiveO2Phase {
    IDLE, HOLD, BREATHING, COMPLETE
}

// ── State data class ────────────────────────────────────────────────────────

data class ProgressiveO2State(
    val phase: ProgressiveO2Phase = ProgressiveO2Phase.IDLE,
    /** 1-indexed round number (0 while IDLE). */
    val currentRound: Int = 0,
    /** Countdown timer for the current phase (ms). */
    val timerMs: Long = 0L,
    /** Target hold duration for the current round (ms). */
    val holdDurationMs: Long = 0L,
    /** User-configured breath period (ms). */
    val breathDurationMs: Long = 0L,
    /** Cumulative time spent holding across all rounds (ms). */
    val totalHoldTimeMs: Long = 0L,
    /** Completed round data for serialisation into tableParamsJson. */
    val roundResults: List<ProgressiveO2RoundResult> = emptyList(),
    /** Epoch ms when first contraction was logged (null if not yet). */
    val firstContractionMs: Long? = null
)

// ── Per-round result ────────────────────────────────────────────────────────

data class ProgressiveO2RoundResult(
    val roundNumber: Int,
    /** What the hold target was (roundNumber × 15 000 ms). */
    val targetHoldMs: Long,
    /** How long the user actually held (equals target if completed, less if stopped mid-hold). */
    val actualHoldMs: Long,
    /** True if the user held for the full target duration. */
    val completed: Boolean
)

// ── State machine ───────────────────────────────────────────────────────────

@Singleton
class ProgressiveO2StateMachine @Inject constructor() {

    private val _state = MutableStateFlow(ProgressiveO2State())
    val state: StateFlow<ProgressiveO2State> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var scope: CoroutineScope? = null

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Begins the endless Progressive O₂ drill.
     * Round 1 starts immediately in the HOLD phase with a 15 s countdown.
     */
    fun start(breathPeriodMs: Long, scope: CoroutineScope) {
        this.scope = scope
        _state.value = ProgressiveO2State(breathDurationMs = breathPeriodMs)
        startHoldRound(1)
    }

    /**
     * Called by the user to end the drill.
     *
     * If currently in HOLD, records a partial round (actualHoldMs = elapsed so far).
     * If currently in BREATHING, the last completed round is already recorded.
     * Transitions to COMPLETE.
     */
    fun stop() {
        timerJob?.cancel()
        val current = _state.value

        when (current.phase) {
            ProgressiveO2Phase.HOLD -> {
                val elapsed = current.holdDurationMs - current.timerMs
                val partialResult = ProgressiveO2RoundResult(
                    roundNumber = current.currentRound,
                    targetHoldMs = current.holdDurationMs,
                    actualHoldMs = elapsed,
                    completed = false
                )
                _state.value = current.copy(
                    phase = ProgressiveO2Phase.COMPLETE,
                    totalHoldTimeMs = current.totalHoldTimeMs + elapsed,
                    roundResults = current.roundResults + partialResult
                )
            }
            ProgressiveO2Phase.BREATHING -> {
                _state.value = current.copy(phase = ProgressiveO2Phase.COMPLETE)
            }
            else -> {
                _state.value = current.copy(phase = ProgressiveO2Phase.COMPLETE)
            }
        }
    }

    /**
     * Called when the user taps "First Contraction" during a HOLD phase.
     * Records the wall-clock timestamp (only once per session).
     */
    fun signalFirstContraction() {
        val current = _state.value
        if (current.firstContractionMs != null) return // already logged
        if (current.phase != ProgressiveO2Phase.HOLD) return
        _state.value = current.copy(firstContractionMs = System.currentTimeMillis())
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun startHoldRound(round: Int) {
        val holdMs = round * 15_000L
        _state.value = _state.value.copy(
            phase = ProgressiveO2Phase.HOLD,
            currentRound = round,
            holdDurationMs = holdMs,
            timerMs = holdMs
        )
        runCountdown(holdMs) { onHoldComplete(round, holdMs) }
    }

    private fun onHoldComplete(round: Int, holdMs: Long) {
        val result = ProgressiveO2RoundResult(
            roundNumber = round,
            targetHoldMs = holdMs,
            actualHoldMs = holdMs,
            completed = true
        )
        val current = _state.value
        val breathMs = current.breathDurationMs
        _state.value = current.copy(
            phase = ProgressiveO2Phase.BREATHING,
            timerMs = breathMs,
            totalHoldTimeMs = current.totalHoldTimeMs + holdMs,
            roundResults = current.roundResults + result
        )
        runCountdown(breathMs) { startHoldRound(round + 1) }
    }

    private fun runCountdown(durationMs: Long, onComplete: () -> Unit) {
        timerJob?.cancel()
        timerJob = scope?.launch {
            var remaining = durationMs
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1000L
                _state.value = _state.value.copy(timerMs = remaining.coerceAtLeast(0L))
            }
            onComplete()
        }
    }
}
