package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-entered piece of advice associated with one of the main sections.
 *
 * [section]   – one of: "home", "apnea", "apnea_hyper", "breathing", "readiness", "morning", "meditation", "rapid_hr_change"
 * [text]      – the advice content entered by the user.
 * [notes]     – optional user notes/thoughts about this advice (nullable, empty = no notes).
 * [createdAt] – epoch millis when the advice was created.
 */
@Entity(tableName = "advice")
data class AdviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val section: String,
    val text: String,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
