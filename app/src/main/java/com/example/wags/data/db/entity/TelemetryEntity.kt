package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telemetry",
    foreignKeys = [
        ForeignKey(
            entity = ApneaSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val telemetryId: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val spO2: Int?,
    val heartRateBpm: Int?,
    val source: String                      // "POLAR" or "OXIMETER"
)
