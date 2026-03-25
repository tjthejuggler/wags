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
     * Groups by spotifyUri when available, otherwise by title+artist.
     * Ordered by most recently played first.
     */
    @Query("""
        SELECT * FROM apnea_song_log
        GROUP BY COALESCE(spotifyUri, title || '|' || artist)
        ORDER BY MAX(startedAtMs) DESC
    """)
    suspend fun getDistinctSongs(): List<ApneaSongLogEntity>
}
