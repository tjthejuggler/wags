package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val timestamp: Long,
    val durationMs: Long,
    val sessionType: String,
    val avgHrBpm: Float,
    val hrSlopeBpmPerMin: Float,
    val startRmssdMs: Float,
    val endRmssdMs: Float,
    val lnRmssdSlope: Float
)
