package com.example.wags.domain.usecase.breathing

import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.data.repository.ResonanceSessionRepository
import com.example.wags.data.repository.RfAssessmentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

// ── Result model ────────────────────────────────────────────────────────────────

/**
 * Full recommendation result with the chosen rate and all the data that went
 * into the decision, so the UI can render a transparent explanation page.
 */
data class RateRecommendation(
    /** The recommended breathing rate (BPM), or null if insufficient data. */
    val recommendedBpm: Float?,
    /** All rate buckets considered, sorted by final weighted score descending. */
    val buckets: List<RateBucket>,
    /** Total assessments used (after validity filter). */
    val assessmentCount: Int,
    /** Total sessions used (after duration filter). */
    val sessionCount: Int,
    /** Human-readable summary of the recommendation logic. */
    val summaryText: String,
    /** The lookback window in days that was used. */
    val lookbackDays: Int
)

/**
 * One rate bucket (0.1 BPM granularity) with its aggregated scores.
 */
data class RateBucket(
    /** The rate in BPM (rounded to 0.1). */
    val rateBpm: Float,
    /** Number of data points in this bucket. */
    val dataPointCount: Int,
    /** Simple average coherence across all data points. */
    val rawAvgCoherence: Float,
    /** Recency-weighted average coherence. */
    val weightedAvgCoherence: Float,
    /** Confidence multiplier (0..1) based on data count. */
    val confidenceMultiplier: Float,
    /** Final score = weightedAvgCoherence × confidenceMultiplier. */
    val finalScore: Float,
    /** Whether this bucket was chosen as the recommendation. */
    val isRecommended: Boolean,
    /** Individual data points that fed into this bucket. */
    val dataPoints: List<RateDataPoint>
)

/**
 * A single data point (from either an assessment or a session).
 */
data class RateDataPoint(
    val source: DataPointSource,
    val rateBpm: Float,
    val coherenceScore: Float,
    val timestamp: Long,
    /** Recency weight applied to this point (0..1, higher = more recent). */
    val recencyWeight: Float,
    /** Label for display, e.g. "Assessment" or "Session (5:23)". */
    val label: String
)

enum class DataPointSource { ASSESSMENT, SESSION }

// ── Use case ────────────────────────────────────────────────────────────────────

/**
 * Computes the optimal resonance breathing rate from the last [LOOKBACK_DAYS]
 * of RF assessment and resonance session data.
 *
 * **Algorithm:**
 * 1. Collect all valid assessments (isValid, optimalBpm 4–7) and sessions
 *    (duration ≥ 60 s, rate 4–7 BPM) from the last 60 days.
 * 2. For each data point compute a **recency weight** using exponential decay
 *    (half-life = 14 days). Recent data matters more.
 * 3. Group into 0.1 BPM buckets (round to nearest 0.1).
 * 4. For each bucket compute:
 *    - Raw average coherence
 *    - Recency-weighted average coherence
 *    - Confidence multiplier: min(dataPointCount / MIN_POINTS_FULL_CONFIDENCE, 1.0)
 *    - Final score = weightedAvgCoherence × confidenceMultiplier
 * 5. Pick the bucket with the highest final score.
 */
