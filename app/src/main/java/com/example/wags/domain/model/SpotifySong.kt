package com.example.wags.domain.model

/**
 * Metadata for a single Spotify track that played during an apnea session.
 *
 * @property title        Track title.
 * @property artist       Artist name.
 * @property albumArt     Album art URL (may be null if unavailable).
 * @property spotifyUri   Spotify track URI (e.g. "spotify:track:xxx"), null if unavailable.
 * @property startedAtMs  Epoch ms when this song started playing during the session.
 * @property endedAtMs    Epoch ms when this song stopped (next track started or session ended).
 *                        Null if the song was still playing when the session ended.
 */
data class SpotifySong(
    val title: String,
    val artist: String,
    val albumArt: String? = null,
    val spotifyUri: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long? = null
)
