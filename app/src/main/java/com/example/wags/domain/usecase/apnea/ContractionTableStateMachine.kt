package com.example.wags.domain.usecase.apnea

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ── Phase enum ──────────────────────────────────────────────────────────────

/**
 * Lifecycle of a contraction-table round:
 *
 * BREATHE → CRUISE → (STRUGGLE, Contraction-Count mode only) → BREATHE → … → COMPLETE
 */
enum class ContractionTablePhase {
    IDLE, BREATHE, CRUISE, STRUGGLE, COMPLETE
}

/** The two contraction-driven drill modes. */
enum class ContractionTableMode {
    /** Hold ends at the first diaphragmatic contraction (trains CO₂ tolerance / interoception). */
    TILL_CONTRACTION,
    /** After the first contraction, hold until N contractions have been logged (trains struggle-phase endurance). */
    CONTRACTION_COUNT
}

// ── Per-round result ────────────────────────────────────────────────────────

/**
 * One completed (or partially completed) round of a contraction table.
 *
 * Terminology:
 *  - cruiseMs   = "easy phase" duration: hold start → first contraction (null if the
 *                 user bailed out before logging any contraction).
 *  - struggleMs = time endured after the first contraction (0 in TILL_CONTRACTION mode).
 *  - totalHoldMs = cruiseMs + struggleMs.
 */
data class ContractionTableRoundResult(
    val roundNumber: Int,
    /** Rest that preceded this hold (ms) — varies when a decreasing-rest schedule is configured. */
    val restBeforeMs: Long,
    /** Easy-phase duration (ms), null when the user ended the hold before any contraction. */
    val cruiseMs: Long?,
    /** Struggle-phase duration (ms). Always 0 in TILL_CONTRACTION mode. */
    val struggleMs: Long,
    /** Total hold duration (ms). */
    val totalHoldMs: Long,
    /** Contractions logged during the struggle phase (1 in completed TILL rounds, 0 if bailed pre-contraction). */
    val contractionsLogged: Int,
    /** True when the round reached its mode's natural completion. */
    val completed: Boolean,
    /** True when the user tapped "End Hold" mid-hold (logged as a partial round). */
    val endedEarly: Boolean
)

// ── State data class ────────────────────────────────────────────────────────

data class ContractionTableState(
    val phase: ContractionTablePhase = ContractionTablePhase.IDLE,
    val mode: ContractionTableMode = ContractionTableMode.TILL_CONTRACTION,
    /** 1-indexed round number (0 while IDLE). */
    val currentRound: Int = 0,
    val totalRounds: Int = 0,
    /**
     * Phase timer (ms):
     *  - BREATHE  → remaining rest countdown
     *  - CRUISE   → elapsed cruise time (counts up)
     *  - STRUGGLE → elapsed total hold time (counts up, cruise included)
     */
    val timerMs: Long = 0L,
    val isCountingUp: Boolean = false,
    /** Rest duration configured for the current round (ms). */
    val restDurationMs: Long = 0L,
    /** Contraction target for the current hold (CONTRACTION_COUNT mode; 0 otherwise). */
    val contractionTarget: Int = 0,
    /** Contractions logged so far in the current hold. */
    val contractionsInHold: Int = 0,
    /** Frozen cruise duration once the struggle phase begins (ms). */
    val cruiseElapsedMs: Long = 0L,
    /** Completed round data for serialisation into tableParamsJson. */
    val roundResults: List<ContractionTableRoundResult> = emptyList(),
    /** Cumulative hold time across all completed rounds (ms). */
    val totalHoldTimeMs: Long = 0L,
    /** Real-time total hold time including the in-progress hold (ms). */
    val realTimeTotalHoldTimeMs: Long = 0L,
    /** Wall-clock epoch ms when the current hold started. */
    val holdStartEpochMs: Long? = null,
    /** Wall-clock epoch ms when the first contraction was logged in the current hold. */
    val firstContractionEpochMs: Long? = null
) {
    /** Best (longest) cruise achieved in this session, null when none logged. */
    val bestCruiseMs: Long?
        get() = roundResults.mapNotNull { it.cruiseMs }.maxOrNull()

    /** Longest total hold achieved in this session (0 when none). */
    val longestHoldMs: Long
        get() = roundResults.maxOfOrNull { it.totalHoldMs } ?: 0L
}

