package com.example.wags.domain.usecase.readiness

import com.example.wags.domain.model.HooperIndex
import com.example.wags.domain.model.HrvMetrics
import com.example.wags.domain.model.OrthostasisMetrics
import com.example.wags.domain.model.ReadinessColor
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Morning Readiness Algorithm — Conditional Limiting Architecture.
 *
 * 6-step pipeline:
 *   1. HRV_Base Score (Z-score vs 30-day chronic baseline + SWC)
 *   2. Orthostatic Multiplier (age-bracket 30:15 ratio + OHRR@60s)
 *   3. CV_Base = HRV_Base × OM
 *   4. Volatility Penalty (7-day CV vs 30-day CV)
 *   5. Hooper Index Gating
 *   6. Hard Pathological Limiters (RHR rule; skin-temp placeholder)
 */
class MorningReadinessScoreCalculator @Inject constructor() {

    data class Input(
        val supineHrvMetrics: HrvMetrics,
        val standingHrvMetrics: HrvMetrics,
        val supineRhr: Int,
        val peakStandHr: Int,
        val orthostasisMetrics: OrthostasisMetrics?,
        val hooperIndex: HooperIndex?,
        val respiratoryRateBpm: Float?,
        val slowBreathingFlagged: Boolean,
        val artifactPercentSupine: Float,
        val artifactPercentStanding: Float,
        val userAgeYears: Int,
        // Baseline data (from repository)
        val chronicLnRmssdHistory: List<Double>,  // 30-day ln(RMSSD) values
        val acuteLnRmssdHistory: List<Double>,    // 7-day ln(RMSSD) values
        val rhrHistory: List<Int>                 // 90-day supine RHR values
    )

    data class Output(
        val readinessScore: Int,
        val readinessColor: ReadinessColor,
        val hrvBaseScore: Int,
        val orthoMultiplier: Float,
        val cvBase: Float,
        val cvPenaltyApplied: Boolean,
        val rhrLimiterApplied: Boolean,
        val skinTempLimiterApplied: Boolean,
        val chronicMeanHrvScore: Float,
        val upperSwc: Float,
        val lowerSwc: Float,
        val acuteMeanHrvScore: Float,
        val sevenDayCv: Float,
        val thirtyDayCv: Float,
        val confidenceLevel: ConfidenceLevel
    )

    enum class ConfidenceLevel { PROVISIONAL, MODERATE, HIGH }

    fun calculate(input: Input): Output {
        val todayHrvScore = (input.supineHrvMetrics.lnRmssd * 20).toFloat()

        val confidence = when {
            input.chronicLnRmssdHistory.size >= 14 -> ConfidenceLevel.HIGH
            input.chronicLnRmssdHistory.size >= 3  -> ConfidenceLevel.MODERATE
            else                                   -> ConfidenceLevel.PROVISIONAL
        }

        // Step 1: HRV_Base
        val (hrvBase, chronicMean, upperSwc, lowerSwc) = computeHrvBase(
            todayHrvScore, input.chronicLnRmssdHistory
        )

        // Step 2: Orthostatic Multiplier
        val om = computeOrthoMultiplier(input.orthostasisMetrics, input.userAgeYears)

        // Step 3: CV_Base
        var cvBase = hrvBase * om

        // Step 4: Volatility Penalty
        val (sevenDayCv, thirtyDayCv, penaltyApplied) = computeVolatilityPenalty(
            input.acuteLnRmssdHistory, input.chronicLnRmssdHistory
        )
        if (penaltyApplied) cvBase -= 10f

        // Step 5: Hooper Gating
        cvBase = applyHooperGating(cvBase, input.hooperIndex)

        // Step 6: Hard Limiters
        val (rhrLimited, rhrLimiterApplied) = applyRhrLimiter(
            cvBase, input.supineRhr, input.rhrHistory
        )
        cvBase = rhrLimited

        val finalScore = cvBase.roundToInt().coerceIn(0, 100)
        val color = when {
            finalScore <= 39 -> ReadinessColor.RED
            finalScore <= 69 -> ReadinessColor.YELLOW
            else             -> ReadinessColor.GREEN
        }

        val acuteMean = if (input.acuteLnRmssdHistory.isNotEmpty()) {
            (input.acuteLnRmssdHistory.average() * 20).toFloat()
        } else todayHrvScore

        return Output(
            readinessScore = finalScore,
            readinessColor = color,
            hrvBaseScore = hrvBase.roundToInt(),
            orthoMultiplier = om,
            cvBase = cvBase,
            cvPenaltyApplied = penaltyApplied,
            rhrLimiterApplied = rhrLimiterApplied,
            skinTempLimiterApplied = false,
            chronicMeanHrvScore = chronicMean,
            upperSwc = upperSwc,
            lowerSwc = lowerSwc,
            acuteMeanHrvScore = acuteMean,
            sevenDayCv = sevenDayCv,
            thirtyDayCv = thirtyDayCv,
            confidenceLevel = confidence
        )
    }

