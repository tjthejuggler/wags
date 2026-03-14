package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists metadata about a meditation audio file (or the special "None" entry).
 *
 * [fileName]       – the bare file name (e.g. "nsdr_20min.mp3") or the sentinel value "NONE"
 *                    for the no-audio option.
 * [sourceUrl]      – optional URL where the audio was obtained (YouTube link, etc.).
 * [youtubeTitle]   – video title fetched from YouTube oEmbed; null if not a YouTube URL or
 *                    not yet fetched.
 * [youtubeChannel] – channel/author name fetched from YouTube oEmbed; null if not a YouTube URL
 *                    or not yet fetched.
 * [isNone]         – true only for the synthetic "None / silent meditation" entry.
 */
@Entity(tableName = "meditation_audios")
data class MeditationAudioEntity(
    @PrimaryKey(autoGenerate = true) val audioId: Long = 0,
    val fileName: String,
    val sourceUrl: String = "",
    val youtubeTitle: String? = null,
    val youtubeChannel: String? = null,
    val isNone: Boolean = false
) {
    /** The display name shown in the UI: YouTube title if available, otherwise the file name. */
    val displayName: String
        get() = youtubeTitle?.takeIf { it.isNotBlank() } ?: fileName
}
