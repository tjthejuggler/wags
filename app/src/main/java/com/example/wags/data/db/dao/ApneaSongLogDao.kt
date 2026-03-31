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
     * Returns 1 if the user has made it all the way through this song (by title+artist)
     * during any past free hold. A song is "completed" when its actual play time during
     * the hold (endedAtMs - startedAtMs) covers the full song duration. Songs that were
     * still playing when the hold ended (endedAtMs IS NULL) are not considered completed.
     */
    @Query("""
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM apnea_song_log s
            INNER JOIN apnea_records r ON s.recordId = r.recordId
            WHERE LOWER(TRIM(s.title)) = LOWER(TRIM(:title))
              AND LOWER(TRIM(s.artist)) = LOWER(TRIM(:artist))
              AND r.tableType IS NULL
              AND s.endedAtMs IS NOT NULL
              AND (s.endedAtMs - s.startedAtMs) >= :songDurationMs
        ) THEN 1 ELSE 0 END
    """)
    suspend fun wasSongCompletedEver(title: String, artist: String, songDurationMs: Long): Int

    /**
     * Returns 1 if the user has made it all the way through this song during a free hold
     * with the given 5-setting combination. Same completion logic as [wasSongCompletedEver].
     */
    @Query("""
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM apnea_song_log s
            INNER JOIN apnea_records r ON s.recordId = r.recordId
            WHERE LOWER(TRIM(s.title)) = LOWER(TRIM(:title))
              AND LOWER(TRIM(s.artist)) = LOWER(TRIM(:artist))
              AND r.tableType IS NULL
              AND s.endedAtMs IS NOT NULL
              AND (s.endedAtMs - s.startedAtMs) >= :songDurationMs
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
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Int
}
