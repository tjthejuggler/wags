package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity

@Dao
interface FreeHoldTelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<FreeHoldTelemetryEntity>)

    @Query("SELECT * FROM free_hold_telemetry WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getForRecord(recordId: Long): List<FreeHoldTelemetryEntity>

    /** Explicit delete — also triggered automatically via CASCADE when the parent record is deleted. */
    @Query("DELETE FROM free_hold_telemetry WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)
}
