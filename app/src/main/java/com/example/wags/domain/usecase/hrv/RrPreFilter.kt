package com.example.wags.domain.usecase.hrv

/**
 * Two-stage RR pre-filter gate.
 * Stage 1: Absolute physiological bounds [333ms, 1500ms] (40–180 BPM)
 * Stage 2: Rolling median relative change — discard if |RR[i] - median(last 9 valid)| / median > 0.20
 *
 * Returns a list of Pair<Double, Boolean> where Boolean=true means isArtifact.
 * Uses rolling window of VALID beats only to prevent domino effect.
 */
object RrPreFilter {

    private const val MIN_RR_MS = 333.0   // 180 BPM
    private const val MAX_RR_MS = 1500.0  // 40 BPM
    private const val RELATIVE_CHANGE_THRESHOLD = 0.20
    private const val MEDIAN_WINDOW = 9

    fun filter(intervals: List<Double>): List<Pair<Double, Boolean>> {
        val result = mutableListOf<Pair<Double, Boolean>>()
        val validBeats = ArrayDeque<Double>()

        for (rr in intervals) {
            // Stage 1: absolute bounds
            if (rr < MIN_RR_MS || rr > MAX_RR_MS) {
                result.add(Pair(rr, true))  // true = is artifact
                continue
            }

            // Stage 2: rolling median relative change
            if (validBeats.size >= MEDIAN_WINDOW) {
                val window = validBeats.takeLast(MEDIAN_WINDOW).sorted()
                val median = window[MEDIAN_WINDOW / 2]
                val relativeChange = Math.abs(rr - median) / median
                if (relativeChange > RELATIVE_CHANGE_THRESHOLD) {
                    result.add(Pair(rr, true))
                    continue
                }
            }

            result.add(Pair(rr, false))
            validBeats.addLast(rr)
            if (validBeats.size > MEDIAN_WINDOW * 2) validBeats.removeFirst()
        }

        return result
    }
}
