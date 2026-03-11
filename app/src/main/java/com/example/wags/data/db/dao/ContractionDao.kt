package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.ContractionEntity

@Dao
interface ContractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contraction: ContractionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contractions: List<ContractionEntity>)

    @Query("SELECT * FROM contractions WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getForSession(sessionId: Long): List<ContractionEntity>

    @Query("SELECT * FROM contractions WHERE sessionId = :sessionId AND roundNumber = :round ORDER BY timestampMs ASC")
    suspend fun getForRound(sessionId: Long, round: Int): List<ContractionEntity>
}
