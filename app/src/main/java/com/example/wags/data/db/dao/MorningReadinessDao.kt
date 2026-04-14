package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.MorningReadinessEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MorningReadinessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MorningReadinessEntity): Long

    @Query("SELECT * FROM morning_readiness ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): MorningReadinessEntity?

    @Query("SELECT * FROM morning_readiness WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MorningReadinessEntity?

    @Query("SELECT * FROM morning_readiness WHERE timestamp >= :startOfDayMs ORDER BY timestamp DESC LIMIT 1")
    fun observeTodayLatest(startOfDayMs: Long): Flow<MorningReadinessEntity?>

    @Query("SELECT * FROM morning_readiness ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastN(limit: Int): List<MorningReadinessEntity>

    @Query("SELECT * FROM morning_readiness WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSince(sinceMs: Long): List<MorningReadinessEntity>

    @Query("SELECT * FROM morning_readiness ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MorningReadinessEntity>>

    @Query("SELECT * FROM morning_readiness ORDER BY timestamp DESC")
    suspend fun getAll(): List<MorningReadinessEntity>

    // For 90-day rolling baseline queries
    @Query("SELECT supineRmssdMs FROM morning_readiness WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSupineRmssdSince(sinceMs: Long): List<Double>

    @Query("SELECT standingRmssdMs FROM morning_readiness WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getStandingRmssdSince(sinceMs: Long): List<Double>

    @Query("SELECT supineRhr FROM morning_readiness WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSupineRhrSince(sinceMs: Long): List<Int>

    @Query("SELECT thirtyFifteenRatio FROM morning_readiness WHERE timestamp >= :sinceMs AND thirtyFifteenRatio IS NOT NULL ORDER BY timestamp DESC")
    suspend fun getThirtyFifteenRatioSince(sinceMs: Long): List<Float>

    @Query("SELECT ohrrAt60sPercent FROM morning_readiness WHERE timestamp >= :sinceMs AND ohrrAt60sPercent IS NOT NULL ORDER BY timestamp DESC")
    suspend fun getOhrrAt60sSince(sinceMs: Long): List<Float>

    @Query("SELECT hooperTotal FROM morning_readiness WHERE timestamp >= :sinceMs AND hooperTotal IS NOT NULL ORDER BY timestamp DESC")
    suspend fun getHooperTotalSince(sinceMs: Long): List<Float>

    @Query("SELECT supineLnRmssd FROM morning_readiness WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSupineLnRmssdSince(sinceMs: Long): List<Double>

    @Query("DELETE FROM morning_readiness WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Cleanup: delete records older than retention period
    @Query("DELETE FROM morning_readiness WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
