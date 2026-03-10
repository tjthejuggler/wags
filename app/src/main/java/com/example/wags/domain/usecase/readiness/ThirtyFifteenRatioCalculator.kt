package com.example.wags.domain.usecase.readiness

import com.example.wags.domain.model.RrInterval
import javax.inject.Inject

/**
 * Computes the 30:15 ratio from orthostatic stand RR intervals.
 *
 * The 30:15 ratio is a standard autonomic index derived from the orthostatic
 * response: the ratio of the longest RR interval around beat 30 (vagal rebound)
 * to the shortest RR interval around beat 15 (peak sympathetic response).
 */
class ThirtyFifteenRatioCalculator @Inject constructor() {

    /**
     * Computes the 30:15 ratio from orthostatic stand RR intervals.
     * Returns null if insufficient data (< 39 valid beats).
     *
     * @param standingRrIntervals RR intervals from the moment of standing
     * @return Triple of (shortestRrMs, longestRrMs, ratio) or null
     */
    fun calculate(standingRrIntervals: List<RrInterval>): Triple<Double, Double, Float>? {
        val validBeats = standingRrIntervals.filter { !it.isArtifact }
        if (validBeats.size < 39) return null

        // Beats are 1-indexed; convert to 0-indexed for list access
        // Shortest RR (max HR): beats 6–24 (indices 5–23)
        val shortestRr = validBeats.subList(5, 24)
            .minOfOrNull { it.intervalMs } ?: return null

        // Longest RR (vagal rebound): beats 21–39 (indices 20–38)
        val longestRr = validBeats.subList(20, 39)
            .maxOfOrNull { it.intervalMs } ?: return null

        if (shortestRr <= 0.0) return null

        val ratio = (longestRr / shortestRr).toFloat()
        return Triple(shortestRr, longestRr, ratio)
    }
}
