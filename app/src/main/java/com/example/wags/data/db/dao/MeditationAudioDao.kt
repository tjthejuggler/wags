package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.wags.data.db.entity.MeditationAudioEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeditationAudioDao {

    @Query("SELECT * FROM meditation_audios ORDER BY isNone DESC, fileName ASC")
    fun observeAll(): Flow<List<MeditationAudioEntity>>

    @Query("SELECT * FROM meditation_audios ORDER BY isNone DESC, fileName ASC")
    suspend fun getAll(): List<MeditationAudioEntity>

    @Query("SELECT * FROM meditation_audios WHERE audioId = :id LIMIT 1")
    suspend fun getById(id: Long): MeditationAudioEntity?

    @Query("SELECT * FROM meditation_audios WHERE isNone = 1 LIMIT 1")
    suspend fun getNoneEntry(): MeditationAudioEntity?

    @Query("SELECT * FROM meditation_audios WHERE fileName = :fileName AND isNone = 0 LIMIT 1")
    suspend fun getByFileName(fileName: String): MeditationAudioEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(audio: MeditationAudioEntity): Long

    @Update
    suspend fun update(audio: MeditationAudioEntity)

    /** Remove all non-None entries whose fileName is not in [activeFileNames]. */
    @Query(
        "DELETE FROM meditation_audios WHERE isNone = 0 AND fileName NOT IN (:activeFileNames)"
    )
    suspend fun deleteStale(activeFileNames: List<String>)

    @Query("DELETE FROM meditation_audios WHERE audioId = :id AND isNone = 0")
    suspend fun deleteById(id: Long)
}
