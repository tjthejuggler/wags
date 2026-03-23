package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlinx.coroutines.flow.Flow

/** Lightweight projection returned by [ApneaRecordDao.getBestFreeHoldRecord]. */
data class BestRecordTuple(
    val recordId: Long,
    val durationMs: Long,
    val timestamp: Long
)

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

    /** Returns all records matching the given 4-setting combination. */
    @Query("""
        SELECT * FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
        ORDER BY timestamp DESC
    """)
    fun getBySettings(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<List<ApneaRecordEntity>>

    /** Best (longest) free-hold for a given settings combination. */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND tableType IS NULL
    """)
    fun getBestFreeHold(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    /** recordId of the best (longest) free-hold for a given settings combination. */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND tableType IS NULL
        ORDER BY durationMs DESC LIMIT 1
    """)
    fun getBestFreeHoldRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    /** One-shot (suspend) best free-hold duration for a given settings combination. */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND tableType IS NULL
    """)
    suspend fun getBestFreeHoldOnce(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Long?

    /** One-shot recordId of the best free-hold for a given settings combination. */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND tableType IS NULL
        ORDER BY durationMs DESC LIMIT 1
    """)
    suspend fun getBestFreeHoldRecordIdOnce(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Long?

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
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND tableType IS NULL
    """)
    fun countFreeHolds(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int>

    /** Count of records for a specific tableType and settings combination. */
    @Query("""
        SELECT COUNT(*) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND tableType = :tableType
    """)
    fun countByTableType(lungVolume: String, prepType: String, timeOfDay: String, posture: String, tableType: String): Flow<Int>

    /** Overall highest HR ever recorded during a session (filtered by settings). */
    @Query("""
        SELECT MAX(maxHrBpm) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND maxHrBpm BETWEEN 20 AND 250
    """)
    fun getMaxHrEver(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Float?>

    /** recordId of the record that holds the highest HR (filtered by settings). */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND maxHrBpm BETWEEN 20 AND 250
        ORDER BY maxHrBpm DESC LIMIT 1
    """)
    fun getMaxHrRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    /** Overall lowest HR ever recorded during a session (filtered by settings). */
    @Query("""
        SELECT MIN(minHrBpm) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND minHrBpm BETWEEN 20 AND 250
    """)
    fun getMinHrEver(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Float?>

    /** recordId of the record that holds the lowest HR (filtered by settings). */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND minHrBpm BETWEEN 20 AND 250
        ORDER BY minHrBpm ASC LIMIT 1
    """)
    fun getMinHrRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    /** Overall lowest SpO2 ever recorded during a session (filtered by settings). */
    @Query("""
        SELECT MIN(lowestSpO2) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND lowestSpO2 IS NOT NULL
          AND lowestSpO2 BETWEEN 1 AND 100
    """)
    fun getLowestSpO2Ever(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    /** recordId of the record that holds the lowest SpO2 (filtered by settings). */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay AND posture = :posture
          AND lowestSpO2 IS NOT NULL
          AND lowestSpO2 BETWEEN 1 AND 100
        ORDER BY lowestSpO2 ASC LIMIT 1
    """)
    fun getLowestSpO2RecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

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
          AND posture    = :posture
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getRecentBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        limit: Int
    ): Flow<List<ApneaRecordEntity>>

    // ── Paginated all-records (for the All Records screen) ───────────────────

    /**
     * Returns a page of records with optional filters.
     * Pass empty string "" for any filter to match all values.
     */
    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR posture    = :posture)
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedAll(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
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
          AND (:posture    = '' OR posture    = :posture)
          AND tableType = :tableType
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedByTableType(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
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
          AND (:posture    = '' OR posture    = :posture)
          AND tableType IS NULL
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    // ── Personal-best free holds (records that were a PB when they happened) ─

    /**
     * Returns free-hold records that were a personal best at the time they were recorded.
     * A record is a PB if no earlier free-hold record (same settings) had a longer duration.
     * Pass "" for any filter to match all values.
     */
    @Query("""
        SELECT r.* FROM apnea_records r
        WHERE r.tableType IS NULL
          AND (:lungVolume = '' OR r.lungVolume = :lungVolume)
          AND (:prepType   = '' OR r.prepType   = :prepType)
          AND (:timeOfDay  = '' OR r.timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR r.posture    = :posture)
          AND NOT EXISTS (
              SELECT 1 FROM apnea_records older
              WHERE older.tableType IS NULL
                AND (:lungVolume = '' OR older.lungVolume = :lungVolume)
                AND (:prepType   = '' OR older.prepType   = :prepType)
                AND (:timeOfDay  = '' OR older.timeOfDay  = :timeOfDay)
                AND (:posture    = '' OR older.posture    = :posture)
                AND older.timestamp < r.timestamp
                AND older.durationMs >= r.durationMs
          )
        ORDER BY r.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedPersonalBestFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    // ── Broader personal-best queries (one-shot, for PB celebration) ──────────

    /** Best free-hold across ALL settings (global PB). */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL
    """)
    suspend fun getBestFreeHoldGlobal(): Long?

    // ── Single-setting queries (1 constraint, 3 relaxed) ─────────────────────

    /** Best free-hold for a given timeOfDay (any lungVolume, any prepType, any posture). */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay
    """)
    suspend fun getBestFreeHoldByTimeOfDay(timeOfDay: String): Long?

    /** Best free-hold for a given lungVolume (any prepType, any timeOfDay, any posture). */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND lungVolume = :lungVolume
    """)
    suspend fun getBestFreeHoldByLungVolume(lungVolume: String): Long?

    /** Best free-hold for a given prepType (any lungVolume, any timeOfDay, any posture). */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND prepType = :prepType
    """)
    suspend fun getBestFreeHoldByPrepType(prepType: String): Long?

    /** Best free-hold for a given posture (any lungVolume, any prepType, any timeOfDay). */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND posture = :posture
    """)
    suspend fun getBestFreeHoldByPosture(posture: String): Long?

    // ── Two-setting queries (2 constraints, 2 relaxed) ───────────────────────

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay AND lungVolume = :lungVolume
    """)
    suspend fun getBestFreeHoldByTimeOfDayAndLungVolume(timeOfDay: String, lungVolume: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay AND prepType = :prepType
    """)
    suspend fun getBestFreeHoldByTimeOfDayAndPrepType(timeOfDay: String, prepType: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay AND posture = :posture
    """)
    suspend fun getBestFreeHoldByTimeOfDayAndPosture(timeOfDay: String, posture: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND lungVolume = :lungVolume AND prepType = :prepType
    """)
    suspend fun getBestFreeHoldByLungVolumeAndPrepType(lungVolume: String, prepType: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND lungVolume = :lungVolume AND posture = :posture
    """)
    suspend fun getBestFreeHoldByLungVolumeAndPosture(lungVolume: String, posture: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND prepType = :prepType AND posture = :posture
    """)
    suspend fun getBestFreeHoldByPrepTypeAndPosture(prepType: String, posture: String): Long?

    // ── Three-setting queries (3 constraints, 1 relaxed) ─────────────────────

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay AND lungVolume = :lungVolume AND prepType = :prepType
    """)
    suspend fun getBestFreeHoldByTodLvPt(timeOfDay: String, lungVolume: String, prepType: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay AND lungVolume = :lungVolume AND posture = :posture
    """)
    suspend fun getBestFreeHoldByTodLvPos(timeOfDay: String, lungVolume: String, posture: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND timeOfDay = :timeOfDay AND prepType = :prepType AND posture = :posture
    """)
    suspend fun getBestFreeHoldByTodPtPos(timeOfDay: String, prepType: String, posture: String): Long?

    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE tableType IS NULL AND lungVolume = :lungVolume AND prepType = :prepType AND posture = :posture
    """)
    suspend fun getBestFreeHoldByLvPtPos(lungVolume: String, prepType: String, posture: String): Long?

    // ── Check if a record is currently the best for a category ────────────────

    /** Is this record's duration the best across ALL free holds? */
    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isGlobalBest(recordId: Long): Boolean

    // ── isBest single-setting ────────────────────────────────────────────────

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTimeOfDay(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.lungVolume = r.lungVolume), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForLungVolume(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.prepType = r.prepType), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForPrepType(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForPosture(recordId: Long): Boolean

    // ── isBest two-setting ───────────────────────────────────────────────────

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay AND o.lungVolume = r.lungVolume), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTimeOfDayAndLungVolume(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay AND o.prepType = r.prepType), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTimeOfDayAndPrepType(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTimeOfDayAndPosture(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForLungVolumeAndPrepType(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.lungVolume = r.lungVolume AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForLungVolumeAndPosture(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.prepType = r.prepType AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForPrepTypeAndPosture(recordId: Long): Boolean

    // ── isBest three-setting ─────────────────────────────────────────────────

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTodLvPt(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay AND o.lungVolume = r.lungVolume AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTodLvPos(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.timeOfDay = r.timeOfDay AND o.prepType = r.prepType AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForTodPtPos(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForLvPtPos(recordId: Long): Boolean

    /** Is this record's duration the best for its exact 4-setting combo? */
    @Query("""
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE o.tableType IS NULL AND o.recordId != :recordId
               AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType
               AND o.timeOfDay = r.timeOfDay AND o.posture = r.posture), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun isBestForExactSettings(recordId: Long): Boolean

    // ── Was a record EVER the best at the time it was recorded? ───────────────

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timestamp < r.timestamp
              AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasGlobalBestAtTime(recordId: Long): Boolean

    // ── wasBest single-setting ───────────────────────────────────────────────

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL AND o.timeOfDay = r.timeOfDay
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTimeOfDayAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL AND o.lungVolume = r.lungVolume
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForLungVolumeAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL AND o.prepType = r.prepType
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForPrepTypeAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForPostureAtTime(recordId: Long): Boolean

    // ── wasBest two-setting ──────────────────────────────────────────────────

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timeOfDay = r.timeOfDay AND o.lungVolume = r.lungVolume
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTimeOfDayAndLungVolumeAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timeOfDay = r.timeOfDay AND o.prepType = r.prepType
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTimeOfDayAndPrepTypeAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timeOfDay = r.timeOfDay AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTimeOfDayAndPostureAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForLungVolumeAndPrepTypeAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.lungVolume = r.lungVolume AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForLungVolumeAndPostureAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.prepType = r.prepType AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForPrepTypeAndPostureAtTime(recordId: Long): Boolean

    // ── wasBest three-setting ────────────────────────────────────────────────

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timeOfDay = r.timeOfDay AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTodLvPtAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timeOfDay = r.timeOfDay AND o.lungVolume = r.lungVolume AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTodLvPosAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.timeOfDay = r.timeOfDay AND o.prepType = r.prepType AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForTodPtPosAtTime(recordId: Long): Boolean

    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForLvPtPosAtTime(recordId: Long): Boolean

    /** Was this record the best for its exact 4-setting combo at the time it was recorded? */
    @Query("""
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE o.tableType IS NULL
              AND o.lungVolume = r.lungVolume AND o.prepType = r.prepType
              AND o.timeOfDay = r.timeOfDay AND o.posture = r.posture
              AND o.timestamp < r.timestamp AND o.durationMs >= r.durationMs
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = :recordId
    """)
    suspend fun wasBestForExactSettingsAtTime(recordId: Long): Boolean

    // ── Flexible best-record query for Personal Bests screen ─────────────────

    /**
     * Returns the best (longest) free-hold record matching optional filters.
     * Pass empty string "" for any filter to match all values for that dimension.
     * Returns recordId, durationMs, and timestamp of the best record, or null if none.
     */
    @Query("""
        SELECT recordId, durationMs, timestamp FROM apnea_records
        WHERE tableType IS NULL
          AND (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR posture    = :posture)
        ORDER BY durationMs DESC
        LIMIT 1
    """)
    suspend fun getBestFreeHoldRecord(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String
    ): BestRecordTuple?

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
