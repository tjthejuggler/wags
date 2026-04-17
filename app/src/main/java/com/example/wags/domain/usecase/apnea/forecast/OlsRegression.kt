package com.example.wags.domain.usecase.apnea.forecast

import kotlin.math.max

/**
 * Ordinary Least Squares regression with ridge penalty.
 *
 * Solves β = (XᵀX + λI)⁻¹ Xᵀy  via Gaussian elimination.
 * Designed for small feature matrices (≤ ~20 columns).
 * Pure Kotlin, no external dependencies.
 */
object OlsRegression {

    /** Result of an OLS fit. */
    data class OlsFit(
        /** Coefficient vector (length = p). */
        val beta: DoubleArray,
        /** Residual variance σ² = RSS / (n − p). */
        val residualVariance: Double,
        /** (XᵀX + λI)⁻¹ — p×p matrix, used for prediction intervals. */
        val xtxInverse: Array<DoubleArray>,
        /** Degrees of freedom: n − p. */
        val dof: Int
    )

    /**
     * Fit an OLS regression.
     *
     * @param X  n×p design matrix (each row is one observation's feature vector).
     * @param y  n-vector of responses (log-seconds).
     * @param lambda  Ridge penalty (default 0.01). Prevents singularity when
     *                rare dummy levels have very few observations.
     * @return OlsFit, or null if the matrix is irrecoverably singular.
     */
    fun fit(
        X: Array<DoubleArray>,
        y: DoubleArray,
        lambda: Double = 0.01
    ): OlsFit? {
        val n = X.size
        if (n == 0) return null
        val p = X[0].size
        if (n < p) return null  // under-determined even with ridge

        // ── Compute XᵀX ──────────────────────────────────────────────────────
        val xtx = Array(p) { DoubleArray(p) }
        for (i in 0 until n) {
            for (j in 0 until p) {
                for (k in 0 until p) {
                    xtx[j][k] += X[i][j] * X[i][k]
                }
            }
        }

        // Add ridge penalty to diagonal
        for (j in 0 until p) {
            xtx[j][j] += lambda
        }

        // ── Compute Xᵀy ──────────────────────────────────────────────────────
        val xty = DoubleArray(p)
        for (i in 0 until n) {
            for (j in 0 until p) {
                xty[j] += X[i][j] * y[i]
            }
        }

        // ── Solve (XᵀX + λI) β = Xᵀy via Gaussian elimination ────────────────
        val xtxInv = invertMatrix(xtx) ?: return null
        val beta = DoubleArray(p)
        for (j in 0 until p) {
            for (k in 0 until p) {
                beta[j] += xtxInv[j][k] * xty[k]
            }
        }

        // ── Residual variance ─────────────────────────────────────────────────
        val dof = max(1, n - p)
        var rss = 0.0
        for (i in 0 until n) {
            var fitted = 0.0
            for (j in 0 until p) {
                fitted += X[i][j] * beta[j]
            }
            val residual = y[i] - fitted
            rss += residual * residual
        }
        val residualVariance = rss / dof

        return OlsFit(beta, residualVariance, xtxInv, dof)
    }

    /**
     * Predict the mean and prediction variance for a new observation.
     *
     * @param xRow  1×p feature vector for the new observation.
     * @param fit   Previously fitted OlsFit.
     * @return Pair(mean, predictionVariance) where predictionVariance = σ² + xᵀ(XᵀX)⁻¹x.
     */
    fun predict(xRow: DoubleArray, fit: OlsFit): Pair<Double, Double> {
        val p = xRow.size
        var mean = 0.0
        for (j in 0 until p) {
            mean += xRow[j] * fit.beta[j]
        }
        // Prediction variance: σ² + xᵀ (XᵀX)⁻¹ x
        var xtxContribution = 0.0
        for (j in 0 until p) {
            var sum = 0.0
            for (k in 0 until p) {
                sum += fit.xtxInverse[j][k] * xRow[k]
            }
            xtxContribution += xRow[j] * sum
        }
        val predVariance = fit.residualVariance + xtxContribution
        return Pair(mean, predVariance)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Matrix inversion via Gauss-Jordan elimination with partial pivoting
    // ─────────────────────────────────────────────────────────────────────────

    private fun invertMatrix(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        // Augment [A | I]
        val aug = Array(n) { i -> DoubleArray(2 * n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                aug[i][j] = a[i][j]
            }
            aug[i][n + i] = 1.0
        }

        // Forward elimination with partial pivoting
        for (col in 0 until n) {
            // Find pivot
            var maxRow = col
            var maxVal = kotlin.math.abs(aug[col][col])
            for (row in (col + 1) until n) {
                val v = kotlin.math.abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-12) return null  // singular

            // Swap rows
            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }

            // Scale pivot row
            val pivot = aug[col][col]
            for (j in 0 until 2 * n) aug[col][j] /= pivot

            // Eliminate column
            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row][col]
                for (j in 0 until 2 * n) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // Extract inverse
        return Array(n) { i -> DoubleArray(n) { j -> aug[i][n + j] } }
    }
}
