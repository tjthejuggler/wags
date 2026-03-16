package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.MorningReadinessTelemetryEntity

@Dao
interface MorningReadinessTelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MorningReadinessTelemetryEntity>)

    @Query("SELECT * FROM morning_readiness_telemetry WHERE readingId = :readingId ORDER BY timestampMs ASC")
    suspend fun getByReadingId(readingId: Long): List<MorningReadinessTelemetryEntity>

    @Query("DELETE FROM morning_readiness_telemetry WHERE readingId = :readingId")
    suspend fun deleteByReadingId(readingId: Long)
}
