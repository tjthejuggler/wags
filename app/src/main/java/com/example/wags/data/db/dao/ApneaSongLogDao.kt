package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.ApneaSongLogEntity

@Dao
interface ApneaSongLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<ApneaSongLogEntity>)

    @Query("SELECT * FROM apnea_song_log WHERE recordId = :recordId ORDER BY startedAtMs ASC")
    suspend fun getForRecord(recordId: Long): List<ApneaSongLogEntity>

    @Query("DELETE FROM apnea_song_log WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)

    @Query("UPDATE apnea_song_log SET startedAtMs = :startedAtMs WHERE songLogId = :songLogId")
    suspend fun updateStartedAt(songLogId: Long, startedAtMs: Long)

    @Query("DELETE FROM apnea_song_log")
    suspend fun deleteAll()

    /**
     * Returns distinct songs that have been played during breath holds.
     * Groups by title+artist (case-insensitive) to prevent duplicates when
     * the same song was stored with different Spotify URIs.
     * Ordered by most recently played first.
     */
    @Query("""
        SELECT * FROM apnea_song_log
        GROUP BY LOWER(TRIM(title)) || '|' || LOWER(TRIM(artist))
        ORDER BY MAX(startedAtMs) DESC
    """)
    suspend fun getDistinctSongs(): List<ApneaSongLogEntity>

    /**
     * Returns 1 if the user has made it all the way through this song during any past
     * free hold. Matches by Spotify URI when available, falling back to normalized
     * title+artist (caller normalizes Unicode apostrophes in Kotlin). A song is
     * "completed" when either:
     *  - Its play time (endedAtMs - startedAtMs) >= the song's known duration, OR
     *  - A subsequent song exists in the same record (Spotify auto-played next track).
     * Songs still playing when the hold ended (endedAtMs IS NULL) are not completed.
     */
    @Query("""
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM apnea_song_log s
            INNER JOIN apnea_records r ON s.recordId = r.recordId
            WHERE (
                  (:spotifyUri != '' AND s.spotifyUri = :spotifyUri)
                  OR (
                      LOWER(TRIM(s.title)) = LOWER(TRIM(:title))
                      AND LOWER(TRIM(s.artist)) = LOWER(TRIM(:artist))
                  )
              )
              AND r.tableType IS NULL
              AND s.endedAtMs IS NOT NULL
              AND (
                  (s.endedAtMs - s.startedAtMs) >= :songDurationMs
                  OR EXISTS (
                      SELECT 1 FROM apnea_song_log s2
                      WHERE s2.recordId = s.recordId
                        AND s2.songLogId != s.songLogId
                        AND s2.startedAtMs >= s.endedAtMs
                  )
              )
        ) THEN 1 ELSE 0 END
    """)
    suspend fun wasSongCompletedEver(
        title: String,
        artist: String,
        songDurationMs: Long,
        spotifyUri: String
    ): Int

    /**
     * Returns 1 if the user has made it all the way through this song during a free hold
     * with the given 5-setting combination. Same matching/completion logic as above.
     */
    @Query("""
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM apnea_song_log s
            INNER JOIN apnea_records r ON s.recordId = r.recordId
            WHERE (
                  (:spotifyUri != '' AND s.spotifyUri = :spotifyUri)
                  OR (
                      LOWER(TRIM(s.title)) = LOWER(TRIM(:title))
                      AND LOWER(TRIM(s.artist)) = LOWER(TRIM(:artist))
                  )
              )
              AND r.tableType IS NULL
              AND s.endedAtMs IS NOT NULL
              AND (
                  (s.endedAtMs - s.startedAtMs) >= :songDurationMs
                  OR EXISTS (
                      SELECT 1 FROM apnea_song_log s2
                      WHERE s2.recordId = s.recordId
                        AND s2.songLogId != s.songLogId
                        AND s2.startedAtMs >= s.endedAtMs
                  )
              )
              AND r.lungVolume = :lungVolume
              AND r.prepType   = :prepType
              AND r.timeOfDay  = :timeOfDay
              AND r.posture    = :posture
              AND r.audio      = :audio
        ) THEN 1 ELSE 0 END
    """)
    suspend fun wasSongCompletedWithSettings(
        title: String,
        artist: String,
        songDurationMs: Long,
        spotifyUri: String,
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Int
}
