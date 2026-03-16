package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores per-beat HR telemetry for a morning readiness session.
 *
 * Each row represents one RR interval converted to HR (bpm), tagged with:
 *  - the parent reading ID (FK → morning_readiness.id)
 *  - the wall-clock timestamp of the beat
 *  - the phase ("SUPINE" or "STANDING")
 *  - the rolling RMSSD (ms) computed over a ~20-beat sliding window at this point
 *
 * This data is used to draw the HR and HRV-over-time charts in the history detail screen.
 * Rows are cascade-deleted when the parent morning_readiness row is deleted.
 */
@Entity(
    tableName = "morning_readiness_telemetry",
    foreignKeys = [
        ForeignKey(
            entity = MorningReadinessEntity::class,
            parentColumns = ["id"],
            childColumns = ["readingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("readingId")]
)
data class MorningReadinessTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** FK → morning_readiness.id */
    val readingId: Long,

    /** Unix epoch ms of this beat (from RrInterval.timestampMs) */
    val timestampMs: Long,

    /** Heart rate in bpm derived from the RR interval: round(60_000 / rrMs) */
    val hrBpm: Int,

    /** Rolling RMSSD (ms) over the preceding ~20 beats at this point in the session.
     *  0.0 when fewer than 2 beats are available for the window. */
    val rollingRmssdMs: Double,

    /** "SUPINE" or "STANDING" */
    val phase: String
)
