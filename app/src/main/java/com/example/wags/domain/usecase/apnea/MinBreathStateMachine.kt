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

enum class MinBreathPhase {
    IDLE, HOLD, BREATHING, COMPLETE
}

// ── Per-hold result ─────────────────────────────────────────────────────────

data class MinBreathHoldResult(
    val holdNumber: Int,
    val holdDurationMs: Long,
    val firstContractionMs: Long? = null
)

// ── Overall state ───────────────────────────────────────────────────────────

data class MinBreathState(
    val phase: MinBreathPhase = MinBreathPhase.IDLE,
    val totalDurationMs: Long = 0L,
    val sessionRemainingMs: Long = 0L,
    val currentPhaseElapsedMs: Long = 0L,
    val currentHoldNumber: Int = 0,
    val totalHoldTimeMs: Long = 0L,
    val totalBreathTimeMs: Long = 0L,
    val holdResults: List<MinBreathHoldResult> = emptyList(),
    val currentHoldContractionMs: Long? = null
)

// ── State machine ───────────────────────────────────────────────────────────

@Singleton
class MinBreathStateMachine @Inject constructor() {

    private val _state = MutableStateFlow(MinBreathState())
    val state: StateFlow<MinBreathState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var scope: CoroutineScope? = null
    private var phaseStartMs: Long = 0L
    private var sessionStartMs: Long = 0L
    private var sessionTotalMs: Long = 0L

    // ── Public API ──────────────────────────────────────────────────────────

    fun start(totalDurationMs: Long, scope: CoroutineScope) {
        this.scope = scope
        phaseStartMs = System.currentTimeMillis()
        sessionStartMs = phaseStartMs
        sessionTotalMs = totalDurationMs
        _state.value = MinBreathState(
            phase = MinBreathPhase.HOLD,
            totalDurationMs = totalDurationMs,
            sessionRemainingMs = totalDurationMs,
            currentHoldNumber = 1
        )
        startTickLoop()
    }

    fun markContraction() {
        val current = _state.value
        if (current.phase != MinBreathPhase.HOLD || current.currentHoldContractionMs != null) return
        _state.value = current.copy(currentHoldContractionMs = current.currentPhaseElapsedMs)
    }

    fun switchToBreathing() {
        val current = _state.value
        if (current.phase != MinBreathPhase.HOLD) return

        val holdDuration = (System.currentTimeMillis() - phaseStartMs).coerceAtLeast(0L)
        val result = MinBreathHoldResult(
            holdNumber = current.currentHoldNumber,
            holdDurationMs = holdDuration,
            firstContractionMs = current.currentHoldContractionMs
        )
        phaseStartMs = System.currentTimeMillis()
        _state.value = current.copy(
            phase = MinBreathPhase.BREATHING,
            currentPhaseElapsedMs = 0L,
            totalHoldTimeMs = current.totalHoldTimeMs + holdDuration,
            holdResults = current.holdResults + result,
            currentHoldContractionMs = null
        )
    }

    fun switchToHolding() {
        val current = _state.value
        if (current.phase != MinBreathPhase.BREATHING) return

        val breathDuration = (System.currentTimeMillis() - phaseStartMs).coerceAtLeast(0L)
        phaseStartMs = System.currentTimeMillis()
        _state.value = current.copy(
            phase = MinBreathPhase.HOLD,
            currentPhaseElapsedMs = 0L,
            totalBreathTimeMs = current.totalBreathTimeMs + breathDuration,
            currentHoldNumber = current.currentHoldNumber + 1
        )
    }

    fun stop() {
        timerJob?.cancel()
        completeSession()
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun completeSession() {
        val current = _state.value
        // Wall-clock elapsed since the current phase began (accurate even when
        // the completing tick/stop happens between tick-loop updates).
        val elapsed = if (current.phase == MinBreathPhase.HOLD ||
            current.phase == MinBreathPhase.BREATHING
        ) (System.currentTimeMillis() - phaseStartMs).coerceAtLeast(0L) else 0L

        _state.value = when (current.phase) {
            MinBreathPhase.HOLD -> {
                val result = MinBreathHoldResult(
                    holdNumber = current.currentHoldNumber,
                    holdDurationMs = elapsed,
                    firstContractionMs = current.currentHoldContractionMs
                )
                current.copy(
                    phase = MinBreathPhase.COMPLETE,
                    totalHoldTimeMs = current.totalHoldTimeMs + elapsed,
                    holdResults = current.holdResults + result,
                    currentHoldContractionMs = null
                )
            }
            MinBreathPhase.BREATHING -> {
                current.copy(
                    phase = MinBreathPhase.COMPLETE,
                    totalBreathTimeMs = current.totalBreathTimeMs + elapsed
                )
            }
            else -> current.copy(phase = MinBreathPhase.COMPLETE)
        }
    }

    private fun startTickLoop() {
        timerJob?.cancel()
        timerJob = scope?.launch {
            while (true) {
                delay(100L)
                val now = System.currentTimeMillis()

                val current = _state.value
                if (current.phase == MinBreathPhase.COMPLETE ||
                    current.phase == MinBreathPhase.IDLE
                ) break

                // Wall-clock derived values: computing phase elapsed and session
                // remaining from phaseStartMs/sessionStartMs (instead of
                // accumulating per-tick deltas) prevents double-crediting time
                // to a phase when the user switches hold<->breath between ticks,
                // which previously inflated totals slightly above 100%.
                val newRemaining = (sessionStartMs + sessionTotalMs - now).coerceAtLeast(0L)
                val newElapsed = (now - phaseStartMs).coerceAtLeast(0L)

                if (newRemaining <= 0L) {
                    _state.value = current.copy(
                        sessionRemainingMs = 0L,
                        currentPhaseElapsedMs = newElapsed
                    )
                    completeSession()
                    break
                }

                _state.value = current.copy(
                    sessionRemainingMs = newRemaining,
                    currentPhaseElapsedMs = newElapsed
                )
            }
        }
    }
}
