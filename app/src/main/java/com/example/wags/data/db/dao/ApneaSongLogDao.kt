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
}
