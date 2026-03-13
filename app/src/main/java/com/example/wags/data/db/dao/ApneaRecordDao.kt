package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApneaRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ApneaRecordEntity): Long

    @Update
    suspend fun update(entity: ApneaRecordEntity)

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

    /** recordId of the best (longest) free-hold for a given settings combination. */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND tableType IS NULL
        ORDER BY durationMs DESC LIMIT 1
    """)
    fun getBestFreeHoldRecordId(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?>

    /** Single record by primary key. */
    @Query("SELECT * FROM apnea_records WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: Long): ApneaRecordEntity?

    /** Permanently delete a record (CASCADE will remove its free_hold_telemetry rows). */
    @Query("DELETE FROM apnea_records WHERE recordId = :recordId")
    suspend fun deleteById(recordId: Long)

    // ── Stats queries (filtered by settings) ─────────────────────────────────

    /** Count of free-hold records for a given settings combination. */
    @Query("""
        SELECT COUNT(*) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND tableType IS NULL
    """)
    fun countFreeHolds(lungVolume: String, prepType: String, timeOfDay: String): Flow<Int>

    /** Count of records for a specific tableType and settings combination. */
    @Query("""
        SELECT COUNT(*) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND tableType = :tableType
    """)
    fun countByTableType(lungVolume: String, prepType: String, timeOfDay: String, tableType: String): Flow<Int>

    /** Overall highest HR ever recorded during a session (filtered by settings). */
    @Query("""
        SELECT MAX(maxHrBpm) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND maxHrBpm BETWEEN 20 AND 250
    """)
    fun getMaxHrEver(lungVolume: String, prepType: String, timeOfDay: String): Flow<Float?>

    /** recordId of the record that holds the highest HR (filtered by settings). */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND maxHrBpm BETWEEN 20 AND 250
        ORDER BY maxHrBpm DESC LIMIT 1
    """)
    fun getMaxHrRecordId(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?>

    /** Overall lowest HR ever recorded during a session (filtered by settings). */
    @Query("""
        SELECT MIN(minHrBpm) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND minHrBpm BETWEEN 20 AND 250
    """)
    fun getMinHrEver(lungVolume: String, prepType: String, timeOfDay: String): Flow<Float?>

    /** recordId of the record that holds the lowest HR (filtered by settings). */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND minHrBpm BETWEEN 20 AND 250
        ORDER BY minHrBpm ASC LIMIT 1
    """)
    fun getMinHrRecordId(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?>

    /** Overall lowest SpO2 ever recorded during a session (filtered by settings). */
    @Query("""
        SELECT MIN(lowestSpO2) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND lowestSpO2 IS NOT NULL
          AND lowestSpO2 BETWEEN 1 AND 100
    """)
    fun getLowestSpO2Ever(lungVolume: String, prepType: String, timeOfDay: String): Flow<Int?>

    /** recordId of the record that holds the lowest SpO2 (filtered by settings). */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND lowestSpO2 IS NOT NULL
          AND lowestSpO2 BETWEEN 1 AND 100
        ORDER BY lowestSpO2 ASC LIMIT 1
    """)
    fun getLowestSpO2RecordId(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?>

    // ── Recent records (all types, filtered by settings) ─────────────────────

    /**
     * Returns the [limit] most recent records for a given settings combination,
     * regardless of tableType (free holds + all table types).
     */
    @Query("""
        SELECT * FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getRecentBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        limit: Int
    ): Flow<List<ApneaRecordEntity>>

    // ── Paginated all-records (for the All Records screen) ───────────────────

    /**
     * Returns a page of records with optional filters.
     * Pass empty string "" for any filter to match all values.
     * [tableTypes] is a comma-separated list of tableType values; pass "FREE_HOLD" to match NULL.
     */
    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedAll(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    /**
     * Same as [getPagedAll] but also filters by a specific tableType.
     * Pass NULL for [tableType] to get only free holds.
     */
    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND tableType = :tableType
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedByTableType(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        tableType: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    /**
     * Free holds only (tableType IS NULL) with optional settings filter.
     */
    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND tableType IS NULL
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    // ── Stats queries (all settings combined) ────────────────────────────────

    @Query("SELECT COUNT(*) FROM apnea_records WHERE tableType IS NULL")
    fun countFreeHoldsAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM apnea_records WHERE tableType = :tableType")
    fun countByTableTypeAll(tableType: String): Flow<Int>

    @Query("SELECT MAX(maxHrBpm) FROM apnea_records WHERE maxHrBpm BETWEEN 20 AND 250")
    fun getMaxHrEverAll(): Flow<Float?>

    @Query("SELECT recordId FROM apnea_records WHERE maxHrBpm BETWEEN 20 AND 250 ORDER BY maxHrBpm DESC LIMIT 1")
    fun getMaxHrRecordIdAll(): Flow<Long?>

    @Query("SELECT MIN(minHrBpm) FROM apnea_records WHERE minHrBpm BETWEEN 20 AND 250")
    fun getMinHrEverAll(): Flow<Float?>

    @Query("SELECT recordId FROM apnea_records WHERE minHrBpm BETWEEN 20 AND 250 ORDER BY minHrBpm ASC LIMIT 1")
    fun getMinHrRecordIdAll(): Flow<Long?>

    @Query("SELECT MIN(lowestSpO2) FROM apnea_records WHERE lowestSpO2 IS NOT NULL AND lowestSpO2 BETWEEN 1 AND 100")
    fun getLowestSpO2EverAll(): Flow<Int?>

    @Query("SELECT recordId FROM apnea_records WHERE lowestSpO2 IS NOT NULL AND lowestSpO2 BETWEEN 1 AND 100 ORDER BY lowestSpO2 ASC LIMIT 1")
    fun getLowestSpO2RecordIdAll(): Flow<Long?>
}
