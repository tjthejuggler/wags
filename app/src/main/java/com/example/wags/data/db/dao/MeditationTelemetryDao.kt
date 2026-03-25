package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.MeditationTelemetryEntity

@Dao
interface MeditationTelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MeditationTelemetryEntity>)

    @Query("SELECT * FROM meditation_telemetry WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getBySessionId(sessionId: Long): List<MeditationTelemetryEntity>

    @Query("DELETE FROM meditation_telemetry WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)
}
