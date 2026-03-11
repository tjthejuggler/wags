package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apnea_sessions")
data class ApneaSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val timestamp: Long,                    // epoch ms
    val tableType: String,                  // "CO2", "O2", "PROGRESSIVE_O2", "MIN_BREATH", "WONKA_FIRST", "WONKA_ENDURANCE", "FREE"
    val tableVariant: String,               // "SHORT", "MEDIUM", "LONG"
    val tableParamsJson: String,            // JSON of the generated table steps
    val pbAtSessionMs: Long,                // user's PB at time of session
    val totalSessionDurationMs: Long,
    val contractionTimestampsJson: String,  // JSON array of Long timestamps
    val maxHrBpm: Int?,
    val lowestSpO2: Int?,
    val roundsCompleted: Int,
    val totalRounds: Int
)
