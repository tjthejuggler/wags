package com.example.wags.domain.usecase.hrv

import javax.inject.Inject

/**
 * Orchestrates the full artifact correction pipeline:
 * 1. RrPreFilter (two-stage gate: absolute bounds + rolling median)
 * 2. Phase1DifferenceSeries (Lipponen & Tarvainen 2019)
 * 3. Phase2MedianComparison (Lipponen & Tarvainen 2019)
 * 4. Phase3Classification + correction
 *
 * Returns corrected NN intervals in milliseconds AND an artifact mask.
 */
class ArtifactCorrectionUseCase @Inject constructor() {

    data class Result(
        val correctedNn: DoubleArray,
        val artifactMask: BooleanArray,  // true = was artifact at original index
        val artifactCount: Int
    )

    fun execute(rawRrMs: List<Double>): Result {
        if (rawRrMs.size < 10) {
            return Result(rawRrMs.toDoubleArray(), BooleanArray(rawRrMs.size), 0)
        }

        // Stage 1: Pre-filter (absolute bounds + rolling median)
        val preFiltered = RrPreFilter.filter(rawRrMs)
        val preFilterMask = preFiltered.map { it.second }.toBooleanArray()
        val validRr = preFiltered.filter { !it.second }.map { it.first }

        if (validRr.size < 10) {
            // Return correctedNn and artifactMask with matching sizes (both based on validRr)
            return Result(
                correctedNn = validRr.toDoubleArray(),
                artifactMask = BooleanArray(validRr.size),
                artifactCount = preFilterMask.count { it }
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
        val totalArtifacts = preFilterMask.count { it } + lipponenArtifactCount

        return Result(corrected, preFilterMask, totalArtifacts)
    }
}
