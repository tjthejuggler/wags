package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
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
    val readinessScore: Int,
    /**
     * Human-readable identifier of the HR device used during this reading
     * (e.g. "Polar H10 (ABC123)", "Polar Verity (XYZ)").
     * Null when no device was connected.
     */
    @ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null
)
