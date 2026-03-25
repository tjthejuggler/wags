package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores Spotify track metadata for songs that played during an apnea session.
 * Cascade-deleted when the parent [ApneaRecordEntity] is deleted.
 */
@Entity(
    tableName = "apnea_song_log",
    foreignKeys = [
        ForeignKey(
            entity = ApneaRecordEntity::class,
            parentColumns = ["recordId"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId")]
)
data class ApneaSongLogEntity(
    @PrimaryKey(autoGenerate = true) val songLogId: Long = 0,
    /** The apnea record this song was played during. */
    val recordId: Long,
    /** Track title. */
    val title: String,
    /** Artist name. */
    val artist: String,
    /** Album art URL, null if unavailable. */
    val albumArt: String? = null,
    /** Spotify track URI (e.g. "spotify:track:xxx"), null if unavailable. */
    val spotifyUri: String? = null,
    /** Epoch ms when this song started playing during the session. */
    val startedAtMs: Long,
    /** Epoch ms when this song stopped. Null if still playing when session ended. */
    val endedAtMs: Long? = null
)
