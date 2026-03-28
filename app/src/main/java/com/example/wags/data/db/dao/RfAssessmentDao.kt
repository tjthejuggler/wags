package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.RfAssessmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RfAssessmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RfAssessmentEntity): Long

    @Query("SELECT * FROM rf_assessments ORDER BY timestamp DESC LIMIT :limit")
    fun getLatest(limit: Int): Flow<List<RfAssessmentEntity>>

    @Query("SELECT * FROM rf_assessments WHERE isValid = 1 ORDER BY compositeScore DESC LIMIT 1")
    suspend fun getBestValid(): RfAssessmentEntity?

    @Query("SELECT * FROM rf_assessments WHERE protocolType = :protocol ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForProtocol(protocol: String): RfAssessmentEntity?

    @Query("SELECT COUNT(*) > 0 FROM rf_assessments")
    suspend fun hasAnySession(): Boolean

    @Query("SELECT * FROM rf_assessments WHERE timestamp = :timestamp LIMIT 1")
    suspend fun getByTimestamp(timestamp: Long): RfAssessmentEntity?

    @Query("SELECT * FROM rf_assessments ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RfAssessmentEntity>>

    @Query("DELETE FROM rf_assessments WHERE timestamp = :timestamp")
    suspend fun deleteByTimestamp(timestamp: Long)

    @Query("SELECT * FROM rf_assessments WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getSince(sinceMs: Long): List<RfAssessmentEntity>
}
