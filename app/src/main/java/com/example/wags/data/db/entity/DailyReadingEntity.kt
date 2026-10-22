package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_readings")
data class DailyReadingEntity(
    @PrimaryKey(autoGenerate = true) val readingId: Long = 0,
    val timestamp: Long,
    val restingHrBpm: Float,
    val rawRmssdMs: Float,
    val lnRmssd: Float,
    val hfPowerMs2: Float,
    val sdnnMs: Float,
    val readinessScore: Int,
    /**
     * Human-readable identifier of the HR device used during this reading
     * (e.g. "Polar H10 (ABC123)", "Polar Verity (XYZ)").
     * Null when no device was connected.
     */
    @ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null,

    /**
     * Version of the HRV artifact-correction pipeline that produced this
     * reading's metrics (migration 44→45, 2026-10-22).
     *  1 = legacy pipeline (pre-2026-10-22; deleted dropped-beat intervals,
     *      inflating RMSSD — see qualityFlag)
     *  2 = pipeline with MissedBeatReconstructor + final quotient filter
     */
    @ColumnInfo(defaultValue = "1")
    val metricsVersion: Int = 1,

    /**
     * True when this reading is considered unreliable (implausible RMSSD for
     * the legacy pipeline, or low beat coverage under pipeline v2). Flagged
     * readings are excluded from baseline calculations but kept in history.
     */
    @ColumnInfo(defaultValue = "0")
    val qualityFlag: Boolean = false,

    /**
     * Actual recording duration in seconds (migration 45→46, 2026-10-22).
     * Legacy rows default to 60: the pre-fix countdown bug halved every test,
     * so a "2 min" capture actually recorded 60 s. New rows store the real
     * duration selected by the user (60 / 120 / 240).
     */
    @ColumnInfo(defaultValue = "60")
    val sessionDurationSeconds: Int = 60
)
