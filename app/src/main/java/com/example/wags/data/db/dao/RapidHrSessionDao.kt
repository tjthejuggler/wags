package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.wags.data.db.entity.RapidHrSessionEntity
import kotlinx.coroutines.flow.Flow

/** Aggregated preset row returned by [getPresetsByDirection]. */
data class RapidHrPreset(
    val direction: String,
    val highThreshold: Int,
    val lowThreshold: Int,
    val bestTimeMs: Long,
    val attemptCount: Int
)

@Dao
interface RapidHrSessionDao {

    @Insert
    suspend fun insert(session: RapidHrSessionEntity): Long

    @Query("SELECT * FROM rapid_hr_sessions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RapidHrSessionEntity>>

    @Query("SELECT * FROM rapid_hr_sessions ORDER BY timestamp DESC")
    suspend fun getAll(): List<RapidHrSessionEntity>

    @Query("SELECT * FROM rapid_hr_sessions WHERE direction = :direction ORDER BY timestamp DESC")
    fun observeByDirection(direction: String): Flow<List<RapidHrSessionEntity>>

    @Query("SELECT * FROM rapid_hr_sessions WHERE id = :id")
    suspend fun getById(id: Long): RapidHrSessionEntity?

    /**
     * Returns distinct setting combos for the given direction, ordered by most-used first.
     * Each row includes the best (minimum) transition time for that combo.
     */
    @Query("""
        SELECT direction, highThreshold, lowThreshold,
               MIN(transitionDurationMs) AS bestTimeMs,
               COUNT(*) AS attemptCount
        FROM rapid_hr_sessions
        WHERE direction = :direction
        GROUP BY highThreshold, lowThreshold
        ORDER BY COUNT(*) DESC
    """)
    fun getPresetsByDirection(direction: String): Flow<List<RapidHrPreset>>

    /** Returns the best (minimum) transition time for specific settings, or null if no records. */
    @Query("""
        SELECT MIN(transitionDurationMs) FROM rapid_hr_sessions
        WHERE direction = :direction AND highThreshold = :high AND lowThreshold = :low
    """)
    suspend fun getBestTransitionTime(direction: String, high: Int, low: Int): Long?

    @Query("SELECT COUNT(*) FROM rapid_hr_sessions")
    suspend fun getCount(): Int
}
