package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.SessionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionLogEntity): Long

    @Query("SELECT * FROM session_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getLatest(limit: Int): Flow<List<SessionLogEntity>>

    @Query("SELECT * FROM session_logs WHERE sessionType = :type ORDER BY timestamp DESC")
    fun getByType(type: String): Flow<List<SessionLogEntity>>
}
