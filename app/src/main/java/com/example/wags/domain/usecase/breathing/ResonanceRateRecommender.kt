package com.example.wags.domain.usecase.breathing

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
    /** Weighted average of normalized scores (assessments count 3×). */
    val weightedAvgScore: Float,
    /** Confidence multiplier (0..1) based on effective data count. */
    val confidenceMultiplier: Float,
    /** Final score = weightedAvgScore × confidenceMultiplier. */
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
    /** Normalized score (0–1). Assessments: compositeScore/260, Sessions: coherenceRatio/5. */
    val normalizedScore: Float,
    /** Raw display value: compositeScore for assessments, coherenceRatio for sessions. */
    val rawDisplayValue: Float,
    /** Weight of this data point (assessments = 3, sessions = 1). */
    val sourceWeight: Int,
    val timestamp: Long,
    /** Label for display, e.g. "Assessment (score 258.7)" or "Session (10:00)". */
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
 * 2. Normalize scores to 0–1: assessments use compositeScore/260,
 *    sessions use meanCoherenceRatio/5 (capped at 1).
 * 3. Group into 0.1 BPM buckets (round to nearest 0.1).
 * 4. For each bucket compute a weighted average where assessments count 3×
 *    and sessions count 1×.
 * 5. Apply confidence multiplier: min(effectivePoints / MIN_EFF_POINTS, 1.0)
 *    where effectivePoints = 3×assessments + 1×sessions.
 * 6. Final score = weightedAvgScore × confidenceMultiplier.
 * 7. Pick the bucket with the highest final score.
 */
@Singleton
class ResonanceRateRecommender @Inject constructor(
    private val rfAssessmentRepo: RfAssessmentRepository,
    private val resonanceSessionRepo: ResonanceSessionRepository
) {
    companion object {
        const val LOOKBACK_DAYS = 60
        /** Assessment composite score ceiling for normalization. */
        const val ASSESSMENT_SCORE_CEILING = 260f
        /** Coherence ratio ceiling for normalization. */
        const val COHERENCE_RATIO_CEILING = 5f
        /** Assessments count this many times more than sessions. */
        const val ASSESSMENT_WEIGHT = 3
        /** Sessions count as this weight. */
        const val SESSION_WEIGHT = 1
        /** Effective data points needed for full confidence (1.0 multiplier). */
        const val MIN_EFFECTIVE_POINTS = 5
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

        // ── Collect assessment data points (use compositeScore) ──────────────
        var validAssessmentCount = 0
        assessments.forEach { a ->
            if (a.isValid && a.optimalBpm in 4f..7f) {
                validAssessmentCount++
                val normalized = (a.compositeScore / ASSESSMENT_SCORE_CEILING).coerceIn(0f, 1f)
                dataPoints.add(
                    RateDataPoint(
                        source = DataPointSource.ASSESSMENT,
                        rateBpm = a.optimalBpm,
                        normalizedScore = normalized,
                        rawDisplayValue = a.compositeScore,
                        sourceWeight = ASSESSMENT_WEIGHT,
                        timestamp = a.timestamp,
                        label = "Assessment (score %.1f)".format(a.compositeScore)
                    )
                )
            }
        }

        // ── Collect session data points (use meanCoherenceRatio) ────────────
        var validSessionCount = 0
        sessions.forEach { s ->
            if (s.breathingRateBpm in 4f..7f && s.durationSeconds >= 60) {
                validSessionCount++
                val normalized = (s.meanCoherenceRatio / COHERENCE_RATIO_CEILING).coerceIn(0f, 1f)
                val durationLabel = "%d:%02d".format(s.durationSeconds / 60, s.durationSeconds % 60)
                dataPoints.add(
                    RateDataPoint(
                        source = DataPointSource.SESSION,
                        rateBpm = s.breathingRateBpm,
                        normalizedScore = normalized,
                        rawDisplayValue = s.meanCoherenceRatio,
                        sourceWeight = SESSION_WEIGHT,
                        timestamp = s.timestamp,
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
            // Weighted average: assessments count 3×, sessions count 1×
            val totalWeight = points.sumOf { it.sourceWeight }
            val weightedAvg = if (totalWeight > 0) {
                points.sumOf { it.normalizedScore.toDouble() * it.sourceWeight } / totalWeight
            } else {
                0.0
            }.toFloat()

            // Confidence based on effective points (assessment=3, session=1)
            val effectivePoints = points.sumOf { it.sourceWeight }
            val confidence = (effectivePoints.toFloat() / MIN_EFFECTIVE_POINTS).coerceAtMost(1f)
            val finalScore = weightedAvg * confidence

            RateBucket(
                rateBpm = rate,
                dataPointCount = points.size,
                weightedAvgScore = weightedAvg,
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
            append("Assessments use composite score (0–260), sessions use coherence ratio (0–5). ")
            append("Both are normalized to 0–1. Assessments count ${ASSESSMENT_WEIGHT}× more than sessions. ")
            append("Confidence requires ≥$MIN_EFFECTIVE_POINTS effective points (1 assessment = $ASSESSMENT_WEIGHT pts).\n\n")
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

    /** Round to nearest 0.1 BPM. */
    private fun roundToTenth(value: Float): Float {
        return (value * 10).roundToInt() / 10f
    }
}
