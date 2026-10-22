package com.example.wags.domain.usecase.hrv

/**
 * Stage 0 of the artifact pipeline: reconstructs missed beats BEFORE any
 * interval is discarded.
 *
 * Why this exists (2026-10-22 fix): when a sensor drops a beat (loose strap,
 * BLE packet loss), two ~1000 ms R-R intervals merge into one ~2000 ms
 * interval. The old pipeline simply deleted such intervals in RrPreFilter,
 * which spliced two non-adjacent beats together. The resulting ±1000 ms
 * successive difference was squared into RMSSD (rocketing it to ~200 ms)
 * while only counting as 1-2 transitions toward pNN50 — the mathematical
 * signature of RMSSD/pNN50 divergence.
 *
 * Instead of deleting, an interval that is an approximate integer multiple
 * (2x or 3x) of the rolling median of VALID beats is split into equal parts,
 * restoring the missing beats. Intervals that are merely noisy (not near an
 * integer multiple) pass through untouched for RrPreFilter to judge.
 */
object MissedBeatReconstructor {

    private const val MEDIAN_WINDOW = 9
    private const val SPLIT_2_LO = 1.65  // ratio window for "exactly one beat missed"
    private const val SPLIT_2_HI = 2.45
    private const val SPLIT_3_LO = 2.65  // ratio window for "two beats missed"
    private const val SPLIT_3_HI = 3.45

    /** Number of reconstructed (synthetic) beats, for telemetry/artifact counts. */
    data class Result(
        val rr: List<Double>,
        val reconstructedBeats: Int
    )

    fun reconstruct(intervals: List<Double>): Result {
        val out = mutableListOf<Double>()
        val validBeats = ArrayDeque<Double>()
        var reconstructed = 0

        for (rr in intervals) {
            // Prime the median window with physiologically plausible beats only.
            if (validBeats.size >= MEDIAN_WINDOW) {
                val window = validBeats.takeLast(MEDIAN_WINDOW).sorted()
                val median = window[MEDIAN_WINDOW / 2]
                if (median > 0) {
                    val ratio = rr / median
                    when {
                        ratio in SPLIT_2_LO..SPLIT_2_HI -> {
                            val half = rr / 2.0
                            out.add(half); out.add(half)
                            reconstructed++
                            validBeats.addLast(half)
                            trimWindow(validBeats)
                            continue
                        }
                        ratio in SPLIT_3_LO..SPLIT_3_HI -> {
                            val third = rr / 3.0
                            out.add(third); out.add(third); out.add(third)
                            reconstructed += 2
                            validBeats.addLast(third)
                            trimWindow(validBeats)
                            continue
                        }
                    }
                }
            }

            out.add(rr)
            // Only feed plausible beats into the median window.
            if (rr in 333.0..1500.0) {
                validBeats.addLast(rr)
                trimWindow(validBeats)
            }
        }

        return Result(out, reconstructed)
    }

    private fun trimWindow(window: ArrayDeque<Double>) {
        if (window.size > MEDIAN_WINDOW * 2) window.removeFirst()
    }
}
