package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.DailyReadingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyReadingEntity): Long

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC LIMIT :limit")
    fun getLatest(limit: Int): Flow<List<DailyReadingEntity>>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC LIMIT 14")
    suspend fun getLast14(): List<DailyReadingEntity>

    @Query("DELETE FROM daily_readings WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
