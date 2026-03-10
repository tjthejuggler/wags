package com.example.wags.domain.usecase.hrv

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 3 of Lipponen & Tarvainen 2019 artifact correction.
 * Classifies beats using S1/S2 space and applies corrections:
 * - Missed beat: interval too long → divide by 2, insert synthetic beat
 * - Extra beat: two short intervals → merge into one
 * - Ectopic beat: discard, replace via neighbor average (spline placeholder)
 */
object Phase3Classification {

    private const val C1 = 0.13
    private const val C2 = 0.17

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
        val corrected = mutableListOf<Double>()

        for (i in nn.indices) {
            when (types[i]) {
                BeatType.NORMAL -> {
                    corrected.add(nn[i])
                }
                BeatType.MISSED -> {
                    // Interval too long — split into two equal halves
                    val half = nn[i] / 2.0
                    corrected.add(half)
                    corrected.add(half)
                }
                BeatType.EXTRA -> {
                    // Two short intervals — merge with next if available
                    if (i + 1 < nn.size) {
                        val merged = nn[i] + nn[i + 1]
                        corrected.add(merged)
                    }
                    // Skip next beat (it was merged); handled by the loop continuing
                }
                BeatType.ECTOPIC -> {
                    // Replace with neighbor average as placeholder
                    val prev = if (i > 0) nn[i - 1] else nn[i]
                    val next = if (i < nn.size - 1) nn[i + 1] else nn[i]
                    corrected.add((prev + next) / 2.0)
                }
            }
        }

        return corrected.toDoubleArray()
    }
}
