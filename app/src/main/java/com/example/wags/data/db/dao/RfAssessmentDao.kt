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
}
