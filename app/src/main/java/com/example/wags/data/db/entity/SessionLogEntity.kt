package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val timestamp: Long,
    val durationMs: Long,
    val sessionType: String,
    /** Null when session was recorded without a heart rate monitor. */
    val monitorId: String? = null,
    val avgHrBpm: Float? = null,
    val hrSlopeBpmPerMin: Float? = null,
    val startRmssdMs: Float? = null,
    val endRmssdMs: Float? = null,
    val lnRmssdSlope: Float? = null
)
