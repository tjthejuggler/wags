package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.DailyReadingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyReadingDao {

    /** LocalTimeBackfillRunner: bulk-stamp the frozen zone on a pre-fix row. */
    @Query("UPDATE daily_readings SET zoneId = :zoneId WHERE readingId = :rowId")
    suspend fun stampZone(rowId: Long, zoneId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyReadingEntity): Long

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC LIMIT :limit")
    fun getLatest(limit: Int): Flow<List<DailyReadingEntity>>

    /** Timestamp (epoch ms) of the most recent HRV readiness reading. Null when none exist. */
    @Query("SELECT MAX(timestamp) FROM daily_readings")
    fun observeLatestTimestamp(): Flow<Long?>

    @Query("SELECT * FROM daily_readings WHERE timestamp >= :startOfDayMs ORDER BY timestamp DESC LIMIT 1")
    fun observeTodayLatest(startOfDayMs: Long): Flow<DailyReadingEntity?>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DailyReadingEntity>>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC")
    suspend fun getAll(): List<DailyReadingEntity>

    @Query("SELECT * FROM daily_readings ORDER BY timestamp DESC LIMIT 14")
    suspend fun getLast14(): List<DailyReadingEntity>

    /**
     * Baseline history for the readiness Z-score, excluding quality-flagged
     * readings (implausible legacy RMSSD or low-coverage captures) since
     * pipeline v2 (migration 44→45).
     */
    @Query("SELECT * FROM daily_readings WHERE qualityFlag = 0 ORDER BY timestamp DESC LIMIT 14")
    suspend fun getLast14ForBaseline(): List<DailyReadingEntity>

    @Query("SELECT * FROM daily_readings WHERE readingId = :id LIMIT 1")
    suspend fun getById(id: Long): DailyReadingEntity?

    @Query("DELETE FROM daily_readings WHERE readingId = :id")
    suspend fun deleteById(id: Long)

    // Intentionally NO time-based deletion query.
    // Policy (2026-07-08): never delete data on a time basis.
}
