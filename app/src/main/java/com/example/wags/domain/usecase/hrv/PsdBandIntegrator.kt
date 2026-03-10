package com.example.wags.domain.usecase.hrv

import com.example.wags.domain.model.FrequencyDomainMetrics
import javax.inject.Inject

/**
 * Integrates PSD over HRV frequency bands using the trapezoidal rule.
 *
 * Band definitions (Task Force 1996):
 * - LF: 0.04–0.15 Hz
 * - HF: 0.15–0.40 Hz
 *
 * Note: VLF (0.0033–0.04 Hz) requires >5 min recording.
 * In a 64s window, minimum resolvable frequency is ~0.0156 Hz.
 * Total power floor starts at 0.015 Hz (not 0.0033 Hz).
 */
class PsdBandIntegrator @Inject constructor() {

    companion object {
        const val TOTAL_POWER_FLOOR_HZ = 0.015
        const val LF_LOW_HZ = 0.04
        const val LF_HIGH_HZ = 0.15
        const val HF_LOW_HZ = 0.15
        const val HF_HIGH_HZ = 0.40
    }

    fun integrate(psd: DoubleArray, freqAxis: DoubleArray): FrequencyDomainMetrics {
        val totalPower = trapezoidalIntegral(psd, freqAxis, TOTAL_POWER_FLOOR_HZ, HF_HIGH_HZ)
        val lfPower = trapezoidalIntegral(psd, freqAxis, LF_LOW_HZ, LF_HIGH_HZ)
        val hfPower = trapezoidalIntegral(psd, freqAxis, HF_LOW_HZ, HF_HIGH_HZ)
        val lfHfRatio = if (hfPower > 0) lfPower / hfPower else 0.0

        val lfHfSum = lfPower + hfPower
        val lfNu = if (lfHfSum > 0) lfPower / lfHfSum * 100.0 else 0.0
        val hfNu = if (lfHfSum > 0) hfPower / lfHfSum * 100.0 else 0.0

        return FrequencyDomainMetrics(
            vlfPowerMs2 = 0.0,  // Not computable in 64s window
            lfPowerMs2 = lfPower,
            hfPowerMs2 = hfPower,
            lfHfRatio = lfHfRatio,
            lfNormalizedUnits = lfNu,
            hfNormalizedUnits = hfNu
        )
    }

    /**
     * Integrate PSD over [freqLow, freqHigh] using the trapezoidal rule.
     * Only includes frequency bins that fall within the specified band.
     */
    fun trapezoidalIntegral(
        psd: DoubleArray,
        freqAxis: DoubleArray,
        freqLow: Double,
        freqHigh: Double
    ): Double {
        var power = 0.0
        for (k in 0 until freqAxis.size - 1) {
            val f0 = freqAxis[k]
            val f1 = freqAxis[k + 1]
            // Only integrate bins within the band
            if (f1 < freqLow || f0 > freqHigh) continue
            val clampedF0 = maxOf(f0, freqLow)
            val clampedF1 = minOf(f1, freqHigh)
            val df = clampedF1 - clampedF0
            // Trapezoidal rule: area = (psd[k] + psd[k+1]) / 2 * df
            power += (psd[k] + psd[k + 1]) / 2.0 * df
        }
        return power
    }
}
