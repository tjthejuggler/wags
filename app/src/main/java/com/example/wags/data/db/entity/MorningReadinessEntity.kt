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

    // Standing HRV
    val standingRmssdMs: Double,
    val standingLnRmssd: Double,
    val standingSdnnMs: Double,

    // Orthostatic metrics
    val peakStandHr: Int,
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
    val standTimestampMs: Long? = null
)
