package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.ForecastCalibrationEntity

@Dao
interface ForecastCalibrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ForecastCalibrationEntity): Long

    @Query("UPDATE forecast_calibration SET actualDurationMs = :durationMs, actualBroken = :broken, actualTimestamp = :ts WHERE id = :id")
    suspend fun updateActual(id: Long, durationMs: Long, broken: String, ts: Long)

    /** Most recent forecast that hasn't been resolved yet (actualDurationMs IS NULL). */
    @Query("SELECT * FROM forecast_calibration WHERE actualDurationMs IS NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestUnresolved(): ForecastCalibrationEntity?

    /** All calibration rows, newest first. */
    @Query("SELECT * FROM forecast_calibration ORDER BY timestamp DESC")
    suspend fun getAll(): List<ForecastCalibrationEntity>
}
