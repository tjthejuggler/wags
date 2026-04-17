package com.example.wags.domain.usecase.apnea.forecast

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Standard-normal CDF approximation (Abramowitz & Stegun 26.2.17).
 *
 * Accuracy: |ε| < 7.5 × 10⁻⁸ for all z.
 * Sub-microsecond per call; no external dependencies.
 */
object NormalCdf {

    /** Coefficients for the rational approximation. */
    private const val A1 =  0.254829592
    private const val A2 = -0.284496736
    private const val A3 =  1.421413741
    private const val A4 = -1.453152027
    private const val A5 =  1.061405429
    private const val P  =  0.3275911

    /**
     * Returns Φ(z) = P(Z ≤ z) for Z ~ Normal(0,1).
     */
    fun cdf(z: Double): Double {
        val sign = if (z < 0) -1 else 1
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + P * x)
        val y = 1.0 - (((((A5 * t + A4) * t) + A3) * t + A2) * t + A1) * t * exp(-x * x)
        return 0.5 * (1.0 + sign * y)
    }

    /**
     * Returns P(Z > z) = 1 − Φ(z), the upper-tail probability.
     */
    fun upperTail(z: Double): Double = 1.0 - cdf(z)
}
