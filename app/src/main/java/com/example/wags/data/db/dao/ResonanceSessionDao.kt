package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.ResonanceSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ResonanceSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ResonanceSessionEntity): Long

    @Query("SELECT * FROM resonance_sessions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ResonanceSessionEntity>>

    @Query("SELECT * FROM resonance_sessions WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSince(sinceMs: Long): List<ResonanceSessionEntity>

    @Query("SELECT * FROM resonance_sessions WHERE timestamp = :timestamp LIMIT 1")
    suspend fun getByTimestamp(timestamp: Long): ResonanceSessionEntity?

    @Query("DELETE FROM resonance_sessions WHERE timestamp = :timestamp")
    suspend fun deleteByTimestamp(timestamp: Long)
}