@Singleton
class ResonanceRateRecommender @Inject constructor(
    private val rfAssessmentRepo: RfAssessmentRepository,
    private val resonanceSessionRepo: ResonanceSessionRepository
) {
    companion object {
        const val LOOKBACK_DAYS = 60
        /** Exponential decay half-life in days. */
        const val HALF_LIFE_DAYS = 14.0
        /** Number of data points needed for full confidence (1.0 multiplier). */
        const val MIN_POINTS_FULL_CONFIDENCE = 3
    }

    /**
     * Compute the recommendation. This is a suspend function because it
     * reads from the database.
     */
    suspend fun recommend(): RateRecommendation {
        val nowMs = System.currentTimeMillis()
        val lookbackMs = nowMs - (LOOKBACK_DAYS.toLong() * 24 * 60 * 60 * 1000)

        val assessments = rfAssessmentRepo.getSince(lookbackMs)
        val sessions = resonanceSessionRepo.getSince(lookbackMs)

        val dataPoints = mutableListOf<RateDataPoint>()

        // ── Collect assessment data points ───────────────────────────────────
        var validAssessmentCount = 0
        assessments.forEach { a ->
            if (a.isValid && a.optimalBpm in 4f..7f) {
                validAssessmentCount++
                val ageDays = (nowMs - a.timestamp).toDouble() / (24 * 60 * 60 * 1000)
                val recencyWeight = recencyWeight(ageDays)
                dataPoints.add(
                    RateDataPoint(
                        source = DataPointSource.ASSESSMENT,
                        rateBpm = a.optimalBpm,
                        coherenceScore = a.maxCoherenceRatio,
                        timestamp = a.timestamp,
                        recencyWeight = recencyWeight,
                        label = "Assessment (score %.1f)".format(a.compositeScore)
                    )
                )
            }
        }

        // ── Collect session data points ─────────────────────────────────────
        var validSessionCount = 0
        sessions.forEach { s ->
            if (s.breathingRateBpm in 4f..7f && s.durationSeconds >= 60) {
                validSessionCount++
                val ageDays = (nowMs - s.timestamp).toDouble() / (24 * 60 * 60 * 1000)
                val recencyWeight = recencyWeight(ageDays)
                val durationLabel = "%d:%02d".format(s.durationSeconds / 60, s.durationSeconds % 60)
                dataPoints.add(
                    RateDataPoint(
                        source = DataPointSource.SESSION,
                        rateBpm = s.breathingRateBpm,
                        coherenceScore = s.meanCoherenceRatio,
                        timestamp = s.timestamp,
                        recencyWeight = recencyWeight,
                        label = "Session ($durationLabel)"
                    )
                )
            }
        }

        if (dataPoints.isEmpty()) {
            return RateRecommendation(
                recommendedBpm = null,
                buckets = emptyList(),
                assessmentCount = 0,
                sessionCount = 0,
                summaryText = "No assessment or session data found in the last $LOOKBACK_DAYS days. " +
                        "Run an RF Assessment to get a personalized recommendation.",
                lookbackDays = LOOKBACK_DAYS
            )
        }

        // ── Group into 0.1 BPM buckets (round to nearest) ──────────────────
        val grouped = dataPoints.groupBy { roundToTenth(it.rateBpm) }

        val buckets = grouped.map { (rate, points) ->
            val rawAvg = points.map { it.coherenceScore }.average().toFloat()
            val totalWeight = points.sumOf { it.recencyWeight.toDouble() }
            val weightedAvg = if (totalWeight > 0) {
                points.sumOf { it.coherenceScore.toDouble() * it.recencyWeight } / totalWeight
            } else {
                rawAvg.toDouble()
            }.toFloat()
            val confidence = (points.size.toFloat() / MIN_POINTS_FULL_CONFIDENCE).coerceAtMost(1f)
            val finalScore = weightedAvg * confidence

            RateBucket(
                rateBpm = rate,
                dataPointCount = points.size,
                rawAvgCoherence = rawAvg,
                weightedAvgCoherence = weightedAvg,
                confidenceMultiplier = confidence,
                finalScore = finalScore,
                isRecommended = false, // set below
                dataPoints = points.sortedByDescending { it.timestamp }
            )
        }.sortedByDescending { it.finalScore }

        val bestBucket = buckets.first()
        val markedBuckets = buckets.map {
            it.copy(isRecommended = it.rateBpm == bestBucket.rateBpm)
        }

        val summaryText = buildString {
            append("Analyzed ${dataPoints.size} data points ")
            append("($validAssessmentCount assessments, $validSessionCount sessions) ")
            append("from the last $LOOKBACK_DAYS days.\n\n")
            append("Rates are grouped into 0.1 BPM buckets. Each bucket is scored using ")
            append("recency-weighted coherence (half-life ${HALF_LIFE_DAYS.toInt()} days) ")
            append("multiplied by a confidence factor (requires ≥$MIN_POINTS_FULL_CONFIDENCE data points for full confidence).\n\n")
            append("Recommended rate: %.2f BPM ".format(bestBucket.rateBpm))
            append("(score: %.2f, %d data points)".format(bestBucket.finalScore, bestBucket.dataPointCount))
        }

        return RateRecommendation(
            recommendedBpm = bestBucket.rateBpm,
            buckets = markedBuckets,
            assessmentCount = validAssessmentCount,
            sessionCount = validSessionCount,
            summaryText = summaryText,
            lookbackDays = LOOKBACK_DAYS
        )
    }

    /** Exponential decay: weight = 2^(-ageDays / halfLife). */
    private fun recencyWeight(ageDays: Double): Float {
        return Math.pow(2.0, -ageDays / HALF_LIFE_DAYS).toFloat()
    }

    /** Round to nearest 0.1 BPM. */
    private fun roundToTenth(value: Float): Float {
        return (value * 10).roundToInt() / 10f
    }
}
