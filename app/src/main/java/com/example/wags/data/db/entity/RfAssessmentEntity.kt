package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rf_assessments")
data class RfAssessmentEntity(
    @PrimaryKey(autoGenerate = true) val assessmentId: Long = 0,
    val timestamp: Long,
    val protocolType: String,
    val optimalBpm: Float,
    val optimalIeRatio: Float,
    val compositeScore: Float,
    val isValid: Boolean,
    val leaderboardJson: String,
    @ColumnInfo(name = "accBreathingUsed", defaultValue = "0")
    val accBreathingUsed: Boolean = false
)
