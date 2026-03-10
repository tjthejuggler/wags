package com.example.wags.domain.usecase.readiness

import com.example.wags.domain.model.RrInterval
import javax.inject.Inject

/**
 * Estimates respiratory rate from RR interval amplitude modulations
 * (Respiratory Sinus Arrhythmia — RSA).
 *
 * Counts local maxima (peaks) in the RR series and divides by recording
 * duration in minutes. If the estimated rate is < 9 bpm (below the 0.15 Hz
 * lower bound of the HF band), [Result.slowBreathingFlagged] is set to true.
 */
class RespiratoryRateCalculator @Inject constructor() {

    data class Result(
        val respiratoryRateBpm: Float,
        val slowBreathingFlagged: Boolean
    )

    /**
     * Estimates respiratory rate from RR interval amplitude modulations.
     * Returns null if fewer than 30 valid RR intervals are available.
     *
     * @param rrIntervals RR intervals (artifacts are excluded automatically)
     * @return [Result] with estimated rate and slow-breathing flag, or null
     */
    fun calculate(rrIntervals: List<RrInterval>): Result? {
        val valid = rrIntervals.filter { !it.isArtifact && it.intervalMs > 0 }
        if (valid.size < 30) return null

        val values = valid.map { it.intervalMs }

        // Count local maxima (peaks) in the RR series
        var peakCount = 0
        for (i in 1 until values.size - 1) {
            if (values[i] > values[i - 1] && values[i] > values[i + 1]) {
                peakCount++
            }
        }

        val durationMs = valid.last().timestampMs - valid.first().timestampMs
        if (durationMs <= 0) return null
        val durationMinutes = durationMs / 60_000.0f

        val respiratoryRate = peakCount / durationMinutes
        val slowBreathingFlagged = respiratoryRate < 9.0f

        return Result(
            respiratoryRateBpm = respiratoryRate,
            slowBreathingFlagged = slowBreathingFlagged
        )
    }
}
