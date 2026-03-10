package com.example.wags.domain.usecase.hrv

import javax.inject.Inject

/**
 * PCHIP (Piecewise Cubic Hermite Interpolating Polynomial) resampler.
 * Monotonic interpolation — does not overshoot between data points.
 * Prevents negative RR values or wild oscillations that corrupt FFT.
 *
 * Resamples NN intervals to a uniform 4 Hz grid for frequency domain analysis.
 * Output is clamped to [333ms, 1500ms] as a safety net.
 */
class PchipResampler @Inject constructor() {

    companion object {
        const val RESAMPLE_RATE_HZ = 4.0
        const val MIN_RR_MS = 333.0
        const val MAX_RR_MS = 1500.0
    }

    /**
     * Resample NN intervals to uniform 4 Hz grid.
     * @param nn NN intervals in milliseconds
     * @return uniformly sampled array at 4 Hz
     */
    fun resample(nn: DoubleArray): DoubleArray {
        if (nn.size < 4) return nn

        // Build cumulative time axis (in seconds)
        val timeAxis = DoubleArray(nn.size)
        timeAxis[0] = 0.0
        for (i in 1 until nn.size) {
            timeAxis[i] = timeAxis[i - 1] + nn[i - 1] / 1000.0
        }

        // Remove duplicate timestamps
        val uniquePairs = mutableListOf<Pair<Double, Double>>()
        for (i in nn.indices) {
            if (i == 0 || timeAxis[i] > uniquePairs.last().first) {
                uniquePairs.add(Pair(timeAxis[i], nn[i]))
            }
        }

        if (uniquePairs.size < 4) return nn

        val x = uniquePairs.map { it.first }.toDoubleArray()
        val y = uniquePairs.map { it.second }.toDoubleArray()

        // Compute PCHIP derivatives
        val d = computePchipDerivatives(x, y)

        // Evaluate at uniform 4 Hz grid
        val totalDuration = x.last()
        val numSamples = (totalDuration * RESAMPLE_RATE_HZ).toInt()
        if (numSamples < 2) return nn

        val result = DoubleArray(numSamples)
        for (k in 0 until numSamples) {
            val t = k / RESAMPLE_RATE_HZ
            result[k] = evaluatePchip(x, y, d, t).coerceIn(MIN_RR_MS, MAX_RR_MS)
        }
        return result
    }

    private fun computePchipDerivatives(x: DoubleArray, y: DoubleArray): DoubleArray {
        val n = x.size
        val d = DoubleArray(n)
        val h = DoubleArray(n - 1) { x[it + 1] - x[it] }
        val delta = DoubleArray(n - 1) { (y[it + 1] - y[it]) / h[it] }

        // Endpoint derivatives (one-sided)
        d[0] = delta[0]
        d[n - 1] = delta[n - 2]

        // Interior derivatives — monotone cubic
        for (k in 1 until n - 1) {
            if (delta[k - 1] * delta[k] <= 0.0) {
                d[k] = 0.0  // local extremum — zero derivative for monotonicity
            } else {
                val w1 = 2.0 * h[k] + h[k - 1]
                val w2 = h[k] + 2.0 * h[k - 1]
                d[k] = (w1 + w2) / (w1 / delta[k - 1] + w2 / delta[k])
            }
        }
        return d
    }

    private fun evaluatePchip(x: DoubleArray, y: DoubleArray, d: DoubleArray, t: Double): Double {
        // Clamp t to valid range
        if (t <= x.first()) return y.first()
        if (t >= x.last()) return y.last()

        // Binary search for interval
        var lo = 0
        var hi = x.size - 2
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (x[mid + 1] < t) lo = mid + 1 else hi = mid
        }
        val i = lo
        val h = x[i + 1] - x[i]
        val s = (t - x[i]) / h
        val s2 = s * s
        val s3 = s2 * s

        // Hermite basis functions
        val h00 = 2 * s3 - 3 * s2 + 1
        val h10 = s3 - 2 * s2 + s
        val h01 = -2 * s3 + 3 * s2
        val h11 = s3 - s2

        return h00 * y[i] + h10 * h * d[i] + h01 * y[i + 1] + h11 * h * d[i + 1]
    }
}
