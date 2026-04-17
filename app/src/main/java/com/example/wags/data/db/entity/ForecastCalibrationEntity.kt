package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks forecast predictions vs. actual outcomes for calibration analysis.
 *
 * Each row records the forecast made *before* a free hold started and the
 * actual outcome *after* the hold completed. Future versions can analyze
 * this table to check whether predicted probabilities match observed
 * frequencies and adjust the model accordingly.
 *
 * Tier D/E hook: additional columns (e.g. readiness_score, sleep_hours)
 * can be added later as nullable columns with defaults.
 */
@Entity(tableName = "forecast_calibration")
data class ForecastCalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch-ms when the forecast was computed (just before hold start). */
    val timestamp: Long,
    /** The 5 settings at forecast time. */
    val lungVolume: String,
    val prepType: String,
    val timeOfDay: String,
    val posture: String,
    val audio: String,
    /** Number of free holds used to fit the model at forecast time. */
    val totalFreeHolds: Int,
    /** Confidence tier at forecast time: LOW, MEDIUM, or HIGH. */
    val confidence: String,
    // ── Per-category predictions (JSON-like: "EXACT=0.42,ONE_SETTING=0.08,...") ──
    /** Serialized map of PersonalBestCategory → predicted probability. */
    val predictions: String,
    // ── Actual outcome (filled in after hold completes) ───────────────────────
    /** Duration of the hold in ms. Null until the hold completes. */
    @ColumnInfo(defaultValue = "NULL")
    val actualDurationMs: Long? = null,
    /** Which categories were actually broken (same format as predictions). */
    @ColumnInfo(defaultValue = "NULL")
    val actualBroken: String? = null,
    /** Epoch-ms when the actual outcome was recorded. */
    @ColumnInfo(defaultValue = "NULL")
    val actualTimestamp: Long? = null
)
