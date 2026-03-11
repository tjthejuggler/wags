package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apnea_records")
data class ApneaRecordEntity(
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    val timestamp: Long,
    val durationMs: Long,
    val lungVolume: String,
    /** One of PrepType enum names: NO_PREP, RESONANCE, HYPER */
    val prepType: String,
    val minHrBpm: Float,
    val maxHrBpm: Float,
    val tableType: String?
)
