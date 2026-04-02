package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One HR telemetry sample during a Rapid HR Change session.
 *
 * [sessionId]  – FK to [RapidHrSessionEntity]
 * [offsetMs]   – Milliseconds since session start
 * [hrBpm]      – Heart rate at this sample
 * [phase]      – "WAITING_FIRST" or "TRANSITIONING"
 */
@Entity(
    tableName = "rapid_hr_telemetry",
    foreignKeys = [
        ForeignKey(
            entity = RapidHrSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RapidHrTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val offsetMs: Long,
    val hrBpm: Int,
    val phase: String
)
