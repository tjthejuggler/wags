package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.model.WonkaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class AdvancedApneaPhase {
    IDLE,
    VENTILATION,
    APNEA,
    WAITING_FOR_BREATH,
    WONKA_CRUISING,
    WONKA_ENDURANCE,
    RECOVERY,
    COMPLETE
}

data class AdvancedApneaState(
    val phase: AdvancedApneaPhase = AdvancedApneaPhase.IDLE,
    val currentRound: Int = 0,
    val totalRounds: Int = 0,
    val timerMs: Long = 0L,
    val isCountingUp: Boolean = false,
    val cruisingElapsedMs: Long = 0L,
    val holdDurationMs: Long = 0L,
    val restDurationMs: Long = 0L
)

@Singleton
class AdvancedApneaStateMachine @Inject constructor() {

    private val _state = MutableStateFlow(AdvancedApneaState())
    val state: StateFlow<AdvancedApneaState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var scope: CoroutineScope? = null
    private var modality: TrainingModality = TrainingModality.PROGRESSIVE_O2
    private var wonkaConfig: WonkaConfig = WonkaConfig()
    private var rounds: List<Pair<Long, Long>> = emptyList() // (holdMs, restMs)

    fun start(
        modality: TrainingModality,
        length: TableLength,
        pbMs: Long,
        wonkaConfig: WonkaConfig = WonkaConfig(),
        coroutineScope: CoroutineScope
    ) {
        this.modality = modality
        this.wonkaConfig = wonkaConfig
        this.scope = coroutineScope
        rounds = buildRounds(modality, length, pbMs, wonkaConfig)
        _state.value = AdvancedApneaState(
            phase = AdvancedApneaPhase.IDLE,
            totalRounds = rounds.size
        )
        startRound(1)
    }

    fun stop() {
        timerJob?.cancel()
        _state.value = AdvancedApneaState()
    }

    fun signalBreathTaken() {
        if (_state.value.phase != AdvancedApneaPhase.WAITING_FOR_BREATH) return
        val round = _state.value.currentRound
        if (round >= rounds.size) {
            _state.value = _state.value.copy(phase = AdvancedApneaPhase.COMPLETE)
            return
        }
        startRound(round + 1)
    }

    fun signalFirstContraction() {
        val current = _state.value
        if (current.phase != AdvancedApneaPhase.WONKA_CRUISING) return
        val cruisingMs = current.timerMs
        timerJob?.cancel()
        if (modality == TrainingModality.WONKA_FIRST_CONTRACTION) {
            _state.value = current.copy(
                phase = AdvancedApneaPhase.RECOVERY,
                cruisingElapsedMs = cruisingMs,
                timerMs = wonkaConfig.restSec * 1000L
            )
            runCountdown(wonkaConfig.restSec * 1000L) { advanceAfterRecovery() }
        } else {
            val enduranceMs = wonkaConfig.enduranceDeltaSec * 1000L
            _state.value = current.copy(
                phase = AdvancedApneaPhase.WONKA_ENDURANCE,
                cruisingElapsedMs = cruisingMs,
                timerMs = enduranceMs
            )
            runCountdown(enduranceMs) {
                _state.value = _state.value.copy(
                    phase = AdvancedApneaPhase.RECOVERY,
                    timerMs = wonkaConfig.restSec * 1000L
                )
                runCountdown(wonkaConfig.restSec * 1000L) { advanceAfterRecovery() }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startRound(round: Int) {
        val (holdMs, restMs) = rounds[round - 1]
        _state.value = _state.value.copy(
            phase = AdvancedApneaPhase.VENTILATION,
            currentRound = round,
            holdDurationMs = holdMs,
            restDurationMs = restMs,
            timerMs = restMs,
            isCountingUp = false,
            cruisingElapsedMs = 0L
        )
        runCountdown(restMs) { beginApneaPhase(round) }
    }

    private fun beginApneaPhase(round: Int) {
        val (holdMs, _) = rounds[round - 1]
        when (modality) {
            TrainingModality.MIN_BREATH -> {
                _state.value = _state.value.copy(
                    phase = AdvancedApneaPhase.APNEA,
                    timerMs = holdMs
                )
                runCountdown(holdMs) {
                    _state.value = _state.value.copy(
                        phase = AdvancedApneaPhase.WAITING_FOR_BREATH,
                        timerMs = 0L
                    )
                }
            }
            TrainingModality.WONKA_FIRST_CONTRACTION,
            TrainingModality.WONKA_ENDURANCE -> {
                _state.value = _state.value.copy(
                    phase = AdvancedApneaPhase.WONKA_CRUISING,
                    timerMs = 0L,
                    isCountingUp = true
                )
                runCountUp()
            }
            else -> {
                _state.value = _state.value.copy(
                    phase = AdvancedApneaPhase.APNEA,
                    timerMs = holdMs
                )
                runCountdown(holdMs) {
                    _state.value = _state.value.copy(
                        phase = AdvancedApneaPhase.RECOVERY,
                        timerMs = rounds[round - 1].second
                    )
                    runCountdown(rounds[round - 1].second) { advanceAfterRecovery() }
                }
            }
        }
    }

    private fun advanceAfterRecovery() {
        val next = _state.value.currentRound + 1
        if (next > rounds.size) {
            _state.value = _state.value.copy(phase = AdvancedApneaPhase.COMPLETE)
        } else {
            startRound(next)
        }
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

    private fun runCountUp() {
        timerJob?.cancel()
        timerJob = scope?.launch {
            while (true) {
                delay(1000L)
                _state.value = _state.value.copy(timerMs = _state.value.timerMs + 1000L)
            }
        }
    }

    private fun buildRounds(
        modality: TrainingModality,
        length: TableLength,
        pbMs: Long,
        wonkaConfig: WonkaConfig
    ): List<Pair<Long, Long>> {
        val rounds = when (length) {
            TableLength.SHORT  -> 4
            TableLength.MEDIUM -> 8
            TableLength.LONG   -> 12
        }
        return when (modality) {
            TrainingModality.PROGRESSIVE_O2 -> (1..rounds).map { r ->
                val holdMs = (30 + (r - 1) * 15) * 1000L
                holdMs to holdMs
            }
            TrainingModality.MIN_BREATH -> {
                val holdMs = (pbMs * 0.50).toLong()
                (1..rounds).map { holdMs to 30_000L }
            }
            TrainingModality.WONKA_FIRST_CONTRACTION,
            TrainingModality.WONKA_ENDURANCE -> {
                val restMs = wonkaConfig.restSec * 1000L
                (1..rounds).map { 0L to restMs }
            }
            else -> emptyList()
        }
    }
}
