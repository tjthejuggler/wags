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

    /** Returns all free-hold records matching the given lung-volume + prep-type combination. */
    @Query("""
        SELECT * FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
        ORDER BY timestamp DESC
    """)
    fun getBySettings(lungVolume: String, prepType: String): Flow<List<ApneaRecordEntity>>

    /** Best (longest) free-hold for a given settings combination. */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND tableType IS NULL
    """)
    fun getBestFreeHold(lungVolume: String, prepType: String): Flow<Long?>
}
