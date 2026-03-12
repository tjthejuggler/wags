package com.example.wags.domain.usecase.hrv

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 3 of Lipponen & Tarvainen 2019 artifact correction.
 * Classifies beats using S1/S2 space and applies corrections:
 * - Missed beat: interval too long → divide by 2, insert synthetic beat
 * - Extra beat: two short intervals → merge into one
 * - Ectopic beat: replace via cubic spline (fallback: neighbor average)
 */
object Phase3Classification {

    private const val C1 = 0.13
    private const val C2 = 0.17
    private const val SPLINE_CONTEXT = 5   // valid beats to gather on each side
    private const val MIN_SPLINE_POINTS = 4 // minimum points required to fit a spline

    enum class BeatType { NORMAL, MISSED, EXTRA, ECTOPIC }

    fun classify(dRR: DoubleArray, mRR: DoubleArray): Array<BeatType> {
        val n = dRR.size
        val types = Array(n) { BeatType.NORMAL }

        for (j in 1 until n - 1) {
            val s1_1 = dRR[j]
            val s1_2 = if (dRR[j] > 0) max(dRR[j - 1], dRR[j + 1])
                       else min(dRR[j - 1], dRR[j + 1])

            val inS1 = abs(s1_1) > C1 && abs(s1_2) > C1 && s1_1 * s1_2 < 0
            val inS2 = abs(mRR[j]) > C2

            types[j] = when {
                inS1 && inS2 -> BeatType.ECTOPIC
                inS1 && !inS2 -> if (dRR[j] > 0) BeatType.MISSED else BeatType.EXTRA
                else -> BeatType.NORMAL
            }
        }
        return types
    }

    fun correct(nn: DoubleArray, types: Array<BeatType>): DoubleArray {
        // Build cumulative time axis (x) for spline fitting
        val cumTime = DoubleArray(nn.size)
        for (i in 1 until nn.size) cumTime[i] = cumTime[i - 1] + nn[i - 1]

        val corrected = mutableListOf<Double>()
        var i = 0
        while (i < nn.size) {
            when (types[i]) {
                BeatType.NORMAL -> {
                    corrected.add(nn[i])
                    i++
                }

                BeatType.MISSED -> {
                    // Interval too long — split into two equal halves
                    val half = nn[i] / 2.0
                    corrected.add(half)
                    corrected.add(half)
                    i++
                }

                BeatType.EXTRA -> {
                    // Two short intervals — merge with next beat and skip it.
                    // Without explicitly advancing past i+1 the for-loop would
                    // visit it again and emit it a second time as NORMAL.
                    if (i + 1 < nn.size) {
                        corrected.add(nn[i] + nn[i + 1])
                        i += 2  // consume both beats
                    } else {
                        // Last beat with no partner — drop it (it's an extra)
                        i++
                    }
                }

                BeatType.ECTOPIC -> {
                    corrected.add(interpolateEctopic(nn, types, cumTime, i))
                    i++
                }
            }
        }

        return corrected.toDoubleArray()
    }

    /**
     * Replaces an ectopic beat using cubic spline interpolation over the
     * surrounding valid beats (up to [SPLINE_CONTEXT] on each side).
     * Falls back to neighbor average when fewer than [MIN_SPLINE_POINTS]
     * valid context beats are available.
     */
    private fun interpolateEctopic(
        nn: DoubleArray,
        types: Array<BeatType>,
        cumTime: DoubleArray,
        idx: Int
    ): Double {
        // Collect valid context indices before the artifact
        val before = mutableListOf<Int>()
        var k = idx - 1
        while (k >= 0 && before.size < SPLINE_CONTEXT) {
            if (types[k] != BeatType.ECTOPIC) before.add(0, k)
            k--
        }

        // Collect valid context indices after the artifact
        val after = mutableListOf<Int>()
        k = idx + 1
        while (k < nn.size && after.size < SPLINE_CONTEXT) {
            if (types[k] != BeatType.ECTOPIC) after.add(k)
            k++
        }

        val contextIndices = before + after
        if (contextIndices.size < MIN_SPLINE_POINTS) {
            // Fallback: neighbor average
            val prev = if (idx > 0) nn[idx - 1] else nn[idx]
            val next = if (idx < nn.size - 1) nn[idx + 1] else nn[idx]
            return (prev + next) / 2.0
        }

        // Build x/y arrays for the spline (cumulative time vs RR value)
        val xArr = DoubleArray(contextIndices.size) { cumTime[contextIndices[it]] }
        val yArr = DoubleArray(contextIndices.size) { nn[contextIndices[it]] }

        return try {
            val spline = SplineInterpolator().interpolate(xArr, yArr)
            val targetX = cumTime[idx]
            // Clamp to spline domain to avoid extrapolation exceptions
            val clampedX = targetX.coerceIn(xArr.first(), xArr.last())
            spline.value(clampedX)
        } catch (_: Exception) {
            // Any numerical failure → neighbor average fallback
            val prev = if (idx > 0) nn[idx - 1] else nn[idx]
            val next = if (idx < nn.size - 1) nn[idx + 1] else nn[idx]
            (prev + next) / 2.0
        }
    }
}
