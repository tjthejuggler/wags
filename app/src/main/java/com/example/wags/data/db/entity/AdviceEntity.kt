package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-entered piece of advice associated with one of the five main sections.
 *
 * [section] – one of: "apnea", "breathing", "readiness", "morning", "meditation"
 * [text]    – the advice content entered by the user.
 * [createdAt] – epoch millis when the advice was created.
 */
@Entity(tableName = "advice")
data class AdviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val section: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)