    // -------------------------------------------------------------------------
    // Step 1: HRV_Base
    // Returns (hrvBase, chronicMean, upperSwc, lowerSwc)
    // -------------------------------------------------------------------------
    private fun computeHrvBase(
        todayHrvScore: Float,
        chronicHistory: List<Double>
    ): Quadruple<Float, Float, Float, Float> {
        if (chronicHistory.isEmpty()) {
            return Quadruple(70f, todayHrvScore, todayHrvScore + 5f, todayHrvScore - 5f)
        }

        val scores = chronicHistory.map { (it * 20).toFloat() }
        val mean   = scores.average().toFloat()
        val sd     = scores.standardDeviation()
        val upper  = mean + 0.5f * sd
        val lower  = mean - 0.5f * sd

        val base = when {
            // Parasympathetic hyper-compensation cap
            todayHrvScore > mean + 1.5f * sd -> 75f

            // Within SWC band → linear 90–100
            todayHrvScore in lower..upper -> {
                val t = (todayHrvScore - lower) / (upper - lower).coerceAtLeast(0.001f)
                90f + t * 10f
            }

            // Below lower SWC → linear 90 down to 30 (floor at lowerSwc - 2*sd)
            todayHrvScore < lower -> {
                val floor = lower - 2f * sd
                val t = ((todayHrvScore - floor) / (lower - floor).coerceAtLeast(0.001f))
                    .coerceIn(0f, 1f)
                30f + t * 60f
            }

            // Above upper SWC but ≤ +1.5 SWC → cap at 90 (already handled by SWC band above)
            else -> 90f
        }

        return Quadruple(base.coerceIn(30f, 100f), mean, upper, lower)
    }

    // -------------------------------------------------------------------------
    // Step 2: Orthostatic Multiplier
    //
    // The multiplier modulates the HRV base score based on how well the ANS
    // handles the gravitational stress of standing. Thresholds are age-adjusted.
    //
    // Multiplier table (revised — less aggressive on first readings):
    //   NORMAL  + GOOD    → 1.05  (bonus: excellent orthostatic response)
    //   NORMAL  + UNKNOWN → 1.0   (ratio good, OHRR not measurable)
    //   UNKNOWN + GOOD    → 1.0   (OHRR good, ratio not measurable)
    //   BORDERLINE (either) → 0.95 (mild penalty)
    //   ABNORMAL + POOR   → 0.80  (both bad — clear orthostatic stress)
    //   ABNORMAL only     → 0.88  (ratio bad but OHRR ok/unknown)
    //   POOR only         → 0.88  (OHRR bad but ratio ok/unknown)
    //   else (mixed unknown) → 0.95
    // -------------------------------------------------------------------------
    private fun computeOrthoMultiplier(ortho: OrthostasisMetrics?, ageYears: Int): Float {
        if (ortho == null) return 1.0f

        val ratio   = ortho.thirtyFifteenRatio
        val ohrr60  = ortho.ohrrAt60sPercent

        val (normalThreshold, borderlineThreshold) = when {
            ageYears < 40 -> Pair(1.15f, 1.08f)
            ageYears < 50 -> Pair(1.10f, 1.05f)
            else          -> Pair(1.05f, 1.02f)
        }

        val ratioStatus = when {
            ratio == null                -> RatioStatus.UNKNOWN
            ratio >= normalThreshold     -> RatioStatus.NORMAL
            ratio >= borderlineThreshold -> RatioStatus.BORDERLINE
            else                         -> RatioStatus.ABNORMAL
        }

        val ohrrStatus = when {
            ohrr60 == null  -> OhrrStatus.UNKNOWN
            ohrr60 > 20f    -> OhrrStatus.GOOD
            ohrr60 >= 10f   -> OhrrStatus.BORDERLINE
            else            -> OhrrStatus.POOR
        }

        return when {
            // Both signals clearly good → small bonus
            ratioStatus == RatioStatus.NORMAL && ohrrStatus == OhrrStatus.GOOD         -> 1.05f
            // One signal good, other unknown → neutral
            ratioStatus == RatioStatus.NORMAL && ohrrStatus == OhrrStatus.UNKNOWN      -> 1.0f
            ratioStatus == RatioStatus.UNKNOWN && ohrrStatus == OhrrStatus.GOOD        -> 1.0f
            // Both signals clearly bad → moderate penalty
            ratioStatus == RatioStatus.ABNORMAL && ohrrStatus == OhrrStatus.POOR       -> 0.80f
            // One signal bad, other ok/unknown → mild penalty
            ratioStatus == RatioStatus.ABNORMAL                                        -> 0.88f
            ohrrStatus == OhrrStatus.POOR                                              -> 0.88f
            // Either borderline → very mild penalty
            ratioStatus == RatioStatus.BORDERLINE || ohrrStatus == OhrrStatus.BORDERLINE -> 0.95f
            // Mixed unknown → neutral
            else -> 1.0f
        }
    }

