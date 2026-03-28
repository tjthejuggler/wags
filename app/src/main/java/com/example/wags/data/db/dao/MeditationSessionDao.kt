package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.MeditationSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeditationSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: MeditationSessionEntity): Long

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
}
