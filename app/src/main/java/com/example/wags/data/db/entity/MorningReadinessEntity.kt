package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "morning_readiness")
data class MorningReadinessEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,                        // Unix epoch ms

    // Supine HRV
    val supineRmssdMs: Double,
    val supineLnRmssd: Double,
    val supineSdnnMs: Double,
    val supineRhr: Int,

    // Standing HRV — null when the user skipped the standing phase
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val standingRmssdMs: Double?,
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val standingLnRmssd: Double?,
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val standingSdnnMs: Double?,

    // Orthostatic metrics — peakStandHr null when standing was skipped
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val peakStandHr: Int?,
    val thirtyFifteenRatio: Float?,
    val ohrrAt20sPercent: Float?,
    val ohrrAt60sPercent: Float?,

    // Respiratory
    val respiratoryRateBpm: Float?,
    val slowBreathingFlagged: Boolean,

    // Hooper Index (continuous 1.0–5.0 per dimension; total 4.0–20.0)
    val hooperSleep: Float?,
    val hooperFatigue: Float?,
    val hooperSoreness: Float?,
    val hooperStress: Float?,
    val hooperTotal: Float?,

    // Data quality
    val artifactPercentSupine: Float,
    val artifactPercentStanding: Float,

    // Final score
    val readinessScore: Int,
    val readinessColor: String,             // "RED", "YELLOW", "GREEN"
    val hrvBaseScore: Int,
    val orthoMultiplier: Float,
    val cvPenaltyApplied: Boolean,
    val rhrLimiterApplied: Boolean,

    /**
     * Human-readable identifier of the HR device used during this assessment
     * (e.g. "Polar H10 (ABC123)", "Polar Verity (XYZ)").
     * Null when no device was connected.
     */
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null,

    /**
     * Unix epoch ms when the user was instructed to stand (STAND_PROMPT → STANDING transition).
     * Used to draw the orthostatic marker on HR/HRV charts in the history detail view.
     * Null for readings recorded before this column was added (migration 13→14).
     */
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val standTimestampMs: Long? = null,

    /**
     * Version of the HRV artifact-correction pipeline that produced this
     * reading's supine/standing metrics (migration 44→45, 2026-10-22).
     *  1 = legacy pipeline (pre-2026-10-22; susceptible to dropped-beat
     *      RMSSD inflation — supine metrics backfilled from telemetry where
     *      possible, otherwise see qualityFlag)
     *  2 = pipeline with MissedBeatReconstructor + final quotient filter,
     *      or a v2 recomputation from stored telemetry
     */
    @androidx.room.ColumnInfo(defaultValue = "1")
    val metricsVersion: Int = 1,

    /**
     * True when this reading's metrics are considered unreliable and should
     * be excluded from baseline calculations (but kept in history/charts).
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val qualityFlag: Boolean = false,

    /**
     * IANA zone the device was in when this reading was saved. Frozen at save
     * time so the reading's local calendar day never changes when the user
     * travels. Null on pre-fix rows; resolved by the one-time backfill or the
     * read-time fallback (see LocalTimeZones).
     */
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val zoneId: String? = null
)