    // -------------------------------------------------------------------------
    // Step 4: Volatility Penalty
    // Returns (sevenDayCv, thirtyDayCv, penaltyApplied)
    // -------------------------------------------------------------------------
    private fun computeVolatilityPenalty(
        acuteHistory: List<Double>,
        chronicHistory: List<Double>
    ): Triple<Float, Float, Boolean> {
        val acuteScores   = acuteHistory.map { (it * 20).toFloat() }
        val chronicScores = chronicHistory.map { (it * 20).toFloat() }

        val sevenDayCv = if (acuteScores.size >= 3) {
            val mean = acuteScores.average().toFloat()
            if (mean > 0f) acuteScores.standardDeviation() / mean * 100f else 0f
        } else 0f

        val thirtyDayCv = if (chronicScores.size >= 7) {
            val mean = chronicScores.average().toFloat()
            if (mean > 0f) chronicScores.standardDeviation() / mean * 100f else 0f
        } else 0f

        val penaltyApplied = thirtyDayCv > 0f && sevenDayCv > thirtyDayCv * 1.30f

        return Triple(sevenDayCv, thirtyDayCv, penaltyApplied)
    }

    // -------------------------------------------------------------------------
    // Step 5: Hooper Index Gating
    //
    // The Hooper scale is 1–5 per item where 5 = best (no fatigue/stress/soreness,
    // excellent sleep). Total range: 4 (worst) to 20 (best).
    //   isLow  = total ≤ 10  → poor subjective state
    //   isHigh = total ≥ 16  → good subjective state
    //
    // Gating rules:
    //   A. HRV already low (cvBase < 50) AND Hooper is also low → no double penalty,
    //      trust the HRV math.
    //   B. HRV looks good (cvBase ≥ 70) BUT Hooper is very bad (total ≤ 8) → apply
    //      a penalty because subjective state is severely poor (likely illness/overreach).
    //   C. HRV looks good (cvBase ≥ 70) AND Hooper is moderately bad (isLow) → mild
    //      penalty; subjective state suggests more fatigue than HRV shows.
    //   D. HRV is low (cvBase < 50) AND Hooper is high → no bonus; trust the HRV math
    //      (HRV is the more objective signal).
    //   E. Everything else → no adjustment.
    //
    // NOTE: "isHigh" (good wellness) should NEVER penalize a good HRV score.
    // -------------------------------------------------------------------------
    private fun applyHooperGating(cvBase: Float, hooper: HooperIndex?): Float {
        if (hooper == null) return cvBase
        return when {
            cvBase < 50f && hooper.isLow   -> cvBase           // A: already low, no double penalty
            cvBase >= 70f && hooper.total <= 8f -> cvBase - 15f // B: HRV good but severely poor wellness
            cvBase >= 70f && hooper.isLow  -> cvBase - 10f     // C: HRV good but moderately poor wellness
            cvBase < 50f && hooper.isHigh  -> cvBase           // D: HRV low despite good wellness — trust HRV
            else                           -> cvBase           // E: no adjustment needed
        }
    }

    // -------------------------------------------------------------------------
    // Step 6: RHR Hard Limiter
    // Returns (adjustedScore, limiterApplied)
    // -------------------------------------------------------------------------
    private fun applyRhrLimiter(
        cvBase: Float,
        todayRhr: Int,
        rhrHistory: List<Int>
    ): Pair<Float, Boolean> {
        if (rhrHistory.size < 7) return Pair(cvBase, false)

        val mean           = rhrHistory.average()
        val sd             = rhrHistory.map { it.toDouble() }.standardDeviationDouble()
        val threshold25Sd  = mean + 2.5 * sd
        val threshold10Bpm = mean + 10.0

        return if (todayRhr > threshold25Sd || todayRhr > threshold10Bpm) {
            Pair(minOf(cvBase, 50f), true)
        } else {
            Pair(cvBase, false)
        }
    }

    // Private helper enums
    private enum class RatioStatus { NORMAL, BORDERLINE, ABNORMAL, UNKNOWN }
    private enum class OhrrStatus  { GOOD, BORDERLINE, POOR, UNKNOWN }
}

// -----------------------------------------------------------------------------
// File-level helpers
// -----------------------------------------------------------------------------

private data class Quadruple<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D
)

private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component1() = first
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component2() = second
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component3() = third
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component4() = fourth

/** Sample standard deviation (Bessel-corrected) for Float lists. */
private fun List<Float>.standardDeviation(): Float {
    if (size < 2) return 0f
    val mean     = average().toFloat()
    val variance = sumOf { ((it - mean) * (it - mean)).toDouble() } / (size - 1)
    return sqrt(variance).toFloat()
}

/** Sample standard deviation (Bessel-corrected) for Double lists. */
private fun List<Double>.standardDeviationDouble(): Double {
    if (size < 2) return 0.0
    val mean     = average()
    val variance = sumOf { (it - mean) * (it - mean) } / (size - 1)
    return sqrt(variance)
}
