package com.example.wags.domain.usecase.breathing

import com.example.wags.domain.usecase.hrv.FftProcessor
import com.example.wags.domain.usecase.hrv.PchipResampler
import com.example.wags.domain.usecase.hrv.PsdBandIntegrator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Post-session analytics for the Sliding Window RF protocol.
 *
 * Port of the desktop ContinuousSlidingMath class.
 * Pure Kotlin object — no Android dependencies, no coroutines, no DI.
 *
 * Pipeline:
 * 1. Resample RR intervals to uniform 4 Hz grid via PCHIP
 * 2. Slide a 60-second window (step 10 s) computing LF power, PT amplitude, Hilbert PLV
 * 3. Compute composite resonance index per window
 * 4. Detect peak resonance → resonance frequency BPM
 * 5. Apply quality gates
 */
object SlidingWindowAnalytics {

    private const val SAMPLE_RATE_HZ = 4f
    private const val WINDOW_SECONDS = 60
    private const val STEP_SECONDS = 10
    private const val WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE_HZ.toInt()   // 240
    private const val STEP_SAMPLES = STEP_SECONDS * SAMPLE_RATE_HZ.toInt()        // 40
    private const val MIN_RR_SAMPLES = 120   // < 30 s of data → invalid

    // Quality gate thresholds
    private const val MIN_PEAK_PLV = 0.3f
    private const val MIN_PEAK_TO_MEAN_RATIO = 1.5f

    // Lazy-instantiated utilities (no DI needed for pure object)
    private val resampler by lazy { PchipResampler() }
    private val fftProcessor by lazy { FftProcessor() }
    private val bandIntegrator by lazy { PsdBandIntegrator() }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun analyze(
        rrTimestampsMs: LongArray,
        rrIntervalsMs: FloatArray,
        refWaveSamples: FloatArray,
        totalDurationSeconds: Float
    ): SlidingWindowResult {
        if (rrIntervalsMs.size < MIN_RR_SAMPLES) {
            return invalidResult("Insufficient RR data")
        }

        // Step 1 — Resample to 4 Hz uniform grid
        val rrDouble = DoubleArray(rrIntervalsMs.size) { rrIntervalsMs[it].toDouble() }
        val resampled = resampler.resample(rrDouble)
        if (resampled.size < WINDOW_SAMPLES) {
            return invalidResult("Insufficient RR data after resampling")
        }

        // Step 2 — Sliding window loop
        val windowCount = windowCount(resampled.size)
        val timeGrid = FloatArray(windowCount)
        val lfPowerRaw = FloatArray(windowCount)
        val ptAmpRaw = FloatArray(windowCount)
        val plvRaw = FloatArray(windowCount)
        val pacingBpmSeries = FloatArray(windowCount)

        for (w in 0 until windowCount) {
            val start = w * STEP_SAMPLES
            val end = start + WINDOW_SAMPLES
            val window = resampled.sliceArray(start until end)

            // Center time of this window in seconds
            val centerSec = (start + WINDOW_SAMPLES / 2f) / SAMPLE_RATE_HZ
            timeGrid[w] = centerSec

            // LF power (normalized by total power)
            lfPowerRaw[w] = computeNormalizedLfPower(window)

            // PT amplitude: max - min of RR window in ms
            ptAmpRaw[w] = (window.max() - window.min()).toFloat()

            // Pacing BPM at window center
            val pacerState = SlidingWindowPacerEngine.evaluate(centerSec)
            val bpm = pacerState?.instantBpm ?: SlidingWindowPacerEngine.START_BPM
            pacingBpmSeries[w] = bpm

            // Hilbert PLV between RR window and reference wave slice
            val refSlice = refWaveSlice(refWaveSamples, start, end)
            plvRaw[w] = computeHilbertPlv(window, refSlice, bpm)
        }

        // Step 3 — Normalize each series to [0,1] and compute resonance index
        val lfNorm = normalizeToUnit(lfPowerRaw)
        val ptNorm = normalizeToUnit(ptAmpRaw)
        val plvNorm = normalizeToUnit(plvRaw)
        val resonanceIndex = FloatArray(windowCount) { i ->
            0.4f * lfNorm[i] + 0.4f * ptNorm[i] + 0.2f * plvNorm[i]
        }

        // Step 4 — Peak detection
        val peakIdx = resonanceIndex.indices.maxByOrNull { resonanceIndex[it] } ?: 0
        val peakResonanceIndex = resonanceIndex[peakIdx]
        val resonanceFrequencyBpm = pacingBpmSeries[peakIdx]
        val peakPlv = plvRaw[peakIdx]

        // Step 5 — Quality gates
        val meanResonance = resonanceIndex.average().toFloat()
        val peakToMeanRatio = if (meanResonance > 0f) peakResonanceIndex / meanResonance else 0f

        val isValid = peakPlv >= MIN_PEAK_PLV && peakToMeanRatio >= MIN_PEAK_TO_MEAN_RATIO
        val invalidReason = when {
            peakPlv < MIN_PEAK_PLV -> "PLV too low (${peakPlv.format2()}): no meaningful phase locking"
            peakToMeanRatio < MIN_PEAK_TO_MEAN_RATIO ->
                "Peak resonance does not stand out from baseline (ratio ${peakToMeanRatio.format2()})"
            else -> null
        }

        return SlidingWindowResult(
            resonanceFrequencyBpm = resonanceFrequencyBpm,
            peakResonanceIndex = peakResonanceIndex,
            peakPlv = peakPlv,
            isValid = isValid,
            invalidReason = invalidReason,
            timeGrid = timeGrid,
            lfPowerSeries = lfPowerRaw,
            ptAmpSeries = ptAmpRaw,
            plvSeries = plvRaw,
            pacingBpmSeries = pacingBpmSeries
        )
    }

