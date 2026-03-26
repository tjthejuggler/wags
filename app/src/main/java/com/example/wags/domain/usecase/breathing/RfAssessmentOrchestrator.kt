package com.example.wags.domain.usecase.breathing

import com.example.wags.domain.usecase.hrv.FrequencyDomainCalculator
import com.example.wags.domain.usecase.hrv.PchipResampler
import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

enum class RfProtocol { EXPRESS, STANDARD, DEEP, CONTINUOUS, TARGETED, SLIDING_WINDOW }
enum class RfPhase    { IDLE, BASELINE, TEST_BLOCK, WASHOUT, COMPLETE }

// ---------------------------------------------------------------------------
// Data models
// ---------------------------------------------------------------------------

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
 * Unified state emitted by [RfAssessmentOrchestrator.state].
 *
 * - [Active]        — session running; carries pacer info, time remaining, latest epoch
 * - [SlidingTick]   — SLIDING_WINDOW tick; carries live pacer state
 * - [Complete]      — stepped protocol finished
 * - [SlidingDone]   — SLIDING_WINDOW finished; carries [SlidingWindowResult]
 * - [Idle]          — no session
 */
sealed class RfOrchestratorState {
    data object Idle : RfOrchestratorState()

    data class Active(
        val phase: RfPhase,
        val rateBpm: Float,
        val ieRatio: Float,
        val remainingSeconds: Long,
        val progress: Float,           // 0.0–1.0 overall session progress
        val latestEpoch: RfEpochResult?
    ) : RfOrchestratorState()

    data class SlidingTick(
        val pacerState: SlidingWindowPacerEngine.PacerState,
        val progress: Float            // 0.0–1.0
    ) : RfOrchestratorState()

    data class Complete(
        val epochs: List<RfEpochResult>
    ) : RfOrchestratorState()

    data class SlidingDone(
        val result: SlidingWindowResult
    ) : RfOrchestratorState()
}

// ---------------------------------------------------------------------------
// Orchestrator
// ---------------------------------------------------------------------------

/**
 * Orchestrates multi-protocol resonance frequency assessment.
 *
 * Protocols:
 * - EXPRESS:        5 rates × 1 min test + 30 s washout  (~8 min)
 * - STANDARD:       5 rates × 2 min test + 60 s washout  (~18 min)
 * - DEEP:          13 rate/IE combos × 3 min test        (~42 min)
 * - TARGETED:      [opt, opt+0.1, opt-0.1] × 3 min       (~10 min)
 * - CONTINUOUS:    5 rates × 2 min, no washout
 * - SLIDING_WINDOW: chirp sweep 6.75→4.5 BPM over 78 breaths (~16 min)
 *
 * RR intervals are fed in via [feedRr] (called by the ViewModel on each beat).
 * The orchestrator collects them internally and computes epoch math at phase boundaries.
 *
 * Composite score = phase×0.40 + PT×0.30 + LFnu×0.20 + RMSSD×0.10, scaled ×260
 * Quality gates: phaseSynchrony >= 0.25 AND ptAmplitude >= 1.5 BPM
 */
