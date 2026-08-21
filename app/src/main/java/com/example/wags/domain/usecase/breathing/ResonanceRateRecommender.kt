package com.example.wags.domain.usecase.breathing

import com.example.wags.data.repository.ResonanceSessionRepository
import com.example.wags.data.repository.RfAssessmentRepository
import com.example.wags.domain.model.Posture
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
 * One rate bucket (0.05 BPM granularity) with its aggregated scores.
 */
data class RateBucket(
    /** The rate in BPM (rounded to 0.05). */
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

// ── History replay models ───────────────────────────────────────────────────────

/** A rate and its final score at one replay checkpoint. */
data class RankedRate(
    val rateBpm: Float,
    val finalScore: Float
)

/**
 * One checkpoint of the historical replay: what the recommender would have
 * said at [timestamp] using only the LOOKBACK_DAYS window ending there.
 */
data class RateHistorySnapshot(
    val timestamp: Long,
    /** Winner rate at this checkpoint, or null if the window held no data. */
    val winnerBpm: Float?,
    val winnerScore: Float,
    val winnerConfidence: Float,
    /** Best buckets at this checkpoint (best first), possibly fewer than 3. */
    val top3: List<RankedRate>,
    /** Data points inside the window at this checkpoint. */
    val dataPointCount: Int
)

/**
 * Staying-power stats for one rate across every replay checkpoint.
 * This is what surfaces rates that "keep proving themselves".
 */
data class RateConsistencyStat(
    val rateBpm: Float,
    /** Checkpoints where this rate was ranked #1. */
    val checkpointsAtNumber1: Int,
    /** Checkpoints where this rate appeared in the top 3. */
    val checkpointsInTop3: Int,
    /** Total checkpoints that had any data. */
    val totalCheckpoints: Int,
    /** Average final score across checkpoints where it was in the top 3. */
    val avgTop3Score: Float
)

/**
 * Full replay result used by the "best rate over time" chart.
 */
data class RateHistoryResult(
    val snapshots: List<RateHistorySnapshot>,
    /** Sorted best-first by #1 finishes, then top-3 appearances. */
    val consistency: List<RateConsistencyStat>,
    val stepDays: Int
) {
    val hasData: Boolean get() = snapshots.any { it.winnerBpm != null }
}

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

        private val WINDOW_MS = LOOKBACK_DAYS * 24L * 60 * 60 * 1000
    }

    /** Points plus counts returned by [collectDataPoints]. */
    private data class DataPointBundle(
        val points: List<RateDataPoint>,
        val assessmentCount: Int,
        val sessionCount: Int
    )

    /**
     * Compute the recommendation. This is a suspend function because it
     * reads from the database.
     */
    suspend fun recommend(): RateRecommendation {
        val nowMs = System.currentTimeMillis()
        val bundle = collectDataPoints(nowMs - WINDOW_MS, nowMs)

        if (bundle.points.isEmpty()) {
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

        val buckets = buildBuckets(bundle.points)
        val bestBucket = buckets.first()
        val markedBuckets = buckets.map {
            it.copy(isRecommended = it.rateBpm == bestBucket.rateBpm)
        }

        val summaryText = buildString {
            append("Analyzed ${bundle.points.size} data points ")
            append("(${bundle.assessmentCount} assessments, ${bundle.sessionCount} sessions) ")
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
            assessmentCount = bundle.assessmentCount,
            sessionCount = bundle.sessionCount,
            summaryText = summaryText,
            lookbackDays = LOOKBACK_DAYS
        )
    }

    /**
     * Replays the recommendation across the FULL history at fixed
     * [stepDays] checkpoints: for every checkpoint t it recomputes the
     * winner using only the data inside (t − 60 days, t], exactly as
     * [recommend] would have done at that moment.
     *
     * This powers the "best rate over time" chart — it shows how the
     * recommended rate drifted over the months and which rates keep
     * re-earning their top spots as old data falls out of the window.
     */
    suspend fun replayHistory(stepDays: Int = 7): RateHistoryResult {
        val nowMs = System.currentTimeMillis()
        val bundle = collectDataPoints(0L, nowMs)
        if (bundle.points.isEmpty()) {
            return RateHistoryResult(emptyList(), emptyList(), stepDays)
        }

        val sorted = bundle.points.sortedBy { it.timestamp }
        val stepMs = stepDays * 24L * 60 * 60 * 1000

        // Checkpoint grid anchored at the first data point, always ending at now.
        val evalTimes = buildList {
            var t = sorted.first().timestamp
            while (t < nowMs) {
                add(t)
                t += stepMs
            }
            add(nowMs)
        }

        val snapshots = evalTimes.map { t ->
            val windowPoints = sorted.filter { it.timestamp <= t && it.timestamp > t - WINDOW_MS }
            if (windowPoints.isEmpty()) {
                RateHistorySnapshot(t, null, 0f, 0f, emptyList(), 0)
            } else {
                val top = buildBuckets(windowPoints).take(3)
                RateHistorySnapshot(
                    timestamp = t,
                    winnerBpm = top.firstOrNull()?.rateBpm,
                    winnerScore = top.firstOrNull()?.finalScore ?: 0f,
                    winnerConfidence = top.firstOrNull()?.confidenceMultiplier ?: 0f,
                    top3 = top.map { RankedRate(it.rateBpm, it.finalScore) },
                    dataPointCount = windowPoints.size
                )
            }
        }

        val totalCheckpoints = snapshots.count { it.winnerBpm != null }
        val consistency = snapshots
            .flatMap { s -> s.top3.map { it.rateBpm to s } }
            .groupBy { it.first }
            .map { (rate, pairs) ->
                val scores = pairs.mapNotNull { (_, s) ->
                    s.top3.firstOrNull { it.rateBpm == rate }?.finalScore
                }
                RateConsistencyStat(
                    rateBpm = rate,
                    checkpointsAtNumber1 = snapshots.count { it.winnerBpm == rate },
                    checkpointsInTop3 = pairs.size,
                    totalCheckpoints = totalCheckpoints,
                    avgTop3Score = if (scores.isNotEmpty()) scores.average().toFloat() else 0f
                )
            }
            .sortedWith(
                compareByDescending<RateConsistencyStat> { it.checkpointsAtNumber1 }
                    .thenByDescending { it.checkpointsInTop3 }
            )

        return RateHistoryResult(snapshots, consistency, stepDays)
    }

    // ── Shared pipeline steps (used by both recommend and replayHistory) ─────

    /**
     * Collects valid data points with timestamps in (fromMs, untilMs].
     * Assessments use compositeScore, sessions use meanCoherenceRatio;
     * sitting data counts 1.5× via its source weight.
     */
    private suspend fun collectDataPoints(fromMs: Long, untilMs: Long): DataPointBundle {
        val dataPoints = mutableListOf<RateDataPoint>()

        // ── Collect assessment data points (use compositeScore) ──────────────
        var validAssessmentCount = 0
        rfAssessmentRepo.getSince(fromMs).forEach { a ->
            if (a.timestamp <= untilMs && a.isValid && a.optimalBpm in 4f..7f) {
                validAssessmentCount++
                val normalized = (a.compositeScore / ASSESSMENT_SCORE_CEILING).coerceIn(0f, 1f)
                val posture = try { Posture.valueOf(a.posture) } catch (e: Exception) { Posture.LAYING }
                // Sitting assessments get 1.5x weight, laying assessments get 1x
                val postureWeightMultiplier = if (posture == Posture.SITTING) 1.5f else 1.0f
                val adjustedWeight = (ASSESSMENT_WEIGHT * postureWeightMultiplier).toInt()
                val postureLabel = posture.displayName()
                dataPoints.add(
                    RateDataPoint(
                        source = DataPointSource.ASSESSMENT,
                        rateBpm = a.optimalBpm,
                        normalizedScore = normalized,
                        rawDisplayValue = a.compositeScore,
                        sourceWeight = adjustedWeight,
                        timestamp = a.timestamp,
                        label = "Assessment (score %.1f, $postureLabel)".format(a.compositeScore)
                    )
                )
            }
        }

        // ── Collect session data points (use meanCoherenceRatio) ────────────
        var validSessionCount = 0
        resonanceSessionRepo.getSince(fromMs).forEach { s ->
            // Only include sessions with HR device connected and valid data
            if (s.timestamp <= untilMs &&
                s.breathingRateBpm in 4f..7f &&
                s.durationSeconds >= 60 &&
                s.hrDeviceId != null &&
                s.hrDeviceId != "NULL" &&
                s.totalBeats > 0
            ) {
                validSessionCount++
                val normalized = (s.meanCoherenceRatio / COHERENCE_RATIO_CEILING).coerceIn(0f, 1f)
                val durationLabel = "%d:%02d".format(s.durationSeconds / 60, s.durationSeconds % 60)
                val posture = try { Posture.valueOf(s.posture) } catch (e: Exception) { Posture.LAYING }
                // Sitting sessions get 1.5x weight, laying sessions get 1x
                val postureWeightMultiplier = if (posture == Posture.SITTING) 1.5f else 1.0f
                val adjustedWeight = (SESSION_WEIGHT * postureWeightMultiplier).toInt()
                val postureLabel = posture.displayName()
                dataPoints.add(
                    RateDataPoint(
                        source = DataPointSource.SESSION,
                        rateBpm = s.breathingRateBpm,
                        normalizedScore = normalized,
                        rawDisplayValue = s.meanCoherenceRatio,
                        sourceWeight = adjustedWeight,
                        timestamp = s.timestamp,
                        label = "Session ($durationLabel, $postureLabel)"
                    )
                )
            }
        }

        return DataPointBundle(dataPoints, validAssessmentCount, validSessionCount)
    }

    /**
     * Groups points into 0.05 BPM buckets and scores them
     * (weighted average × confidence), sorted best-first.
     */
    private fun buildBuckets(points: List<RateDataPoint>): List<RateBucket> {
        return points
            .groupBy { roundToTwentieth(it.rateBpm) }
            .map { (rate, bucketPoints) ->
                // Weighted average: assessments count 3×, sessions count 1×
                val totalWeight = bucketPoints.sumOf { it.sourceWeight }
                val weightedAvg = if (totalWeight > 0) {
                    bucketPoints.sumOf { it.normalizedScore.toDouble() * it.sourceWeight } / totalWeight
                } else {
                    0.0
                }.toFloat()

                // Confidence based on effective points (assessment=3, session=1)
                val effectivePoints = bucketPoints.sumOf { it.sourceWeight }
                val confidence = (effectivePoints.toFloat() / MIN_EFFECTIVE_POINTS).coerceAtMost(1f)
                val finalScore = weightedAvg * confidence

                RateBucket(
                    rateBpm = rate,
                    dataPointCount = bucketPoints.size,
                    weightedAvgScore = weightedAvg,
                    confidenceMultiplier = confidence,
                    finalScore = finalScore,
                    isRecommended = false,
                    dataPoints = bucketPoints.sortedByDescending { it.timestamp }
                )
            }
            .sortedByDescending { it.finalScore }
    }

    /** Round to nearest 0.05 BPM. */
    private fun roundToTwentieth(value: Float): Float {
        return (value * 20).roundToInt() / 20f
    }
}