    // -------------------------------------------------------------------------
    // LF Power
    // -------------------------------------------------------------------------

    private fun computeNormalizedLfPower(window: DoubleArray): Float {
        val fftResult = fftProcessor.process(window)
        val lfPower = bandIntegrator.trapezoidalIntegral(
            fftResult.psd, fftResult.freqAxis,
            PsdBandIntegrator.LF_LOW_HZ, PsdBandIntegrator.LF_HIGH_HZ
        )
        val totalPower = bandIntegrator.trapezoidalIntegral(
            fftResult.psd, fftResult.freqAxis,
            PsdBandIntegrator.TOTAL_POWER_FLOOR_HZ, PsdBandIntegrator.HF_HIGH_HZ
        )
        return if (totalPower > 0.0) (lfPower / totalPower).toFloat() else 0f
    }

    // -------------------------------------------------------------------------
    // Hilbert PLV
    // -------------------------------------------------------------------------

    /**
     * Approximates Hilbert PLV between [rrWindow] and [refSlice].
     *
     * Approach:
     * 1. Shift the RR window by N/4 samples to approximate a 90° phase shift (Hilbert approximation)
     * 2. Compute instantaneous phase of RR via atan2(hilbert[n], signal[n])
     * 3. Compute instantaneous phase of reference wave similarly
     * 4. PLV = |mean(exp(i * Δφ))| = sqrt(mean(cos Δφ)² + mean(sin Δφ)²)
     *
     * Returns 0 if window is too short for reliable estimation.
     */
    private fun computeHilbertPlv(
        rrWindow: DoubleArray,
        refSlice: FloatArray,
        pacingBpm: Float
    ): Float {
        val n = minOf(rrWindow.size, refSlice.size)
        val quarterShift = n / 4
        if (quarterShift < 4) return 0f

        // Analytic signal approximation for RR
        val rrPhase = instantaneousPhase(rrWindow, n, quarterShift)

        // Analytic signal approximation for reference wave
        val refDouble = DoubleArray(n) { refSlice[it].toDouble() }
        val refPhase = instantaneousPhase(refDouble, n, quarterShift)

        // PLV over the valid (non-shifted) region
        val validStart = quarterShift
        val validEnd = n - quarterShift
        if (validEnd <= validStart) return 0f

        var sumCos = 0.0
        var sumSin = 0.0
        val count = validEnd - validStart
        for (i in validStart until validEnd) {
            val dPhi = rrPhase[i] - refPhase[i]
            sumCos += cos(dPhi)
            sumSin += sin(dPhi)
        }
        val plv = sqrt((sumCos / count) * (sumCos / count) + (sumSin / count) * (sumSin / count))
        return plv.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Computes instantaneous phase array via quarter-shift Hilbert approximation.
     * hilbert[n] ≈ signal[n - quarterShift] (90° lag)
     * phase[n] = atan2(hilbert[n], signal[n])
     */
    private fun instantaneousPhase(
        signal: DoubleArray,
        n: Int,
        quarterShift: Int
    ): DoubleArray {
        return DoubleArray(n) { i ->
            val hilbertIdx = i - quarterShift
            val hilbertVal = if (hilbertIdx >= 0) signal[hilbertIdx] else 0.0
            atan2(hilbertVal, signal[i])
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun windowCount(totalSamples: Int): Int {
        if (totalSamples < WINDOW_SAMPLES) return 0
        return (totalSamples - WINDOW_SAMPLES) / STEP_SAMPLES + 1
    }

    /** Slice the reference wave to match the RR window indices, padding with zeros if needed. */
    private fun refWaveSlice(refWave: FloatArray, start: Int, end: Int): FloatArray {
        return FloatArray(end - start) { i ->
            val idx = start + i
            if (idx < refWave.size) refWave[idx] else 0f
        }
    }

    /** Normalize a float array to [0, 1]. Returns zeros if range is zero. */
    private fun normalizeToUnit(arr: FloatArray): FloatArray {
        val min = arr.min()
        val max = arr.max()
        val range = max - min
        return if (range == 0f) FloatArray(arr.size) { 0f }
        else FloatArray(arr.size) { (arr[it] - min) / range }
    }

    private fun invalidResult(reason: String) = SlidingWindowResult(
        resonanceFrequencyBpm = 0f,
        peakResonanceIndex = 0f,
        peakPlv = 0f,
        isValid = false,
        invalidReason = reason,
        timeGrid = FloatArray(0),
        lfPowerSeries = FloatArray(0),
        ptAmpSeries = FloatArray(0),
        plvSeries = FloatArray(0),
        pacingBpmSeries = FloatArray(0)
    )

    private fun Float.format2(): String = "%.2f".format(this)
}

// =============================================================================
// Result model
// =============================================================================

/**
 * Output of [SlidingWindowAnalytics.analyze].
 *
 * All time-series arrays are the same length and aligned to sliding window centers.
 */
data class SlidingWindowResult(
    /** Detected optimal pacing rate in BPM (at peak resonance index). */
    val resonanceFrequencyBpm: Float,
    /** Composite resonance score at peak window, range 0–1. */
    val peakResonanceIndex: Float,
    /** Hilbert PLV at peak window, range 0–1. */
    val peakPlv: Float,
    /** True if both quality gates pass. */
    val isValid: Boolean,
    /** Human-readable reason when [isValid] is false; null otherwise. */
    val invalidReason: String?,
    // --- Time-series for plotting ---
    /** Center time of each window in seconds. */
    val timeGrid: FloatArray,
    /** Normalized LF power (LF / total) per window. */
    val lfPowerSeries: FloatArray,
    /** Peak-to-trough RR amplitude in ms per window. */
    val ptAmpSeries: FloatArray,
    /** Hilbert PLV per window. */
    val plvSeries: FloatArray,
    /** Instantaneous pacing BPM at each window center. */
    val pacingBpmSeries: FloatArray
)
