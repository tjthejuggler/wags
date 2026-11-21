package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed Rapid HR Change session.
 *
 * [direction]              – "HIGH_TO_LOW" or "LOW_TO_HIGH"
 * [highThreshold]          – High HR threshold in bpm
 * [lowThreshold]           – Low HR threshold in bpm
 * [phase1DurationMs]       – Time to reach the first threshold (warm-up phase)
 * [transitionDurationMs]   – Time from first threshold to second threshold (the main score)
 * [totalDurationMs]        – Total session time
 * [peakHrBpm]              – Highest HR recorded during session
 * [troughHrBpm]            – Lowest HR recorded during session
 * [hrAtFirstCrossing]      – HR when first threshold was crossed
 * [hrAtSecondCrossing]     – HR when second threshold was crossed
 * [avgHrBpm]               – Average HR over entire session
 * [monitorId]              – BLE device label, null if no monitor connected
 * [isPersonalBest]         – Whether this was a PB at time of recording
 */
@Entity(tableName = "rapid_hr_sessions")
data class RapidHrSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val direction: String,
    val highThreshold: Int,
    val lowThreshold: Int,
    val phase1DurationMs: Long,
    val transitionDurationMs: Long,
    val totalDurationMs: Long,
    val peakHrBpm: Int,
    val troughHrBpm: Int,
    val hrAtFirstCrossing: Int,
    val hrAtSecondCrossing: Int,
    val avgHrBpm: Float? = null,
    val monitorId: String? = null,
    val isPersonalBest: Boolean = false,
    /**
     * IANA zone the device was in when this session was saved. Frozen at save
     * time so the session's local calendar day never changes when the user
     * travels. Null on pre-fix rows; resolved by the one-time backfill or the
     * read-time fallback (see LocalTimeZones).
     */
    @ColumnInfo(defaultValue = "NULL")
    val zoneId: String? = null
)
