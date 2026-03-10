package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "acc_calibrations")
data class AccCalibrationEntity(
    @PrimaryKey(autoGenerate = true) val calibrationId: Long = 0,
    val timestamp: Long,
    val posture: String,
    val withHold: Boolean,
    val inhaleDeltaThreshold: Float,
    val exhaleDeltaThreshold: Float,
    val holdDebounceCount: Int
)
