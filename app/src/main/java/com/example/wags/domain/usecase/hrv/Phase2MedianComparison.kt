package com.example.wags.domain.usecase.hrv

/**
 * Phase 2 of Lipponen & Tarvainen 2019 artifact correction.
 * Computes normalized median comparison series mRR[j] = mRRs[j] / Th2[j]
 * where mRRs[j] = RR[j] - median(11-beat window centered on j).
 */
object Phase2MedianComparison {

    private const val MEDIAN_WINDOW = 11

    data class Result(
        val mRR: DoubleArray,
        val mRRs: DoubleArray,
        val th2: DoubleArray
    )

    fun compute(nn: DoubleArray, dRRs: DoubleArray): Result {
        val n = nn.size
        val mRRs = DoubleArray(n)
        val th2 = DoubleArray(n)
        val mRR = DoubleArray(n)
        val half = MEDIAN_WINDOW / 2

        for (j in nn.indices) {
            val start = maxOf(0, j - half)
            val end = minOf(n - 1, j + half)
            val window = nn.slice(start..end).sorted()
            val median = window[window.size / 2]
            mRRs[j] = nn[j] - median

            // Th2 uses same IQR approach as Th1 but on dRRs window
            val dWindow = dRRs.slice(start..end).sorted()
            val q1 = dWindow[(dWindow.size * 0.25).toInt()]
            val q3 = dWindow[(dWindow.size * 0.75).toInt()]
            val qd = (q3 - q1) / 2.0
            th2[j] = 5.2 * qd
            mRR[j] = if (th2[j] > 0) mRRs[j] / th2[j] else 0.0
        }

        return Result(mRR, mRRs, th2)
    }
}
