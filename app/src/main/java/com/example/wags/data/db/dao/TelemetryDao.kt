package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.TelemetryEntity

@Dao
interface TelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(telemetry: TelemetryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(telemetry: List<TelemetryEntity>)

    @Query("SELECT * FROM telemetry WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getForSession(sessionId: Long): List<TelemetryEntity>

    @Query("SELECT MIN(spO2) FROM telemetry WHERE sessionId = :sessionId AND spO2 IS NOT NULL")
    suspend fun getMinSpO2(sessionId: Long): Int?

    @Query("SELECT MAX(heartRateBpm) FROM telemetry WHERE sessionId = :sessionId AND heartRateBpm IS NOT NULL")
    suspend fun getMaxHr(sessionId: Long): Int?
}
