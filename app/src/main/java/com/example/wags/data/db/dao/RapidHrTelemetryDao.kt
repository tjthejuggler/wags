package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.wags.data.db.entity.RapidHrTelemetryEntity

@Dao
interface RapidHrTelemetryDao {

    @Insert
    suspend fun insert(sample: RapidHrTelemetryEntity)

    @Insert
    suspend fun insertAll(samples: List<RapidHrTelemetryEntity>)

    @Query("SELECT * FROM rapid_hr_telemetry WHERE sessionId = :sessionId ORDER BY offsetMs ASC")
    suspend fun getBySessionId(sessionId: Long): List<RapidHrTelemetryEntity>

    @Query("DELETE FROM rapid_hr_telemetry WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)
}
