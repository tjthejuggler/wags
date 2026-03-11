package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-sample HR / SpO2 telemetry captured during a free-hold breath hold.
 * FK → apnea_records; CASCADE delete so removing a record also removes its telemetry.
 */
@Entity(
    tableName = "free_hold_telemetry",
    foreignKeys = [
        ForeignKey(
            entity = ApneaRecordEntity::class,
            parentColumns = ["recordId"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId")]
)
data class FreeHoldTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    /** Milliseconds since epoch — absolute timestamp of this sample. */
    val timestampMs: Long,
    /** Heart rate in BPM derived from RR intervals, null if not available. */
    val heartRateBpm: Int?,
    /** SpO2 percentage from pulse oximeter, null if not available. */
    val spO2: Int?
)