class RfAssessmentOrchestrator @Inject constructor(
    private val timeDomainCalc: TimeDomainHrvCalculator,
    private val freqDomainCalc: FrequencyDomainCalculator,
    private val coherenceCalc: CoherenceScoreCalculator,
    private val resampler: PchipResampler
) {

    companion object {
        /**
         * Core 5 rates used by STANDARD and CONTINUOUS protocols.
         * These are shuffled at session start so the order is randomized.
         */
        private val STANDARD_RATES_BPM = listOf(4.5f, 5.0f, 5.5f, 6.0f, 6.5f)

        /**
         * Expanded pool for EXPRESS protocol. Each session randomly picks 5
         * from this larger set so that repeated assessments cover different
         * rates, reducing order bias and improving long-term calibration.
         */
        private val EXPRESS_POOL_BPM = listOf(4.0f, 4.5f, 5.0f, 5.5f, 6.0f, 6.5f, 7.0f)

        /** 13-combination grid for DEEP protocol: (rateBpm to ieRatio). */
        val DEEP_GRID = listOf(
            6.5f to 1.0f, 6.5f to 1.5f,
            6.0f to 1.0f, 6.0f to 1.5f, 6.0f to 2.0f,
            5.5f to 1.0f, 5.5f to 1.5f, 5.5f to 2.0f,
            5.0f to 1.0f, 5.0f to 1.5f, 5.0f to 2.0f,
            4.5f to 1.0f, 4.5f to 1.5f
        )

        private const val BASELINE_DURATION_MS = 1 * 60 * 1000L
        private const val PHASE_SYNC_THRESHOLD = 0.25f
        private const val PT_AMPLITUDE_THRESHOLD = 1.5f
        private const val SLIDING_TICK_MS = 50L   // 20 Hz pacer update
    }

    // -----------------------------------------------------------------------
    // Legacy StateFlows — kept for backward compatibility with BreathingViewModel
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Unified state flow
    // -----------------------------------------------------------------------

    private val _state = MutableStateFlow<RfOrchestratorState>(RfOrchestratorState.Idle)
    val state: StateFlow<RfOrchestratorState> = _state.asStateFlow()

    // -----------------------------------------------------------------------
    // RR interval ingestion
    // -----------------------------------------------------------------------

    /** ViewModel calls this for every RR interval received from the sensor (ms). */
    private val _rrFlow = MutableSharedFlow<Float>(extraBufferCapacity = 512)

    fun feedRr(rrMs: Float) {
        _rrFlow.tryEmit(rrMs)
    }

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    private var assessmentJob: Job? = null

    // Baseline metrics (computed at end of BASELINE phase)
    private var baselineRmssd = 0f
    private var baselinePtAmp = 0f

    // Per-epoch RR buffer (cleared between epochs)
    private val epochRrBuffer = mutableListOf<Float>()

    // Sliding window accumulators
    private val swRrTimestamps = mutableListOf<Long>()
    private val swRrIntervals  = mutableListOf<Float>()
    private val swRefWave      = mutableListOf<Float>()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun start(
        protocol: RfProtocol,
        scope: CoroutineScope,
        optimalBpm: Float = 5.5f,
        onEpochComplete: (RfEpochResult) -> Unit = {},
        onComplete: (List<RfEpochResult>) -> Unit = {}
    ) {
        assessmentJob?.cancel()
        resetState()

        assessmentJob = when (protocol) {
            RfProtocol.SLIDING_WINDOW -> scope.launch {
                runSlidingWindow(scope)
            }
            else -> scope.launch {
                runSteppedProtocol(protocol, optimalBpm, onEpochComplete, onComplete)
            }
        }

        // Collect incoming RR intervals into the active buffer
        scope.launch {
            _rrFlow.collect { rr ->
                epochRrBuffer.add(rr)
                if (protocol == RfProtocol.SLIDING_WINDOW) {
                    swRrTimestamps.add(System.currentTimeMillis())
                    swRrIntervals.add(rr)
                }
            }
        }
    }

    fun stop() {
        assessmentJob?.cancel()
        _phase.value = RfPhase.IDLE
        _remainingSeconds.value = 0L
        _state.value = RfOrchestratorState.Idle
    }

    /** Called by ViewModel to manually record an epoch result (legacy path). */
    fun recordEpochResult(result: RfEpochResult) {
        _epochResults.value = _epochResults.value + result
    }

    // -----------------------------------------------------------------------
    // Stepped protocol runner
    // -----------------------------------------------------------------------

    private suspend fun runSteppedProtocol(
        protocol: RfProtocol,
        optimalBpm: Float,
        onEpochComplete: (RfEpochResult) -> Unit,
        onComplete: (List<RfEpochResult>) -> Unit
    ) {
        val (grid, testDurationMs, washoutDurationMs) = protocolParams(protocol, optimalBpm)
        val totalEpochs = grid.size
        val totalMs = BASELINE_DURATION_MS + totalEpochs * (testDurationMs + washoutDurationMs)

        // --- BASELINE ---
        _phase.value = RfPhase.BASELINE
        emitActive(RfPhase.BASELINE, 0f, 1.0f, BASELINE_DURATION_MS / 1000L, 0f, null)
        epochRrBuffer.clear()
        runCountdown(BASELINE_DURATION_MS, totalMs, 0L)
        computeBaselineMetrics()

        // --- TEST BLOCKS ---
        var elapsedMs = BASELINE_DURATION_MS
        grid.forEachIndexed { idx, (rateBpm, ieRatio) ->
            _phase.value = RfPhase.TEST_BLOCK
            _currentRateBpm.value = rateBpm
            _currentIeRatio.value = ieRatio
            epochRrBuffer.clear()

            runCountdown(testDurationMs, totalMs, elapsedMs)
            elapsedMs += testDurationMs

            val epoch = computeEpochResult(rateBpm, ieRatio)
            _epochResults.value = _epochResults.value + epoch
            onEpochComplete(epoch)

            val progress = elapsedMs.toFloat() / totalMs
            emitActive(RfPhase.TEST_BLOCK, rateBpm, ieRatio, 0L, progress, epoch)

            if (washoutDurationMs > 0) {
                _phase.value = RfPhase.WASHOUT
                runCountdown(washoutDurationMs, totalMs, elapsedMs)
                elapsedMs += washoutDurationMs
            }
        }

        _phase.value = RfPhase.COMPLETE
        val finalEpochs = _epochResults.value
        _state.value = RfOrchestratorState.Complete(finalEpochs)
        onComplete(finalEpochs)
    }

    // -----------------------------------------------------------------------
    // Sliding window runner
    // -----------------------------------------------------------------------

    private suspend fun runSlidingWindow(scope: CoroutineScope) {
        val totalSec = SlidingWindowPacerEngine.totalDurationSeconds
        val startMs = System.currentTimeMillis()

        while (true) {
            val elapsedSec = (System.currentTimeMillis() - startMs) / 1000f
            val pacerState = SlidingWindowPacerEngine.evaluate(elapsedSec) ?: break

            // Accumulate reference wave at pacer tick rate
            swRefWave.add(pacerState.refWave)

            val progress = (elapsedSec / totalSec).coerceIn(0f, 1f)
            _state.value = RfOrchestratorState.SlidingTick(pacerState, progress)
            _currentRateBpm.value = pacerState.instantBpm
            _remainingSeconds.value = (totalSec - elapsedSec).toLong().coerceAtLeast(0L)

            delay(SLIDING_TICK_MS)
        }

        // Session complete — run analytics
        val result = SlidingWindowAnalytics.analyze(
            rrTimestampsMs  = swRrTimestamps.toLongArray(),
            rrIntervalsMs   = swRrIntervals.toFloatArray(),
            refWaveSamples  = swRefWave.toFloatArray(),
            totalDurationSeconds = totalSec
        )
        _phase.value = RfPhase.COMPLETE
        _state.value = RfOrchestratorState.SlidingDone(result)
    }

    // -----------------------------------------------------------------------
    // Math helpers
    // -----------------------------------------------------------------------

    private fun computeBaselineMetrics() {
        val rr = epochRrBuffer.map { it.toDouble() }.toDoubleArray()
        if (rr.size < 4) return

        val hrv = timeDomainCalc.calculate(rr)
        baselineRmssd = hrv.rmssdMs.toFloat()

        // Estimate baseline PT amplitude from IHR swing
        val ihrBpm = rrToIhr(rr)
        baselinePtAmp = if (ihrBpm.isNotEmpty()) (ihrBpm.max() - ihrBpm.min()).toFloat() else 0f
    }

    private fun computeEpochResult(rateBpm: Float, ieRatio: Float): RfEpochResult {
        val rr = epochRrBuffer.map { it.toDouble() }.toDoubleArray()

        if (rr.size < 8) return invalidEpoch(rateBpm, ieRatio)

        val hrv    = timeDomainCalc.calculate(rr)
        val freq   = freqDomainCalc.calculate(rr)
        val ihrBpm = rrToIhr(rr)

        val cycleSec = 60.0 / rateBpm
        val refWave  = buildRefWave(ihrBpm.size, cycleSec, PchipResampler.RESAMPLE_RATE_HZ)
        val cycleStarts = breathCycleStarts(ihrBpm.size, cycleSec, PchipResampler.RESAMPLE_RATE_HZ)

        val phaseSynchrony = coherenceCalc.calculatePhaseSynchrony(ihrBpm, refWave, cycleSec)
        val ptAmp          = coherenceCalc.calculatePtAmplitude(ihrBpm, cycleStarts)
        val lfNu           = freq.lfNormalizedUnits.toFloat()
        val rmssd          = hrv.rmssdMs.toFloat()

        val epochScore = SteppedEpochScorer.score(
            rmssd          = rmssd,
            ptAmp          = ptAmp,
            lfNu           = lfNu,
            phaseSynchrony = phaseSynchrony,
            baselineRmssd  = baselineRmssd,
            baselinePtAmp  = baselinePtAmp
        )

        val rmssdRatio = if (baselineRmssd > 0f) rmssd / baselineRmssd else 0f

        return RfEpochResult(
            rateBpm        = rateBpm,
            ieRatio        = ieRatio,
            compositeScore = epochScore.score,
            phaseSynchrony = phaseSynchrony,
            ptAmplitude    = ptAmp,
            lfNu           = lfNu,
            rmssdRatio     = rmssdRatio,
            isValid        = epochScore.isValid
        )
    }

    /** Convert RR intervals (ms) to instantaneous HR (BPM) at 4 Hz via PCHIP resampling. */
    private fun rrToIhr(rr: DoubleArray): DoubleArray {
        if (rr.size < 4) return DoubleArray(0)
        val resampled = resampler.resample(rr)
        return DoubleArray(resampled.size) { 60_000.0 / resampled[it].coerceAtLeast(1.0) }
    }

    /** Build a cosine reference wave at [sampleRateHz] for [nSamples] samples. */
    private fun buildRefWave(nSamples: Int, cycleSec: Double, sampleRateHz: Double): DoubleArray {
        return DoubleArray(nSamples) { i ->
            val t = i / sampleRateHz
            (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * t / cycleSec)) / 2.0
        }
    }

    /** Indices (in 4 Hz samples) where each breath cycle starts. */
    private fun breathCycleStarts(nSamples: Int, cycleSec: Double, sampleRateHz: Double): List<Int> {
        val samplesPerCycle = (cycleSec * sampleRateHz).toInt().coerceAtLeast(1)
        return (0 until nSamples step samplesPerCycle).toList()
    }

    private fun invalidEpoch(rateBpm: Float, ieRatio: Float) = RfEpochResult(
        rateBpm = rateBpm, ieRatio = ieRatio,
        compositeScore = 0f, phaseSynchrony = 0f, ptAmplitude = 0f,
        lfNu = 0f, rmssdRatio = 0f, isValid = false
    )

    // -----------------------------------------------------------------------
    // Protocol params
    // -----------------------------------------------------------------------

    /**
     * Returns (grid, testDurationMs, washoutDurationMs).
     * Grid is a list of (rateBpm, ieRatio) pairs.
     *
     * All stepped protocols shuffle their rate order so that repeated
     * assessments don't always test the same rate first. EXPRESS draws
     * 5 rates from a larger pool for better long-term coverage.
     */
    private fun protocolParams(
        protocol: RfProtocol,
        optimalBpm: Float
    ): Triple<List<Pair<Float, Float>>, Long, Long> = when (protocol) {
        RfProtocol.EXPRESS    -> Triple(
            EXPRESS_POOL_BPM.shuffled().take(5).map { it to 1.0f },
            60_000L, 30_000L
        )
        RfProtocol.STANDARD   -> Triple(
            STANDARD_RATES_BPM.shuffled().map { it to 1.0f },
            120_000L, 60_000L
        )
        RfProtocol.DEEP       -> Triple(DEEP_GRID.shuffled(), 180_000L, 60_000L)
        RfProtocol.TARGETED   -> Triple(
            listOf(optimalBpm to 1.0f, optimalBpm + 0.1f to 1.0f, optimalBpm - 0.1f to 1.0f).shuffled(),
            180_000L, 60_000L
        )
        RfProtocol.CONTINUOUS -> Triple(
            STANDARD_RATES_BPM.shuffled().map { it to 1.0f },
            120_000L, 0L
        )
        RfProtocol.SLIDING_WINDOW -> Triple(emptyList(), 0L, 0L) // handled separately
    }

    // -----------------------------------------------------------------------
    // Countdown + state emission helpers
    // -----------------------------------------------------------------------

    private suspend fun runCountdown(durationMs: Long, totalMs: Long, elapsedSoFarMs: Long) {
        var remaining = durationMs / 1000L
        _remainingSeconds.value = remaining
        while (remaining > 0) {
            delay(1000L)
            remaining--
            _remainingSeconds.value = remaining
            val progress = (elapsedSoFarMs + (durationMs - remaining * 1000L)).toFloat() / totalMs
            emitActive(
                phase    = _phase.value,
                rateBpm  = _currentRateBpm.value,
                ieRatio  = _currentIeRatio.value,
                remSecs  = remaining,
                progress = progress.coerceIn(0f, 1f),
                epoch    = _epochResults.value.lastOrNull()
            )
        }
    }

    private fun emitActive(
        phase: RfPhase, rateBpm: Float, ieRatio: Float,
        remSecs: Long, progress: Float, epoch: RfEpochResult?
    ) {
        _state.value = RfOrchestratorState.Active(
            phase            = phase,
            rateBpm          = rateBpm,
            ieRatio          = ieRatio,
            remainingSeconds = remSecs,
            progress         = progress,
            latestEpoch      = epoch
        )
    }

    private fun resetState() {
        _phase.value = RfPhase.IDLE
        _currentRateBpm.value = 0f
        _currentIeRatio.value = 1.0f
        _epochResults.value = emptyList()
        _remainingSeconds.value = 0L
        _state.value = RfOrchestratorState.Idle
        baselineRmssd = 0f
        baselinePtAmp = 0f
        epochRrBuffer.clear()
        swRrTimestamps.clear()
        swRrIntervals.clear()
        swRefWave.clear()
    }
}
