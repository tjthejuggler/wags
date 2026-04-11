package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.GuidedAudioEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuidedAudioDao {
    @Query("SELECT * FROM guided_audios ORDER BY audioId DESC")
    fun observeAll(): Flow<List<GuidedAudioEntity>>

    @Query("SELECT * FROM guided_audios ORDER BY audioId DESC")
    suspend fun getAll(): List<GuidedAudioEntity>

    @Query("SELECT * FROM guided_audios WHERE audioId = :id")
    suspend fun getById(id: Long): GuidedAudioEntity?

    @Insert
    suspend fun insert(entity: GuidedAudioEntity): Long

    @Delete
    suspend fun delete(entity: GuidedAudioEntity)

    @Query("DELETE FROM guided_audios WHERE audioId = :id")
    suspend fun deleteById(id: Long)
}
