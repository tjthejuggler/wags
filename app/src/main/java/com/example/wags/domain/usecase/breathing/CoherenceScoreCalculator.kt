package com.example.wags.domain.usecase.breathing

import com.example.wags.domain.usecase.hrv.FftProcessor
import com.example.wags.domain.usecase.hrv.PchipResampler
import com.example.wags.domain.usecase.hrv.PsdBandIntegrator
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Computes resonance frequency coherence score from RR intervals.
 *
 * Two modes:
 * 1. Time-domain (default): amplitude-based RSA swing per breath cycle
 * 2. FFT precision mode: peak power ratio at target breathing frequency
 *
 * Also provides:
 * - Phase synchrony (cross-correlation between IHR and respiratory reference)
 * - PT amplitude (peak-to-trough HR per breath cycle)
 *
 * Coherence normalization (from hrvm reference):
 *   ratio = band_power / max(total_power - band_power, 1e-9)
 *   score = tanh(ratio × 2.0) × 100.0
 *
 * Target band: ±0.03 Hz around the target breathing frequency.
 */
class CoherenceScoreCalculator @Inject constructor(
    private val resampler: PchipResampler,
    private val fftProcessor: FftProcessor,
    private val bandIntegrator: PsdBandIntegrator
) {

    companion object {
        private const val BAND_HALF_WIDTH_HZ = 0.03
        private const val EPSILON = 1e-9
        private const val TANH_SCALE = 2.0
    }

    /**
     * Time-domain coherence: mean RSA amplitude over last N breath cycles,
     * penalized by variability (σ).
     *
     * @param breathAmplitudesBpm list of peak-to-trough HR amplitudes per cycle (BPM)
     * @param w variability penalty weight (default 0.5)
     * @return coherence score 0–100
     */
    fun calculateTimeDomain(
        breathAmplitudesBpm: List<Double>,
        w: Double = 0.5
    ): Float {
        if (breathAmplitudesBpm.isEmpty()) return 0f
        val mean = breathAmplitudesBpm.average()
        val stdDev = if (breathAmplitudesBpm.size > 1) {
            val variance = breathAmplitudesBpm.sumOf { (it - mean) * (it - mean) } /
                    (breathAmplitudesBpm.size - 1)
            kotlin.math.sqrt(variance)
        } else 0.0
        val raw = (mean - w * stdDev).coerceAtLeast(0.0)
        // Normalize: assume 20 BPM amplitude = 100 score
        return (raw / 20.0 * 100.0).coerceIn(0.0, 100.0).toFloat()
    }

    /**
     * FFT precision coherence: tanh-normalized peak power ratio at target frequency.
     *
     * @param nn corrected NN intervals in ms
     * @param targetFreqHz target breathing frequency in Hz (e.g. 0.1 for 6 BPM)
     * @return coherence score 0–100
     */
    fun calculateFft(nn: DoubleArray, targetFreqHz: Double): Float {
        if (nn.size < 16) return 0f

        val resampled = resampler.resample(nn)
        if (resampled.size < 8) return 0f

        val fftResult = fftProcessor.process(resampled)
        val psd = fftResult.psd
        val freqAxis = fftResult.freqAxis

        val bandLow = targetFreqHz - BAND_HALF_WIDTH_HZ
        val bandHigh = targetFreqHz + BAND_HALF_WIDTH_HZ

        val bandPower = bandIntegrator.trapezoidalIntegral(psd, freqAxis, bandLow, bandHigh)
        val totalPower = bandIntegrator.trapezoidalIntegral(
            psd, freqAxis,
            PsdBandIntegrator.TOTAL_POWER_FLOOR_HZ,
            PsdBandIntegrator.HF_HIGH_HZ
        )

        val ratio = bandPower / max(totalPower - bandPower, EPSILON)
        val score = tanh(ratio * TANH_SCALE) * 100.0
        return score.coerceIn(0.0, 100.0).toFloat()
    }

    /**
     * Phase synchrony via cross-correlation between IHR and respiratory reference.
     * Returns 0.0 (no sync) to 1.0 (perfect sync).
     *
     * @param ihrBpm instantaneous HR in BPM at 4 Hz (from PCHIP-resampled NN)
     * @param breathRefWave synthetic respiratory reference wave at same sample rate
     * @param cycleDurationSec duration of one breath cycle in seconds
     */
    fun calculatePhaseSynchrony(
        ihrBpm: DoubleArray,
        breathRefWave: DoubleArray,
        cycleDurationSec: Double
    ): Float {
        if (ihrBpm.size < 4 || breathRefWave.size < 4) return 0f
        val n = min(ihrBpm.size, breathRefWave.size)

        // Normalize both signals
        val ihrNorm = normalize(ihrBpm.take(n).toDoubleArray())
        val refNorm = normalize(breathRefWave.take(n).toDoubleArray())

        // Cross-correlate: find lag that maximizes correlation
        val maxLagSamples = (cycleDurationSec * PchipResampler.RESAMPLE_RATE_HZ / 2).toInt()
        var bestCorr = Double.MIN_VALUE
        var bestLag = 0

        for (lag in -maxLagSamples..maxLagSamples) {
            var corr = 0.0
            var count = 0
            for (i in 0 until n) {
                val j = i + lag
                if (j < 0 || j >= n) continue
                corr += ihrNorm[i] * refNorm[j]
                count++
            }
            if (count > 0) corr /= count
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        val bestLagSec = abs(bestLag) / PchipResampler.RESAMPLE_RATE_HZ
        val halfCycle = cycleDurationSec / 2.0
        val synchrony = (1.0 - (bestLagSec / halfCycle)).coerceIn(0.0, 1.0)
        return synchrony.toFloat()
    }

    /**
     * Peak-to-trough HR amplitude per breath cycle.
     * Primary RSA indicator for resonance frequency assessment.
     *
     * @param ihrBpm instantaneous HR in BPM at 4 Hz
     * @param breathCycleStartIndices sample indices where each breath cycle starts
     * @return mean PT amplitude in BPM
     */
    fun calculatePtAmplitude(
        ihrBpm: DoubleArray,
        breathCycleStartIndices: List<Int>
    ): Float {
        if (breathCycleStartIndices.size < 2) return 0f
        val amplitudes = mutableListOf<Double>()

        for (i in 0 until breathCycleStartIndices.size - 1) {
            val start = breathCycleStartIndices[i]
            val end = breathCycleStartIndices[i + 1]
            if (start >= ihrBpm.size || end > ihrBpm.size || start >= end) continue
            val cycleHr = ihrBpm.slice(start until end)
            if (cycleHr.isEmpty()) continue
            amplitudes.add(cycleHr.max() - cycleHr.min())
        }

        return if (amplitudes.isEmpty()) 0f else amplitudes.average().toFloat()
    }

    private fun normalize(data: DoubleArray): DoubleArray {
        val mean = data.average()
        val std = kotlin.math.sqrt(data.sumOf { (it - mean) * (it - mean) } / data.size)
        return if (std > 0) DoubleArray(data.size) { (data[it] - mean) / std }
        else DoubleArray(data.size) { 0.0 }
    }
}
