package com.example.wags.domain.usecase.readiness

import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.domain.model.ReadinessInterpretation
import com.example.wags.domain.model.ReadinessScore
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Computes a 1–100 readiness score from the current session's ln(RMSSD)
 * relative to a 14-day personal baseline using Z-score normalization.
 *
 * Z-score mapping to 1–100:
 *   Z in [-0.5, +0.5]  → 70–85  (Optimal zone)
 *   Z in [+0.5, +1.5]  → 85–100 (Elevated — high readiness)
 *   Z > +1.5            → 100    (Capped — possible overreaching signal)
 *   Z in [-1.0, -0.5]  → 55–70  (Reduced)
 *   Z in [-2.0, -1.0]  → 20–55  (Low)
 *   Z < -2.0            → 1      (Floor — overreaching / illness)
 */
class ReadinessScoreCalculator @Inject constructor() {

    fun calculate(
        currentLnRmssd: Float,
        baseline: List<DailyReadingEntity>
    ): ReadinessScore {
        if (baseline.size < 3) {
            // Not enough history — return neutral score
            return ReadinessScore(
                score = 70,
                zScore = 0f,
                interpretation = ReadinessInterpretation.OPTIMAL
            )
        }

        val history = baseline.map { it.lnRmssd.toDouble() }
        val mean = history.average()
        val stdDev = computeStdDev(history, mean)

        val zScore = if (stdDev > 0.0) {
            ((currentLnRmssd - mean) / stdDev).toFloat()
        } else {
            0f
        }

        val score = mapZToScore(zScore)
        val interpretation = interpretScore(zScore)

        return ReadinessScore(
            score = score,
            zScore = zScore,
            interpretation = interpretation
        )
    }

    private fun mapZToScore(z: Float): Int {
        val score = when {
            z > 1.5f  -> 100.0
            z > 0.5f  -> 85.0 + (z - 0.5f) / 1.0f * 15.0   // 85–100
            z >= -0.5f -> 70.0 + (z + 0.5f) / 1.0f * 15.0  // 70–85
            z >= -1.0f -> 55.0 + (z + 1.0f) / 0.5f * 15.0  // 55–70
            z >= -2.0f -> 20.0 + (z + 2.0f) / 1.0f * 35.0  // 20–55
            else       -> 1.0
        }
        return score.roundToInt().coerceIn(1, 100)
    }

    private fun interpretScore(z: Float): ReadinessInterpretation = when {
        z > 1.5f   -> ReadinessInterpretation.OVERREACHING
        z > 0.5f   -> ReadinessInterpretation.ELEVATED
        z >= -0.5f -> ReadinessInterpretation.OPTIMAL
        z >= -1.0f -> ReadinessInterpretation.REDUCED
        else       -> ReadinessInterpretation.LOW
    }

    private fun computeStdDev(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }
}