// ── State machine ───────────────────────────────────────────────────────────

/**
 * Contraction-driven table state machine (replaces the old inline "Wonka" logic
 * that used to run inside ApneaScreen).
 *
 * Design notes:
 *  - All timing is computed from wall-clock epoch anchors on a 100 ms tick, so the
 *    timers never drift under coroutine scheduling delays (unlike the old
 *    decrement-per-delay implementation).
 *  - Per-round results are accumulated in state (no historical amnesia), enabling
 *    intra-session cruise-decay analytics.
 *  - The first hold starts immediately (no rest before it — there is nothing
 *    to recover from yet). The rest schedule linearly interpolates from
 *    restStartSec (before round 2) to restEndSec (before the final round).
 *    Equal values give a classic fixed-rest table; restEnd < restStart gives
 *    a progressive-overload "falling rest" table.
 */
@Singleton
class ContractionTableStateMachine @Inject constructor() {

    private val _state = MutableStateFlow(ContractionTableState())
    val state: StateFlow<ContractionTableState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var scope: CoroutineScope? = null

    /** Rest (ms) scheduled before each round, indexed by round number - 1. */
    private var restScheduleMs: List<Long> = emptyList()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Begins a contraction table session. Round 1 starts immediately with its
     * hold (CRUISE) — there is no rest before the first hold. The configured
     * rest schedule covers the breaths between rounds (round 2 … N).
     */
    fun start(
        mode: ContractionTableMode,
        rounds: Int,
        restStartSec: Int,
        restEndSec: Int,
        contractionTarget: Int,
        scope: CoroutineScope
    ) {
        this.scope = scope
        // One rest entry per inter-round breath (rounds 2..N); round 1 holds
        // straight away, so a single-round table has no rest at all.
        restScheduleMs = buildRestSchedule((rounds - 1).coerceAtLeast(0), restStartSec, restEndSec)
        _state.value = ContractionTableState(
            mode = mode,
            totalRounds = rounds,
            contractionTarget = if (mode == ContractionTableMode.CONTRACTION_COUNT) contractionTarget else 0
        )
        beginCruise(1)
    }

    /**
     * Ends the session. If a hold is in progress it is recorded as a partial
     * (ended-early) round first. Always transitions to COMPLETE.
     */
    fun stop() {
        timerJob?.cancel()
        val current = _state.value
        when (current.phase) {
            ContractionTablePhase.CRUISE,
            ContractionTablePhase.STRUGGLE -> {
                val partial = buildCurrentRoundResult(
                    current,
                    completed = false,
                    endedEarly = true,
                    now = System.currentTimeMillis()
                )
                _state.value = current.copy(
                    phase = ContractionTablePhase.COMPLETE,
                    totalHoldTimeMs = current.totalHoldTimeMs + partial.totalHoldMs,
                    realTimeTotalHoldTimeMs = current.totalHoldTimeMs + partial.totalHoldMs,
                    roundResults = current.roundResults + partial
                )
            }
            else -> {
                _state.value = current.copy(
                    phase = ContractionTablePhase.COMPLETE,
                    realTimeTotalHoldTimeMs = current.totalHoldTimeMs
                )
            }
        }
    }

