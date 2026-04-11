package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists metadata about a guided audio MP3 file in the user's library.
 *
 * [fileName]   – the display name of the file (e.g. "guided_breathwork_20min.mp3")
 * [uri]        – the content:// URI string for playback
 * [sourceUrl]  – optional URL where the audio was obtained (YouTube link, website, etc.)
 */
@Entity(tableName = "guided_audios")
data class GuidedAudioEntity(
    @PrimaryKey(autoGenerate = true) val audioId: Long = 0,
    val fileName: String,
    val uri: String,
    val sourceUrl: String = ""
)
