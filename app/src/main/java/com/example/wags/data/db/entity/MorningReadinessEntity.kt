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

    // Hooper Index
    val hooperSleep: Int?,
    val hooperFatigue: Int?,
    val hooperSoreness: Int?,
    val hooperStress: Int?,
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
    val hrDeviceId: String? = null
)
