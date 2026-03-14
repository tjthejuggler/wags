package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists metadata about a meditation audio file (or the special "None" entry).
 *
 * [fileName]  – the bare file name (e.g. "nsdr_20min.mp3") or the sentinel value "NONE"
 *               for the no-audio option.
 * [sourceUrl] – optional URL where the audio was obtained (YouTube link, etc.).
 * [isNone]    – true only for the synthetic "None / silent meditation" entry.
 */
@Entity(tableName = "meditation_audios")
data class MeditationAudioEntity(
    @PrimaryKey(autoGenerate = true) val audioId: Long = 0,
    val fileName: String,
    val sourceUrl: String = "",
    val isNone: Boolean = false
)
