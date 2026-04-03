package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.wags.data.db.entity.AdviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AdviceDao {

    /** Observe all advice for a given section, newest first. */
    @Query("SELECT * FROM advice WHERE section = :section ORDER BY createdAt DESC")
    fun observeBySection(section: String): Flow<List<AdviceEntity>>

    /** Get all advice for a section (suspend, for one-shot reads). */
    @Query("SELECT * FROM advice WHERE section = :section ORDER BY createdAt DESC")
    suspend fun getBySection(section: String): List<AdviceEntity>

    /** Get a single advice item by ID. */
    @Query("SELECT * FROM advice WHERE id = :id")
    suspend fun getById(id: Long): AdviceEntity?

    @Insert
    suspend fun insert(entity: AdviceEntity): Long

    @Update
    suspend fun update(entity: AdviceEntity)

    @Delete
    suspend fun delete(entity: AdviceEntity)

    @Query("DELETE FROM advice WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Update only the notes column for a given advice item. */
    @Query("UPDATE advice SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String?)

    /** Get all advice items (for export/backup). */
    @Query("SELECT * FROM advice ORDER BY section, createdAt DESC")
    suspend fun getAll(): List<AdviceEntity>
}
