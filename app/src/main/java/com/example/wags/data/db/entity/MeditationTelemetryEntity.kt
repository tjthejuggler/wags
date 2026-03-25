package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-beat HR / HRV telemetry captured during a meditation or NSDR session.
 *
 * Each row represents one 1-second sample tagged with:
 *  - the parent session ID (FK → meditation_sessions)
 *  - the wall-clock timestamp of the sample
 *  - the instantaneous HR in BPM derived from the most recent RR interval
 *  - the rolling RMSSD (ms) computed over a ~20-beat sliding window
 *
 * Used to draw the HR-over-time and RMSSD-over-time charts in the session
 * detail screen. Rows are cascade-deleted when the parent session is deleted.
 */
@Entity(
    tableName = "meditation_telemetry",
    foreignKeys = [
        ForeignKey(
            entity = MeditationSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MeditationTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** FK → meditation_sessions.sessionId */
    val sessionId: Long,

    /** Unix epoch ms of this sample. */
    val timestampMs: Long,

    /** Instantaneous heart rate in BPM (round(60_000 / lastRrMs)), null if unavailable. */
    val hrBpm: Int?,

    /** Rolling RMSSD (ms) over the preceding ~20 beats. 0.0 when fewer than 2 beats available. */
    val rollingRmssdMs: Double
)
