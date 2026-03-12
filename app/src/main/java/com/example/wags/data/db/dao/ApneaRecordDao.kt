package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApneaRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ApneaRecordEntity): Long

    @Query("SELECT * FROM apnea_records ORDER BY timestamp DESC LIMIT :limit")
    fun getLatest(limit: Int): Flow<List<ApneaRecordEntity>>

    @Query("SELECT * FROM apnea_records WHERE tableType = :type ORDER BY timestamp DESC")
    fun getByType(type: String): Flow<List<ApneaRecordEntity>>

    /** Returns all records matching the given lung-volume + prep-type + time-of-day combination. */
    @Query("""
        SELECT * FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
        ORDER BY timestamp DESC
    """)
    fun getBySettings(lungVolume: String, prepType: String, timeOfDay: String): Flow<List<ApneaRecordEntity>>

    /** Best (longest) free-hold for a given settings combination. */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND tableType IS NULL
    """)
    fun getBestFreeHold(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?>

    /** Single record by primary key. */
    @Query("SELECT * FROM apnea_records WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: Long): ApneaRecordEntity?

    /** Permanently delete a record (CASCADE will remove its free_hold_telemetry rows). */
    @Query("DELETE FROM apnea_records WHERE recordId = :recordId")
    suspend fun deleteById(recordId: Long)
}
