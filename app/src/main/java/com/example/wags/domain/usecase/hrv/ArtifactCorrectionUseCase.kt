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
 *
 * IMPORTANT: artifactMask is always sized to match correctedNn exactly.
 * Phase3 correction can change the array length (MISSED beats expand it,
 * EXTRA beats shrink it), so we cannot reuse the pre-filter mask — which
 * is sized to the raw input — as the final artifact mask. The corrected
 * array is already clean, so the returned mask is all-false with size
 * equal to correctedNn.size.
 */
class ArtifactCorrectionUseCase @Inject constructor() {

    data class Result(
        val correctedNn: DoubleArray,
        val artifactMask: BooleanArray,  // always same size as correctedNn; all-false (beats are clean)
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
            // validRr is already clean; mask matches its size
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

        // The corrected array may be a different length than nn (and therefore
        // a different length than preFilterMask) because Phase3 can insert or
        // remove beats. Build a fresh all-false mask sized to corrected.size so
        // that TimeDomainHrvCalculator's require(nn.size == artifactMask.size)
        // is always satisfied.
        val correctedMask = BooleanArray(corrected.size)

        return Result(corrected, correctedMask, totalArtifacts)
    }
}
