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

    @Query("SELECT * FROM resonance_sessions ORDER BY timestamp DESC")
    suspend fun getAll(): List<ResonanceSessionEntity>

    @Query("SELECT * FROM resonance_sessions WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSince(sinceMs: Long): List<ResonanceSessionEntity>

    @Query("SELECT * FROM resonance_sessions WHERE timestamp = :timestamp LIMIT 1")
    suspend fun getByTimestamp(timestamp: Long): ResonanceSessionEntity?

    @Query("SELECT * FROM resonance_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: Long): ResonanceSessionEntity?

    @Query("DELETE FROM resonance_sessions WHERE timestamp = :timestamp")
    suspend fun deleteByTimestamp(timestamp: Long)

    @Query("DELETE FROM resonance_sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: Long)
    
    @Query("UPDATE resonance_sessions SET posture = :posture WHERE sessionId = :sessionId")
    suspend fun updatePosture(sessionId: Long, posture: String)

    /** End timestamp (epoch ms) of the most recent resonance session. Null when none exist. */
    @Query("SELECT MAX(timestamp) FROM resonance_sessions")
    fun observeLatestEnd(): Flow<Long?>

    /** One-shot variant of [observeLatestEnd]. */
    @Query("SELECT MAX(timestamp) FROM resonance_sessions")
    suspend fun getLatestEndOnce(): Long?

    /**
     * Latest resonance session that ended at or before [atMs] (its timestamp IS the
     * end moment — the entity is saved when the session completes). Null when none.
     */
    @Query("SELECT * FROM resonance_sessions WHERE timestamp <= :atMs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEndingBefore(atMs: Long): ResonanceSessionEntity?
}
