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

    @Query("SELECT * FROM daily_readings WHERE timestamp >= :startOfDayMs ORDER BY timestamp DESC LIMIT 1")
    fun observeTodayLatest(startOfDayMs: Long): Flow<DailyReadingEntity?>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DailyReadingEntity>>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC")
    suspend fun getAll(): List<DailyReadingEntity>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC LIMIT 14")
    suspend fun getLast14(): List<DailyReadingEntity>

    @Query("SELECT * FROM daily_readings WHERE readingId = :id LIMIT 1")
    suspend fun getById(id: Long): DailyReadingEntity?

    @Query("DELETE FROM daily_readings WHERE readingId = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM daily_readings WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
