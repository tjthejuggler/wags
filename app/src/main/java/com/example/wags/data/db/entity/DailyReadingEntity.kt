package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_readings")
data class DailyReadingEntity(
    @PrimaryKey(autoGenerate = true) val readingId: Long = 0,
    val timestamp: Long,
    val restingHrBpm: Float,
    val rawRmssdMs: Float,
    val lnRmssd: Float,
    val hfPowerMs2: Float,
    val sdnnMs: Float,
    val readinessScore: Int
)
