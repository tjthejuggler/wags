package com.example.wags.domain.usecase.apnea.forecast

import com.example.wags.domain.model.PersonalBestCategory

// ─────────────────────────────────────────────────────────────────────────────
// Data classes for the record-breaking forecast feature
// ─────────────────────────────────────────────────────────────────────────────

/** Current 5-setting combination (all values as DB enum-name strings). */
data class ForecastSettings(
    val lungVolume: String,
    val prepType: String,
    val timeOfDay: String,
    val posture: String,
    val audio: String
)

/** Whether the forecast is available or not. */
sealed class ForecastStatus {
    object Ready : ForecastStatus()
    object InsufficientData : ForecastStatus()
}

/** Confidence tier based on total free-hold sample size. */
enum class ForecastConfidence {
    LOW,    // 5–49 holds
    MEDIUM, // 50–149 holds
    HIGH;   // 150+ holds

    fun emoji(): String = when (this) {
        LOW    -> "🔴"
        MEDIUM -> "🟡"
        HIGH   -> "🟢"
    }
}

/**
 * One row in the 32-category forecast breakdown.
 *
 * @property category       Trophy level (EXACT … GLOBAL).
 * @property trophyCount     1–6.
 * @property label           Human-readable (e.g. "Morning · Full", "All settings").
 * @property recordMs        Current PB for this category, or null if no record exists.
 * @property probability     P(next hold > recordMs), 0–1. 1.0 when recordMs is null.
 * @property confidence       Data-confidence tier for this prediction.
 */
data class CategoryForecast(
    val category: PersonalBestCategory,
    val trophyCount: Int,
    val label: String,
    val recordMs: Long?,
    val probability: Float,
    val confidence: ForecastConfidence
)

/**
 * Complete forecast result for the current settings.
 *
 * @property status           Ready or InsufficientData.
 * @property exactProbability  The single % shown on the Free Hold card (exact-combo chance).
 * @property categories        All 32 sub-categories, sorted by probability descending.
 * @property totalFreeHolds    N used to fit the model.
 * @property confidence        Overall confidence tier.
 */
data class RecordForecast(
    val status: ForecastStatus,
    val exactProbability: Float,
    val categories: List<CategoryForecast>,
    val totalFreeHolds: Int,
    val confidence: ForecastConfidence
)
