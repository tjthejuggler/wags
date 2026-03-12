package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
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
    val tableType: String?,
    /** Lowest SpO₂ percentage recorded during this hold (null if no oximeter was connected). */
    @ColumnInfo(defaultValue = "NULL")
    val lowestSpO2: Int? = null,
    /** One of TimeOfDay enum names: MORNING, DAY, NIGHT */
    @ColumnInfo(defaultValue = "DAY")
    val timeOfDay: String = "DAY"
)
