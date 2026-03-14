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

    /**
     * All audios ordered so that:
     *  1. The "None" sentinel always comes first.
     *  2. YouTube audios (those with a channel) are grouped by channel name, then by title.
     *  3. Non-YouTube audios (no channel) come last, sorted by fileName.
     */
    @Query("""
        SELECT * FROM meditation_audios
        ORDER BY
            isNone DESC,
            CASE WHEN youtubeChannel IS NULL OR youtubeChannel = '' THEN 1 ELSE 0 END ASC,
            youtubeChannel ASC,
            COALESCE(youtubeTitle, fileName) ASC
    """)
    fun observeAll(): Flow<List<MeditationAudioEntity>>

    @Query("""
        SELECT * FROM meditation_audios
        ORDER BY
            isNone DESC,
            CASE WHEN youtubeChannel IS NULL OR youtubeChannel = '' THEN 1 ELSE 0 END ASC,
            youtubeChannel ASC,
            COALESCE(youtubeTitle, fileName) ASC
    """)
    suspend fun getAll(): List<MeditationAudioEntity>

    @Query("SELECT * FROM meditation_audios WHERE audioId = :id LIMIT 1")
    suspend fun getById(id: Long): MeditationAudioEntity?

    @Query("SELECT * FROM meditation_audios WHERE isNone = 1 LIMIT 1")
    suspend fun getNoneEntry(): MeditationAudioEntity?

    @Query("SELECT * FROM meditation_audios WHERE fileName = :fileName AND isNone = 0 LIMIT 1")
    suspend fun getByFileName(fileName: String): MeditationAudioEntity?

    /** Returns all distinct non-null, non-blank channel names, sorted alphabetically. */
    @Query("""
        SELECT DISTINCT youtubeChannel FROM meditation_audios
        WHERE isNone = 0 AND youtubeChannel IS NOT NULL AND youtubeChannel != ''
        ORDER BY youtubeChannel ASC
    """)
    suspend fun getDistinctChannels(): List<String>

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
