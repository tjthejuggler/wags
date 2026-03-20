package com.example.wags.domain.usecase.readiness

import com.example.wags.domain.model.OrthostasisMetrics
import com.example.wags.domain.model.RrInterval
import javax.inject.Inject
import kotlin.math.abs

/**
 * Computes Orthostatic Heart Rate Recovery (OHRR) metrics.
 *
 * Measures how quickly HR drops after the initial sympathetic spike of standing,
 * at 20 s and 60 s post-peak. Also derives the 30:15 ratio and peak HR values.
 */
class OhrrCalculator @Inject constructor() {

    /**
     * Computes orthostatic HR recovery metrics.
     *
     * @param peakStandHr Absolute highest HR (bpm) during the stand
     * @param standingRrIntervals RR intervals from moment of standing (with timestamps)
     * @return [OrthostasisMetrics] with all computed values
     */
    fun calculate(
        peakStandHr: Int,
        standingRrIntervals: List<RrInterval>
    ): OrthostasisMetrics {
        // Reconstruct timestamps from RR interval durations if the original
        // timestamps look unreliable (e.g. all clustered within a few ms because
        // they were assigned at polling time rather than at actual beat time).
        // This is a safety net — the ViewModel should now assign proper timestamps,
        // but older data or edge cases may still have bad timestamps.
        val beats = ensureMonotonicTimestamps(standingRrIntervals)
        val validBeats = beats.filter { !it.isArtifact }

        // Peak beat: shortest RR in beats 6–24 (indices 5–23)
        val peakWindow = if (validBeats.size >= 24) validBeats.subList(5, 24) else validBeats
        val peakBeat = peakWindow.minByOrNull { it.intervalMs }

        val shortestRrMs = peakBeat?.intervalMs
        val peakIdx = if (peakBeat != null) validBeats.indexOf(peakBeat) else -1

        // Vagal rebound: longest RR in beats 21–39 (indices 20–38)
        val longestRrMs = if (validBeats.size >= 39) {
            validBeats.subList(20, 39).maxOfOrNull { it.intervalMs }
        } else null

        val thirtyFifteenRatio = if (shortestRrMs != null && longestRrMs != null && shortestRrMs > 0) {
            (longestRrMs / shortestRrMs).toFloat()
        } else null

        // HR at 20 s and 60 s post-peak using reconstructed timestamps
        val peakTimestampMs = if (peakIdx >= 0) validBeats[peakIdx].timestampMs else null
        val hrAt20s = peakTimestampMs?.let { findHrAtOffset(validBeats, it, offsetMs = 20_000L) }
        val hrAt60s = peakTimestampMs?.let { findHrAtOffset(validBeats, it, offsetMs = 60_000L) }

        val ohrrAt20s = hrAt20s?.let { hr ->
            (peakStandHr - hr).toFloat() / peakStandHr * 100f
        }
        val ohrrAt60s = hrAt60s?.let { hr ->
            (peakStandHr - hr).toFloat() / peakStandHr * 100f
        }

        return OrthostasisMetrics(
            peakStandHr = peakStandHr,
            shortestRrBeat15 = shortestRrMs,
            longestRrBeat30 = longestRrMs,
            thirtyFifteenRatio = thirtyFifteenRatio,
            hrAt20s = hrAt20s,
            hrAt60s = hrAt60s,
            ohrrAt20sPercent = ohrrAt20s,
            ohrrAt60sPercent = ohrrAt60s
        )
    }

    /**
     * Ensures timestamps are monotonically increasing and properly spaced.
     *
     * If the existing timestamps look unreliable (many beats share the same
     * timestamp, or timestamps are not monotonically increasing), we reconstruct
     * them by accumulating RR interval durations from the first beat's timestamp.
     *
     * Detection heuristic: if more than 30% of consecutive beat pairs have
     * identical timestamps (within 5 ms), the timestamps are considered unreliable.
     */
    private fun ensureMonotonicTimestamps(intervals: List<RrInterval>): List<RrInterval> {
        if (intervals.size < 2) return intervals

        // Check how many consecutive pairs have near-identical timestamps
        var duplicateCount = 0
        for (i in 1 until intervals.size) {
            if (abs(intervals[i].timestampMs - intervals[i - 1].timestampMs) < 5L) {
                duplicateCount++
            }
        }
        val duplicateRatio = duplicateCount.toFloat() / (intervals.size - 1)

        // If timestamps look reasonable, use them as-is
        if (duplicateRatio < 0.30f) return intervals

        // Reconstruct: use the first beat's timestamp as the anchor, then
        // accumulate each RR interval duration to get subsequent timestamps.
        val result = ArrayList<RrInterval>(intervals.size)
        var cumulativeTs = intervals[0].timestampMs
        result.add(intervals[0])
        for (i in 1 until intervals.size) {
            cumulativeTs += intervals[i].intervalMs.toLong()
            result.add(intervals[i].copy(timestampMs = cumulativeTs))
        }
        return result
    }

    /**
     * Finds the HR (bpm) at [offsetMs] milliseconds after [peakTimestampMs]
     * by locating the closest beat to the target timestamp.
     */
    private fun findHrAtOffset(
        beats: List<RrInterval>,
        peakTimestampMs: Long,
        offsetMs: Long
    ): Int? {
        val targetTs = peakTimestampMs + offsetMs
        val beat = beats
            .filter { it.timestampMs >= peakTimestampMs }
            .minByOrNull { abs(it.timestampMs - targetTs) }
            ?: return null
        // Reject if the closest beat is more than 5 seconds away from the target
        // (indicates insufficient data coverage at that time point)
        if (abs(beat.timestampMs - targetTs) > 5_000L) return null
        if (beat.intervalMs <= 0) return null
        return (60_000.0 / beat.intervalMs).toInt()
    }
}