    /**
     * Called when the user taps "First Contraction" during CRUISE.
     *
     * TILL_CONTRACTION: the round ends immediately (the contraction IS the target).
     * CONTRACTION_COUNT: freezes the cruise time and enters the struggle phase.
     */
    fun signalFirstContraction() {
        val current = _state.value
        if (current.phase != ContractionTablePhase.CRUISE) return
        val now = System.currentTimeMillis()

        if (current.mode == ContractionTableMode.TILL_CONTRACTION) {
            finishCurrentRound(current, completed = true, endedEarly = false, now)
        } else {
            if (current.contractionTarget <= 1) {
                // Degenerate config: the first contraction already satisfies the target.
                finishCurrentRound(
                    current.copy(
                        firstContractionEpochMs = now,
                        cruiseElapsedMs = now - (current.holdStartEpochMs ?: now),
                        contractionsInHold = 1
                    ),
                    completed = true,
                    endedEarly = false,
                    now
                )
            } else {
                _state.value = current.copy(
                    phase = ContractionTablePhase.STRUGGLE,
                    firstContractionEpochMs = now,
                    cruiseElapsedMs = now - (current.holdStartEpochMs ?: now),
                    contractionsInHold = 1
                )
            }
        }
    }

    /**
     * Called when the user logs a subsequent contraction during STRUGGLE
     * (CONTRACTION_COUNT mode). When the configured target is reached the
     * round completes.
     */
    fun signalContraction() {
        val current = _state.value
        if (current.phase != ContractionTablePhase.STRUGGLE) return
        val count = current.contractionsInHold + 1
        if (count >= current.contractionTarget) {
            finishCurrentRound(
                _state.value.copy(contractionsInHold = count),
                completed = true,
                endedEarly = false,
                System.currentTimeMillis()
            )
        } else {
            _state.value = current.copy(contractionsInHold = count)
        }
    }

    /**
     * Localised bail-out: ends the current hold early (recorded as a partial
     * round) without terminating the session. No-op outside a hold phase.
     */
    fun endHoldEarly() {
        val current = _state.value
        if (current.phase != ContractionTablePhase.CRUISE &&
            current.phase != ContractionTablePhase.STRUGGLE
        ) return
        finishCurrentRound(current, completed = false, endedEarly = true, System.currentTimeMillis())
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun startBreathePhase(round: Int) {
        // Only ever called for round ≥ 2 — round 1 starts directly with its hold.
        val restMs = restScheduleMs.getOrElse(round - 2) { restScheduleMs.lastOrNull() ?: 0L }
        _state.value = _state.value.copy(
            phase = ContractionTablePhase.BREATHE,
            currentRound = round,
            restDurationMs = restMs,
            timerMs = restMs,
            isCountingUp = false,
            contractionsInHold = 0,
            cruiseElapsedMs = 0L,
            holdStartEpochMs = null,
            firstContractionEpochMs = null,
            realTimeTotalHoldTimeMs = _state.value.totalHoldTimeMs
        )
        breatheStartEpochMs = System.currentTimeMillis()
        runTicker { now ->
            val current = _state.value
            if (current.phase != ContractionTablePhase.BREATHE) return@runTicker null
            // restDurationMs is already in ms — anchor the countdown to the moment
            // the BREATHE state was entered.
            val end = breatheStartEpochMs + current.restDurationMs - now
            if (end <= 0L) {
                beginCruise(round)
                null // stop this ticker; the cruise ticker takes over
            } else {
                _state.value = current.copy(timerMs = end)
                true
            }
        }
    }

    /** Epoch ms when the current BREATHE phase started (set on each phase transition). */
    private var breatheStartEpochMs: Long = 0L

    private fun beginCruise(round: Int) {
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = ContractionTablePhase.CRUISE,
            currentRound = round,
            timerMs = 0L,
            isCountingUp = true,
            holdStartEpochMs = now,
            firstContractionEpochMs = null,
            contractionsInHold = 0,
            cruiseElapsedMs = 0L
        )
        runTicker { nowTick ->
            val current = _state.value
            when (current.phase) {
                ContractionTablePhase.CRUISE -> {
                    val elapsed = nowTick - (current.holdStartEpochMs ?: nowTick)
                    _state.value = current.copy(
                        timerMs = elapsed,
                        realTimeTotalHoldTimeMs = current.totalHoldTimeMs + elapsed
                    )
                    true
                }
                ContractionTablePhase.STRUGGLE -> {
                    // signalFirstContraction switched phases under us — keep showing
                    // the total-hold elapsed time.
                    val elapsed = nowTick - (current.holdStartEpochMs ?: nowTick)
                    _state.value = current.copy(
                        timerMs = elapsed,
                        realTimeTotalHoldTimeMs = current.totalHoldTimeMs + elapsed
                    )
                    true
                }
                else -> null // phase ended — stop ticking
            }
        }
    }

