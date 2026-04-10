package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.ApneaSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApneaSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ApneaSessionEntity): Long

    @Query("SELECT * FROM apnea_sessions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int = 50): List<ApneaSessionEntity>

    @Query("SELECT * FROM apnea_sessions WHERE tableType = :type ORDER BY timestamp DESC")
    suspend fun getByType(type: String): List<ApneaSessionEntity>

    @Query("SELECT * FROM apnea_sessions ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecent(): ApneaSessionEntity?

    @Query("SELECT MAX(pbAtSessionMs) FROM apnea_sessions")
    suspend fun getMaxPb(): Long?

    @Query("SELECT * FROM apnea_sessions WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: Long): ApneaSessionEntity?

    /** Find a session by its timestamp — used to link ApneaRecordEntity to its session. */
    @Query("SELECT * FROM apnea_sessions WHERE timestamp = :timestamp AND tableType = :tableType LIMIT 1")
    suspend fun getByTimestampAndType(timestamp: Long, tableType: String): ApneaSessionEntity?

    @Query("SELECT * FROM apnea_sessions ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<ApneaSessionEntity>

    // ── Total session time SUM queries (filtered — join with records for settings) ──

    @Query("""
        SELECT COALESCE(SUM(s.totalSessionDurationMs), 0)
        FROM apnea_sessions s
        INNER JOIN apnea_records r ON r.timestamp = s.timestamp AND r.tableType = s.tableType
        WHERE s.tableType = :tableType
          AND (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType)
          AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture) AND (:audio = 'ALL' OR r.audio = :audio)
    """)
    fun sumSessionDuration(
        lungVolume: String, prepType: String, timeOfDay: String,
        posture: String, audio: String, tableType: String
    ): Flow<Long>

    // ── Total session time SUM queries (all settings) ──────────────────────────

    @Query("SELECT COALESCE(SUM(totalSessionDurationMs), 0) FROM apnea_sessions WHERE tableType = :tableType")
    fun sumSessionDurationAll(tableType: String): Flow<Long>
}
