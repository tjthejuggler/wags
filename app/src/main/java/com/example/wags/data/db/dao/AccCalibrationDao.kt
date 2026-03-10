package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.AccCalibrationEntity

@Dao
interface AccCalibrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AccCalibrationEntity): Long

    @Query("SELECT * FROM acc_calibrations WHERE posture = :posture AND withHold = :withHold ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForProfile(posture: String, withHold: Boolean): AccCalibrationEntity?
}
