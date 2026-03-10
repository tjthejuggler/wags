package com.example.wags.domain.usecase.hrv

/**
 * Phase 1 of Lipponen & Tarvainen 2019 artifact correction.
 * Computes normalized difference series dRR[j] = dRRs[j] / Th1[j]
 * where Th1 is based on the interquartile range over a 91-beat sliding window.
 */
object Phase1DifferenceSeries {

    private const val WINDOW_SIZE = 91

    data class Result(
        val dRR: DoubleArray,   // normalized difference series
        val dRRs: DoubleArray,  // raw difference series
        val th1: DoubleArray    // thresholds
    )

    fun compute(nn: DoubleArray): Result {
        val n = nn.size
        val dRRs = DoubleArray(n) { if (it == 0) 0.0 else nn[it] - nn[it - 1] }
        val th1 = DoubleArray(n)
        val dRR = DoubleArray(n)

        for (j in nn.indices) {
            val half = WINDOW_SIZE / 2
            val start = maxOf(0, j - half)
            val end = minOf(n - 1, j + half)
            val window = dRRs.slice(start..end).sorted()
            val q1 = window[(window.size * 0.25).toInt()]
            val q3 = window[(window.size * 0.75).toInt()]
            val qd = (q3 - q1) / 2.0
            th1[j] = 5.2 * qd
            dRR[j] = if (th1[j] > 0) dRRs[j] / th1[j] else 0.0
        }

        return Result(dRR, dRRs, th1)
    }
}
