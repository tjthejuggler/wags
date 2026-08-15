package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.wags.data.db.entity.MeditationSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeditationSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: MeditationSessionEntity): Long

    @Update
    suspend fun update(session: MeditationSessionEntity)

    @Query("SELECT * FROM meditation_sessions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MeditationSessionEntity>>

    @Query("SELECT * FROM meditation_sessions ORDER BY timestamp DESC")
    suspend fun getAll(): List<MeditationSessionEntity>

    @Query("SELECT * FROM meditation_sessions WHERE sessionId = :id LIMIT 1")
    suspend fun getById(id: Long): MeditationSessionEntity?

    @Query("SELECT * FROM meditation_sessions WHERE audioId = :audioId ORDER BY timestamp DESC")
    fun observeByAudio(audioId: Long): Flow<List<MeditationSessionEntity>>

    @Query("SELECT * FROM meditation_sessions WHERE posture = :posture ORDER BY timestamp DESC")
    fun observeByPosture(posture: String): Flow<List<MeditationSessionEntity>>

    @Query("DELETE FROM meditation_sessions WHERE sessionId = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE meditation_sessions SET durationMs = :durationMs WHERE sessionId = :id")
    suspend fun updateDurationMs(id: Long, durationMs: Long)

    // ── Incremental persistence & recovery support ─────────────────────────────

    /** Returns sessions that were interrupted (process killed before finalization). */
    @Query("SELECT * FROM meditation_sessions WHERE completed = 0 ORDER BY timestamp DESC")
    suspend fun getIncompleteSessions(): List<MeditationSessionEntity>

    /** Returns the most recent in-progress session, or null if none. */
    @Query("SELECT * FROM meditation_sessions WHERE completed = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecentIncompleteSession(): MeditationSessionEntity?

    /** Marks a session as finalized with the given duration. */
    @Query("UPDATE meditation_sessions SET completed = 1, durationMs = :durationMs WHERE sessionId = :id")
    suspend fun finalizeSession(id: Long, durationMs: Long)

    /**
     * Deletes stale incomplete sessions shorter than the given duration
     * (cleans up accidental/empty sessions).
     *
     * The `createdBeforeMs` cutoff is critical: a *just-created* session row has
     * `durationMs = 0` and `completed = 0`, so without the recency guard this
     * query would delete the ACTIVE session row while the session is running
     * (race with [com.example.wags.data.meditation.MeditationService.startSession]),
     * which orphans all subsequent telemetry inserts (FK violation) and loses
     * the whole session. Only sessions started before the cutoff are eligible.
     */
    @Query(
        "DELETE FROM meditation_sessions WHERE completed = 0 AND durationMs < :minDurationMs " +
            "AND timestamp < :createdBeforeMs"
    )
    suspend fun deleteIncompleteShorterThan(
        minDurationMs: Long,
        createdBeforeMs: Long
    )
}
