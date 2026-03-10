package com.example.wags.domain.usecase.hrv

import com.example.wags.domain.model.HrvMetrics
import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Computes time-domain HRV metrics from corrected NN intervals.
 *
 * Artifact-aware RMSSD: computes successive differences on the full array
 * but drops any difference where either beat is flagged as artifact.
 * This avoids the "missing beat" flaw where deleting an artifact pairs
 * two non-adjacent beats.
 *
 * Per Task Force 1996:
 * - RMSSD uses all valid successive differences
 * - SDNN uses population std dev of all valid beats
 * - pNN50 counts |diff| > 50ms pairs
 */
class TimeDomainHrvCalculator @Inject constructor() {

    fun calculate(
        nn: DoubleArray,
        artifactMask: BooleanArray = BooleanArray(nn.size)
    ): HrvMetrics {
        require(nn.size >= 2) { "Need at least 2 NN intervals" }
        require(nn.size == artifactMask.size) { "nn and artifactMask must have same size" }

        // Artifact-aware successive differences
        val validDiffs = mutableListOf<Double>()
        var nn50Count = 0

        for (i in 1 until nn.size) {
            if (artifactMask[i] || artifactMask[i - 1]) continue
            val diff = nn[i] - nn[i - 1]
            validDiffs.add(diff)
            if (Math.abs(diff) > 50.0) nn50Count++
        }

        val rmssd = if (validDiffs.isEmpty()) 0.0
        else sqrt(validDiffs.sumOf { it * it } / validDiffs.size)

        val lnRmssd = if (rmssd > 0) ln(rmssd) else 0.0

        // SDNN from valid beats only (population std dev per Task Force 1996)
        val validNn = nn.filterIndexed { i, _ -> !artifactMask[i] }
        val sdnn = if (validNn.size >= 2) {
            val mean = validNn.average()
            sqrt(validNn.sumOf { (it - mean) * (it - mean) } / (validNn.size - 1))
        } else 0.0

        val pnn50 = if (validDiffs.isNotEmpty()) {
            nn50Count.toDouble() / validDiffs.size * 100.0
        } else 0.0

        return HrvMetrics(
            rmssdMs = rmssd,
            lnRmssd = lnRmssd,
            sdnnMs = sdnn,
            pnn50Percent = pnn50,
            sampleCount = validNn.size
        )
    }
}
