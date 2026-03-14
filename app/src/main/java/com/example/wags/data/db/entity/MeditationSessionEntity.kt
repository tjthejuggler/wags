package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completed meditation / NSDR session.
 *
 * [audioId]          – FK to [MeditationAudioEntity]; references the "None" row when no audio.
 * [timestamp]        – epoch-ms when the session was started.
 * [durationMs]       – total session length in milliseconds.
 * [monitorId]        – BLE device label used for HR, null if no monitor was connected.
 * [avgHrBpm]         – mean heart rate over the session, null if no HR data.
 * [hrSlopeBpmPerMin] – linear trend of HR (positive = rising), null if no HR data.
 * [startRmssdMs]     – RMSSD computed from the first 60 s of RR data, null if unavailable.
 * [endRmssdMs]       – RMSSD computed from the last 60 s of RR data, null if unavailable.
 * [lnRmssdSlope]     – slope of ln(RMSSD) over the session, null if unavailable.
 */
@Entity(
    tableName = "meditation_sessions",
    foreignKeys = [
        ForeignKey(
            entity = MeditationAudioEntity::class,
            parentColumns = ["audioId"],
            childColumns = ["audioId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("audioId")]
)
data class MeditationSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val audioId: Long?,
    val timestamp: Long,
    val durationMs: Long,
    val monitorId: String? = null,
    val avgHrBpm: Float? = null,
    val hrSlopeBpmPerMin: Float? = null,
    val startRmssdMs: Float? = null,
    val endRmssdMs: Float? = null,
    val lnRmssdSlope: Float? = null
)
