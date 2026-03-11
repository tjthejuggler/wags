package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contractions",
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
data class ContractionEntity(
    @PrimaryKey(autoGenerate = true) val contractionId: Long = 0,
    val sessionId: Long,
    val roundNumber: Int,
    val timestampMs: Long,                  // absolute epoch ms
    val elapsedInRoundMs: Long,             // ms since round start
    val phase: String                       // "CRUISING" or "STRUGGLE"
)
