package com.example.wags.domain.usecase.breathing

import kotlin.math.PI
import kotlin.math.cos

/**
 * Analytically-integrated chirp pacer for the Sliding Window RF protocol.
 * Sweeps breathing rate from 6.75 BPM down to ~4.5 BPM over 78 breath cycles (~16 min).
 *
 * Port of the desktop ContinuousPacer class.
 *
 * Math:
 *   T(n) = 60/startBpm + n * deltaT          (period of breath n)
 *   t(n) = sum of T(0)..T(n-1)               (cumulative start time of breath n)
 *   phase = (elapsed - t(n)) / T(n) * 2π     (phase within current breath)
 *   refWave = (1 - cos(phase)) / 2           (0=exhale trough, 1=inhale peak)
 */
object SlidingWindowPacerEngine {

    const val TOTAL_BREATHS = 78
    const val START_BPM = 6.75f
    const val DELTA_T = 0.06704f  // seconds added to period per breath

    /**
     * Result of evaluating the pacer at a given elapsed time.
     *
     * @param instantBpm           Current breathing rate in BPM
     * @param phaseRadians         Current phase in radians (0..2π per breath cycle)
     * @param refWave              Reference sine wave value in [0, 1] (0=exhale trough, 1=inhale peak)
     * @param breathIndex          Which breath cycle we are in (0-based)
     * @param totalElapsedSeconds  Total session duration in seconds (for progress display)
     */
    data class PacerState(
        val instantBpm: Float,
        val phaseRadians: Float,
        val refWave: Float,
        val breathIndex: Int,
        val totalElapsedSeconds: Float
    )

    /** Period of breath n in seconds. */
    private fun periodOf(n: Int): Float = 60f / START_BPM + n * DELTA_T

    /**
     * Cumulative start times: cumulativeTimes[n] = sum of T(0)..T(n-1).
     * cumulativeTimes[0] = 0.0, cumulativeTimes[TOTAL_BREATHS] = totalDurationSeconds.
     * Size = TOTAL_BREATHS + 1 for easy boundary checks.
     */
    private val cumulativeTimes: FloatArray by lazy {
        FloatArray(TOTAL_BREATHS + 1).also { arr ->
            arr[0] = 0f
            for (n in 0 until TOTAL_BREATHS) {
                arr[n + 1] = arr[n] + periodOf(n)
            }
        }
    }

    /** Total session duration in seconds (sum of all breath periods). */
    val totalDurationSeconds: Float by lazy { cumulativeTimes[TOTAL_BREATHS] }

    /**
     * Evaluate the pacer at [elapsedSeconds] into the session.
     * Returns null if the session is complete (elapsed >= totalDurationSeconds).
     */
    fun evaluate(elapsedSeconds: Float): PacerState? {
        if (elapsedSeconds >= totalDurationSeconds) return null

        val breathIndex = findBreathIndex(elapsedSeconds)
        val breathStart = cumulativeTimes[breathIndex]
        val period = periodOf(breathIndex)

        val phaseRadians = ((elapsedSeconds - breathStart) / period * 2f * PI).toFloat()
        val refWave = (1f - cos(phaseRadians)) / 2f
        val instantBpm = 60f / period

        return PacerState(
            instantBpm = instantBpm,
            phaseRadians = phaseRadians,
            refWave = refWave,
            breathIndex = breathIndex,
            totalElapsedSeconds = elapsedSeconds
        )
    }

    /**
     * Binary search the cumulative time array to find which breath [elapsedSeconds] falls in.
     * Returns the 0-based breath index such that cumulativeTimes[n] <= elapsed < cumulativeTimes[n+1].
     */
    private fun findBreathIndex(elapsedSeconds: Float): Int {
        var lo = 0
        var hi = TOTAL_BREATHS - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeTimes[mid] <= elapsedSeconds) lo = mid else hi = mid - 1
        }
        return lo
    }
}
