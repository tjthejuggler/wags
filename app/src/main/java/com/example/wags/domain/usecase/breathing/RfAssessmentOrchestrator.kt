package com.example.wags.domain.usecase.breathing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RfProtocol { EXPRESS, STANDARD, DEEP, CONTINUOUS, TARGETED }
enum class RfPhase { IDLE, BASELINE, TEST_BLOCK, WASHOUT, COMPLETE }

data class RfEpochResult(
    val rateBpm: Float,
    val ieRatio: Float,
    val compositeScore: Float,
    val phaseSynchrony: Float,
    val ptAmplitude: Float,
    val lfNu: Float,
    val rmssdRatio: Float,
    val isValid: Boolean
)

/**
 * Orchestrates multi-protocol resonance frequency assessment.
 *
 * Protocols:
 * - EXPRESS:   5 rates × 1 min test + 30s washout  (~8 min)
 * - STANDARD:  5 rates × 2 min test + 60s washout  (~18 min)
 * - DEEP:     13 rate combinations × 3 min test    (~42 min)
 * - TARGETED: [optimal, optimal+0.2, optimal-0.2] × 3 min (~10 min)
 * - CONTINUOUS: 5 rates × 2 min, no washout
 *
 * Composite score = phase×0.40 + PT×0.30 + LFnu×0.20 + RMSSD×0.10
 * Quality gates: phaseSynchrony >= 0.25 AND ptAmplitude >= 1.5 BPM
 */
class RfAssessmentOrchestrator @Inject constructor() {

    companion object {
        private val TEST_RATES_BPM = listOf(4.5f, 5.0f, 5.5f, 6.0f, 6.5f)
        private val DEEP_RATES_BPM = listOf(
            4.5f, 5.0f, 5.5f, 6.0f, 6.5f,
            5.25f, 4.75f, 5.75f, 6.25f,
            4.25f, 6.75f, 5.0f, 5.5f
        )
        private const val BASELINE_DURATION_MS = 2 * 60 * 1000L
        private const val PHASE_SYNC_THRESHOLD = 0.25f
        private const val PT_AMPLITUDE_THRESHOLD = 1.5f
    }

    private val _phase = MutableStateFlow(RfPhase.IDLE)
    val phase: StateFlow<RfPhase> = _phase.asStateFlow()

    private val _currentRateBpm = MutableStateFlow(0f)
    val currentRateBpm: StateFlow<Float> = _currentRateBpm.asStateFlow()

    private val _currentIeRatio = MutableStateFlow(1.0f)
    val currentIeRatio: StateFlow<Float> = _currentIeRatio.asStateFlow()

    private val _epochResults = MutableStateFlow<List<RfEpochResult>>(emptyList())
    val epochResults: StateFlow<List<RfEpochResult>> = _epochResults.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private var assessmentJob: Job? = null

    fun start(
        protocol: RfProtocol,
        scope: CoroutineScope,
        optimalBpm: Float = 5.5f,
        onEpochComplete: (RfEpochResult) -> Unit,
        onComplete: (List<RfEpochResult>) -> Unit
    ) {
        assessmentJob?.cancel()
        _epochResults.value = emptyList()

        val (rates, testDurationMs, washoutDurationMs) = protocolParams(protocol, optimalBpm)

        assessmentJob = scope.launch {
            _phase.value = RfPhase.BASELINE
            runCountdown(BASELINE_DURATION_MS)

            for (rate in rates) {
                _phase.value = RfPhase.TEST_BLOCK
                _currentRateBpm.value = rate
                _currentIeRatio.value = 1.0f
                runCountdown(testDurationMs)

                // Placeholder epoch — ViewModel fills real metrics via recordEpochResult()
                val placeholder = RfEpochResult(
                    rateBpm = rate,
                    ieRatio = 1.0f,
                    compositeScore = 0f,
                    phaseSynchrony = 0f,
                    ptAmplitude = 0f,
                    lfNu = 0f,
                    rmssdRatio = 0f,
                    isValid = false
                )
                onEpochComplete(placeholder)

                if (washoutDurationMs > 0) {
                    _phase.value = RfPhase.WASHOUT
                    runCountdown(washoutDurationMs)
                }
            }

            _phase.value = RfPhase.COMPLETE
            onComplete(_epochResults.value)
        }
    }

    private fun protocolParams(
        protocol: RfProtocol,
        optimalBpm: Float
    ): Triple<List<Float>, Long, Long> = when (protocol) {
        RfProtocol.EXPRESS    -> Triple(TEST_RATES_BPM, 60_000L, 30_000L)
        RfProtocol.STANDARD   -> Triple(TEST_RATES_BPM, 120_000L, 60_000L)
        RfProtocol.DEEP       -> Triple(DEEP_RATES_BPM, 180_000L, 60_000L)
        RfProtocol.TARGETED   -> Triple(
            listOf(optimalBpm, optimalBpm + 0.2f, optimalBpm - 0.2f),
            180_000L, 60_000L
        )
        RfProtocol.CONTINUOUS -> Triple(TEST_RATES_BPM, 120_000L, 0L)
    }

    private suspend fun runCountdown(durationMs: Long) {
        var remaining = durationMs / 1000L
        _remainingSeconds.value = remaining
        while (remaining > 0) {
            delay(1000L)
            remaining--
            _remainingSeconds.value = remaining
        }
    }

    /** Called by ViewModel with real computed metrics to store the epoch result. */
    fun recordEpochResult(result: RfEpochResult) {
        _epochResults.value = _epochResults.value + result
    }

    /**
     * Composite score formula:
     * phase×0.40 + PT×0.30 + LFnu×0.20 + RMSSD×0.10, scaled to 0–260+
     */
    fun computeCompositeScore(
        phaseSynchrony: Float,
        ptAmplitude: Float,
        baselinePtAmplitude: Float,
        lfNu: Float,
        rmssd: Float,
        baselineRmssd: Float
    ): Float {
        val ptRatio = if (baselinePtAmplitude > 0f) (ptAmplitude / baselinePtAmplitude).coerceAtMost(5f) else 0f
        val rmssdRatio = if (baselineRmssd > 0f) (rmssd / baselineRmssd).coerceAtMost(5f) else 0f
        val lfNuNorm = (lfNu / 100f).coerceAtMost(1f)
        return (phaseSynchrony * 0.40f + ptRatio * 0.30f + lfNuNorm * 0.20f + rmssdRatio * 0.10f) * 100f
    }

    /** Quality gate: both thresholds must pass for an epoch to be considered valid. */
    fun isEpochValid(phaseSynchrony: Float, ptAmplitude: Float): Boolean =
        phaseSynchrony >= PHASE_SYNC_THRESHOLD && ptAmplitude >= PT_AMPLITUDE_THRESHOLD

    fun stop() {
        assessmentJob?.cancel()
        _phase.value = RfPhase.IDLE
        _remainingSeconds.value = 0L
    }
}