    /**
     * Records the round result and either starts the next round's BREATHE phase
     * or completes the session.
     */
    private fun finishCurrentRound(
        stateAtEnd: ContractionTableState,
        completed: Boolean,
        endedEarly: Boolean,
        now: Long
    ) {
        timerJob?.cancel()
        val result = buildCurrentRoundResult(stateAtEnd, completed, endedEarly, now)
        val newTotalHold = stateAtEnd.totalHoldTimeMs + result.totalHoldMs
        val nextRound = stateAtEnd.currentRound + 1
        val base = stateAtEnd.copy(
            totalHoldTimeMs = newTotalHold,
            realTimeTotalHoldTimeMs = newTotalHold,
            roundResults = stateAtEnd.roundResults + result
        )
        if (nextRound > stateAtEnd.totalRounds) {
            _state.value = base.copy(phase = ContractionTablePhase.COMPLETE)
        } else {
            _state.value = base
            startBreathePhase(nextRound)
        }
    }

    private fun buildCurrentRoundResult(
        state: ContractionTableState,
        completed: Boolean,
        endedEarly: Boolean,
        now: Long
    ): ContractionTableRoundResult {
        val holdStart = state.holdStartEpochMs
        val firstContraction = state.firstContractionEpochMs
        val cruiseMs: Long? = when {
            // Bailed (or stopped) before any contraction — there was no contraction,
            // so there is no cruise time; only the partial hold duration is kept.
            state.phase == ContractionTablePhase.CRUISE -> null
            firstContraction != null && holdStart != null ->
                (firstContraction - holdStart).coerceAtLeast(0L)
            else -> null
        }
        val totalHoldMs = if (holdStart != null) (now - holdStart).coerceAtLeast(0L) else 0L
        val struggleMs = if (cruiseMs != null) (totalHoldMs - cruiseMs).coerceAtLeast(0L) else 0L
        val contractions = when {
            state.phase == ContractionTablePhase.CRUISE -> 0 // no contraction logged yet
            completed && state.mode == ContractionTableMode.TILL_CONTRACTION -> 1
            else -> state.contractionsInHold
        }
        return ContractionTableRoundResult(
            roundNumber = state.currentRound,
            restBeforeMs = state.restDurationMs,
            cruiseMs = cruiseMs,
            struggleMs = struggleMs,
            totalHoldMs = totalHoldMs,
            contractionsLogged = contractions,
            completed = completed,
            endedEarly = endedEarly
        )
    }

    /**
     * Runs a 100 ms tick loop. The callback returns `true` to continue ticking,
     * `null` to stop. All durations are recomputed from epoch anchors each tick,
     * so the timers are immune to coroutine scheduling drift.
     */
    private fun runTicker(onTick: suspend (now: Long) -> Boolean?) {
        timerJob?.cancel()
        timerJob = scope?.launch {
            while (isActive) {
                delay(100L)
                if (onTick(System.currentTimeMillis()) == null) break
            }
        }
    }

    private fun buildRestSchedule(rounds: Int, restStartSec: Int, restEndSec: Int): List<Long> {
        if (rounds <= 0) return emptyList()
        val startMs = restStartSec * 1000L
        val endMs = restEndSec * 1000L
        return (0 until rounds).map { i ->
            if (rounds == 1) startMs
            else startMs + (endMs - startMs) * i / (rounds - 1)
        }
    }
}
