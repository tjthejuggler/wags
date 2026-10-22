package com.example.wags.domain.usecase.hrv

import javax.inject.Inject

/**
 * Orchestrates the full artifact correction pipeline (v2, 2026-10-22):
 * 0. MissedBeatReconstructor — split merged intervals (dropped beats) instead
 *    of letting them be deleted downstream (the root cause of the
 *    RMSSD≈200ms / pNN50≈25% divergence seen in real captures)
 * 1. RrPreFilter (two-stage gate: absolute bounds + rolling median)
 * 2. Phase1DifferenceSeries (Lipponen & Tarvainen 2019)
 * 3. Phase2MedianComparison (Lipponen & Tarvainen 2019)
 * 4. Phase3Classification + correction
 * 5. Final quotient filter (safety net): any residual successive pair whose
 *    ratio falls outside [0.8, 1.2] is masked so TimeDomainHrvCalculator
 *    excludes that difference from RMSSD/pNN50 rather than squaring a
 *    sensor glitch into the metrics.
 *
 * Returns corrected NN intervals in milliseconds AND an artifact mask.
 *
 * IMPORTANT: artifactMask marks beats whose LEFT-side difference is suspect;
 * TimeDomainHrvCalculator drops diffs where either beat is masked.
 */
class ArtifactCorrectionUseCase @Inject constructor() {

    data class Result(
        val correctedNn: DoubleArray,
        val artifactMask: BooleanArray,  // same size as correctedNn; true = suspect beat
        val artifactCount: Int,
        /** Raw intervals fed into the pipeline (before any correction). */
        val rawCount: Int,
        /** Valid beats surviving after correction — used for coverage checks. */
        val validCount: Int
    ) {
        /** Fraction of raw beats that survived as valid (0..1). */
        val validRatio: Double get() = if (rawCount > 0) validCount.toDouble() / rawCount else 0.0
    }

    companion object {
        /** Metrics-pipeline version written alongside new readings. */
        const val METRICS_VERSION = 2

        /** Residual successive-pair ratio bounds for the final safety net. */
        private const val QUOTIENT_MIN = 0.80
        private const val QUOTIENT_MAX = 1.20
    }

    fun execute(rawRrMs: List<Double>): Result {
        if (rawRrMs.size < 10) {
            return Result(
                correctedNn = rawRrMs.toDoubleArray(),
                artifactMask = BooleanArray(rawRrMs.size),
                artifactCount = 0,
                rawCount = rawRrMs.size,
                validCount = rawRrMs.size
            )
        }

        // Stage 0: reconstruct merged (dropped-beat) intervals
        val reconstructed = MissedBeatReconstructor.reconstruct(rawRrMs)

        // Stage 1: Pre-filter (absolute bounds + rolling median)
        val preFiltered = RrPreFilter.filter(reconstructed.rr)
        val preFilterMask = preFiltered.map { it.second }.toBooleanArray()
        val validRr = preFiltered.filter { !it.second }.map { it.first }

        if (validRr.size < 10) {
            return Result(
                correctedNn = validRr.toDoubleArray(),
                artifactMask = BooleanArray(validRr.size),
                artifactCount = preFilterMask.count { it } + reconstructed.reconstructedBeats,
                rawCount = rawRrMs.size,
                validCount = validRr.size
            )
        }

        val nn = validRr.toDoubleArray()

        // Stage 2: Lipponen Phase 1 — normalized difference series
        val phase1 = Phase1DifferenceSeries.compute(nn)

        // Stage 3: Lipponen Phase 2 — normalized median comparison
        val phase2 = Phase2MedianComparison.compute(nn, phase1.dRRs)

        // Stage 4: Lipponen Phase 3 — classify and correct
        val types = Phase3Classification.classify(phase1.dRR, phase2.mRR)
        val corrected = Phase3Classification.correct(nn, types)

        val lipponenArtifactCount = types.count { it != Phase3Classification.BeatType.NORMAL }
        val totalArtifacts = preFilterMask.count { it } +
                lipponenArtifactCount + reconstructed.reconstructedBeats

        // Stage 5: final quotient filter — mask any residual suspect pair so
        // its difference is excluded from RMSSD/pNN50 (rather than deleted,
        // which would splice non-adjacent beats — the original flaw).
        val mask = BooleanArray(corrected.size)
        var maskedCount = 0
        for (i in 1 until corrected.size) {
            if (mask[i] || mask[i - 1]) continue
            val prev = corrected[i - 1]
            val curr = corrected[i]
            if (prev > 0 && curr > 0) {
                val ratio = curr / prev
                if (ratio < QUOTIENT_MIN || ratio > QUOTIENT_MAX) {
                    mask[i] = true
                    mask[i - 1] = true
                    maskedCount++
                }
            }
        }

        val validCount = mask.size - mask.count { it }

        return Result(corrected, mask, totalArtifacts + maskedCount, rawRrMs.size, validCount)
    }
}
