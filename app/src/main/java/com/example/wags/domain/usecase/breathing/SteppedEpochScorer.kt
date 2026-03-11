package com.example.wags.domain.usecase.breathing

data class EpochScore(
    val score: Float,
    val isValid: Boolean,
    val invalidReason: String?
)

enum class ScoreColor { RED, ORANGE, GREEN, BLUE, PINK, YELLOW, WHITE }

data class ScoreColorInfo(val color: ScoreColor, val label: String)

/**
 * Pure math scorer for stepped RF assessment epochs.
 * Wraps the composite scoring formula from the desktop hrvm app.
 *
 * Formula: score = (phase×0.40 + (PT/baseline_PT)×0.30 + (LFnu/100)×0.20 + (RMSSD/baseline_RMSSD)×0.10) × 260
 *
 * Quality gates: phaseSynchrony >= 0.25 && ptAmp >= 1.5
 */
object SteppedEpochScorer {

    private const val PHASE_WEIGHT    = 0.40f
    private const val PT_WEIGHT       = 0.30f
    private const val LFNU_WEIGHT     = 0.20f
    private const val RMSSD_WEIGHT    = 0.10f
    private const val SCALE_FACTOR    = 260f

    private const val MIN_PHASE_SYNCHRONY = 0.25f
    private const val MIN_PT_AMP          = 1.5f

    fun score(
        rmssd: Float,
        ptAmp: Float,
        lfNu: Float,
        phaseSynchrony: Float,
        baselineRmssd: Float,
        baselinePtAmp: Float
    ): EpochScore {
        // Quality gate checks
        if (phaseSynchrony < MIN_PHASE_SYNCHRONY) {
            return EpochScore(
                score = 0f,
                isValid = false,
                invalidReason = "Phase synchrony too low (%.2f < %.2f)".format(phaseSynchrony, MIN_PHASE_SYNCHRONY)
            )
        }
        if (ptAmp < MIN_PT_AMP) {
            return EpochScore(
                score = 0f,
                isValid = false,
                invalidReason = "PT amplitude too low (%.1f < %.1f BPM)".format(ptAmp, MIN_PT_AMP)
            )
        }

        // Clamp baselines to avoid division by zero
        val safeBaselineRmssd = if (baselineRmssd > 0f) baselineRmssd else 1f
        val safeBaselinePtAmp = if (baselinePtAmp > 0f) baselinePtAmp else 1f

        val composite = phaseSynchrony * PHASE_WEIGHT +
                (ptAmp / safeBaselinePtAmp) * PT_WEIGHT +
                (lfNu / 100f) * LFNU_WEIGHT +
                (rmssd / safeBaselineRmssd) * RMSSD_WEIGHT

        return EpochScore(
            score = composite * SCALE_FACTOR,
            isValid = true,
            invalidReason = null
        )
    }

    fun scoreColor(score: Float): ScoreColorInfo = when {
        score < 80f  -> ScoreColorInfo(ScoreColor.RED,    "Low")
        score < 130f -> ScoreColorInfo(ScoreColor.ORANGE, "Fair")
        score < 170f -> ScoreColorInfo(ScoreColor.GREEN,  "Good")
        score < 210f -> ScoreColorInfo(ScoreColor.BLUE,   "Very Good")
        score < 230f -> ScoreColorInfo(ScoreColor.PINK,   "Excellent")
        score < 245f -> ScoreColorInfo(ScoreColor.YELLOW, "Exceptional")
        else         -> ScoreColorInfo(ScoreColor.WHITE,  "Extraordinary")
    }
}
