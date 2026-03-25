package com.example.wags.data.db.dao

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlinx.coroutines.flow.Flow

/** Lightweight projection returned by [ApneaRecordDao.getBestFreeHoldRecord]. */
data class BestRecordTuple(
    val recordId: Long,
    val durationMs: Long,
    val timestamp: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Dynamic PB query builder
//
// With 5 settings (timeOfDay, lungVolume, prepType, posture, audio) the number
// of combinatorial PB queries would be 2^5 - 1 = 31 combinations × 3 variants
// (getBest, isBest, wasBest) = 93 hand-coded methods. Instead we use a single
// @RawQuery approach with a Kotlin builder that constructs the SQL at runtime.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds a parameterised query to find the best free-hold duration matching
 * a subset of the 5 settings. Pass null for any setting to relax that constraint.
 *
 * Returns: MAX(durationMs) or null.
 */
internal fun buildBestFreeHoldQuery(
    timeOfDay: String?,
    lungVolume: String?,
    prepType: String?,
    posture: String?,
    audio: String?
): SupportSQLiteQuery {
    val args = mutableListOf<Any>()
    val conditions = mutableListOf("tableType IS NULL")
    if (timeOfDay  != null) { conditions += "timeOfDay = ?";  args += timeOfDay  }
    if (lungVolume != null) { conditions += "lungVolume = ?"; args += lungVolume }
    if (prepType   != null) { conditions += "prepType = ?";   args += prepType   }
    if (posture    != null) { conditions += "posture = ?";    args += posture    }
    if (audio      != null) { conditions += "audio = ?";      args += audio      }
    val sql = "SELECT MAX(durationMs) FROM apnea_records WHERE ${conditions.joinToString(" AND ")}"
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}

/**
 * Builds a query to check if a specific record is currently the best for a
 * given subset of settings.
 *
 * Returns: 1 if best, 0 otherwise.
 */
internal fun buildIsBestQuery(
    recordId: Long,
    timeOfDay: String?,
    lungVolume: String?,
    prepType: String?,
    posture: String?,
    audio: String?
): SupportSQLiteQuery {
    val args = mutableListOf<Any>()
    val innerConditions = mutableListOf("o.tableType IS NULL", "o.recordId != ?")
    args += recordId
    if (timeOfDay  != null) { innerConditions += "o.timeOfDay = r.timeOfDay";   }
    if (lungVolume != null) { innerConditions += "o.lungVolume = r.lungVolume"; }
    if (prepType   != null) { innerConditions += "o.prepType = r.prepType";     }
    if (posture    != null) { innerConditions += "o.posture = r.posture";       }
    if (audio      != null) { innerConditions += "o.audio = r.audio";           }
    val sql = """
        SELECT CASE WHEN r.durationMs >= COALESCE(
            (SELECT MAX(o.durationMs) FROM apnea_records o
             WHERE ${innerConditions.joinToString(" AND ")}), 0)
        THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = ?
    """.trimIndent()
    args += recordId
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}

/**
 * Builds a query to check if a specific record WAS the best at the time it
 * was recorded, for a given subset of settings.
 *
 * Returns: 1 if it was the best at the time, 0 otherwise.
 */
internal fun buildWasBestAtTimeQuery(
    recordId: Long,
    timeOfDay: String?,
    lungVolume: String?,
    prepType: String?,
    posture: String?,
    audio: String?
): SupportSQLiteQuery {
    val args = mutableListOf<Any>()
    val innerConditions = mutableListOf("o.tableType IS NULL", "o.timestamp < r.timestamp", "o.durationMs >= r.durationMs")
    if (timeOfDay  != null) { innerConditions += "o.timeOfDay = r.timeOfDay";   }
    if (lungVolume != null) { innerConditions += "o.lungVolume = r.lungVolume"; }
    if (prepType   != null) { innerConditions += "o.prepType = r.prepType";     }
    if (posture    != null) { innerConditions += "o.posture = r.posture";       }
    if (audio      != null) { innerConditions += "o.audio = r.audio";           }
    val sql = """
        SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM apnea_records o
            WHERE ${innerConditions.joinToString(" AND ")}
        ) THEN 1 ELSE 0 END
        FROM apnea_records r WHERE r.recordId = ?
    """.trimIndent()
    args += recordId
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}

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

    /** Returns all records matching the given 5-setting combination. */
    @Query("""
        SELECT * FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND audio      = :audio
        ORDER BY timestamp DESC
    """)
    fun getBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<List<ApneaRecordEntity>>

    /** Best (longest) free-hold for a given 5-setting combination. */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND audio      = :audio
          AND tableType IS NULL
    """)
    fun getBestFreeHold(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?>

    /** recordId of the best (longest) free-hold for a given 5-setting combination. */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND audio      = :audio
          AND tableType IS NULL
        ORDER BY durationMs DESC LIMIT 1
    """)
    fun getBestFreeHoldRecordId(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?>

    /** One-shot (suspend) best free-hold duration for a given 5-setting combination. */
    @Query("""
        SELECT MAX(durationMs) FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND audio      = :audio
          AND tableType IS NULL
    """)
    suspend fun getBestFreeHoldOnce(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Long?

    /** One-shot recordId of the best free-hold for a given 5-setting combination. */
    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND audio      = :audio
          AND tableType IS NULL
        ORDER BY durationMs DESC LIMIT 1
    """)
    suspend fun getBestFreeHoldRecordIdOnce(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Long?

    /** Single record by primary key. */
    @Query("SELECT * FROM apnea_records WHERE recordId = :recordId LIMIT 1")
    suspend fun getById(recordId: Long): ApneaRecordEntity?

    /** Permanently delete a record (CASCADE will remove its free_hold_telemetry and song_log rows). */
    @Query("DELETE FROM apnea_records WHERE recordId = :recordId")
    suspend fun deleteById(recordId: Long)

    // ── Stats queries (filtered by 5 settings) ────────────────────────────────

    @Query("""
        SELECT COUNT(*) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio AND tableType IS NULL
    """)
    fun countFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio AND tableType = :tableType
    """)
    fun countByTableType(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        tableType: String
    ): Flow<Int>

    @Query("""
        SELECT MAX(maxHrBpm) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio AND maxHrBpm BETWEEN 20 AND 250
    """)
    fun getMaxHrEver(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Float?>

    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio AND maxHrBpm BETWEEN 20 AND 250
        ORDER BY maxHrBpm DESC LIMIT 1
    """)
    fun getMaxHrRecordId(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?>

    @Query("""
        SELECT MIN(minHrBpm) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio AND minHrBpm BETWEEN 20 AND 250
    """)
    fun getMinHrEver(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Float?>

    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio AND minHrBpm BETWEEN 20 AND 250
        ORDER BY minHrBpm ASC LIMIT 1
    """)
    fun getMinHrRecordId(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?>

    @Query("""
        SELECT MIN(lowestSpO2) FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio
          AND lowestSpO2 IS NOT NULL AND lowestSpO2 BETWEEN 1 AND 100
    """)
    fun getLowestSpO2Ever(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Int?>

    @Query("""
        SELECT recordId FROM apnea_records
        WHERE lungVolume = :lungVolume AND prepType = :prepType AND timeOfDay = :timeOfDay
          AND posture = :posture AND audio = :audio
          AND lowestSpO2 IS NOT NULL AND lowestSpO2 BETWEEN 1 AND 100
        ORDER BY lowestSpO2 ASC LIMIT 1
    """)
    fun getLowestSpO2RecordId(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?>

    // ── Recent records (all types, filtered by 5 settings) ───────────────────

    @Query("""
        SELECT * FROM apnea_records
        WHERE lungVolume = :lungVolume
          AND prepType   = :prepType
          AND timeOfDay  = :timeOfDay
          AND posture    = :posture
          AND audio      = :audio
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getRecentBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        limit: Int
    ): Flow<List<ApneaRecordEntity>>

    // ── Paginated all-records (for the All Records screen) ───────────────────

    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR posture    = :posture)
          AND (:audio      = '' OR audio      = :audio)
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedAll(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR posture    = :posture)
          AND (:audio      = '' OR audio      = :audio)
          AND tableType = :tableType
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedByTableType(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        tableType: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    @Query("""
        SELECT * FROM apnea_records
        WHERE (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR posture    = :posture)
          AND (:audio      = '' OR audio      = :audio)
          AND tableType IS NULL
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPagedFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    @Query("""
        SELECT r.* FROM apnea_records r
        WHERE r.tableType IS NULL
          AND (:lungVolume = '' OR r.lungVolume = :lungVolume)
          AND (:prepType   = '' OR r.prepType   = :prepType)
          AND (:timeOfDay  = '' OR r.timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR r.posture    = :posture)
          AND (:audio      = '' OR r.audio      = :audio)
          AND NOT EXISTS (
              SELECT 1 FROM apnea_records older
              WHERE older.tableType IS NULL
                AND (:lungVolume = '' OR older.lungVolume = :lungVolume)
                AND (:prepType   = '' OR older.prepType   = :prepType)
                AND (:timeOfDay  = '' OR older.timeOfDay  = :timeOfDay)
                AND (:posture    = '' OR older.posture    = :posture)
                AND (:audio      = '' OR older.audio      = :audio)
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
        audio: String,
        limit: Int,
        offset: Int
    ): List<ApneaRecordEntity>

    // ── Global best (no setting constraints) ─────────────────────────────────

    @Query("SELECT MAX(durationMs) FROM apnea_records WHERE tableType IS NULL")
    suspend fun getBestFreeHoldGlobal(): Long?

    // ── Dynamic PB queries via @RawQuery ──────────────────────────────────────
    // These replace the 48+ hand-coded combinatorial methods from the 4-setting
    // era. The query builders above construct the SQL at runtime.

    /** Best free-hold for any subset of settings (pass null to relax a constraint). */
    @RawQuery
    suspend fun getBestFreeHoldDynamic(query: SupportSQLiteQuery): Long?

    /** Is this record currently the best for a given subset of settings? Returns 1/0. */
    @RawQuery
    suspend fun isBestDynamic(query: SupportSQLiteQuery): Int

    /** Was this record the best at the time it was recorded? Returns 1/0. */
    @RawQuery
    suspend fun wasBestAtTimeDynamic(query: SupportSQLiteQuery): Int

    // ── Flexible best-record query for Personal Bests screen ─────────────────

    @Query("""
        SELECT recordId, durationMs, timestamp FROM apnea_records
        WHERE tableType IS NULL
          AND (:lungVolume = '' OR lungVolume = :lungVolume)
          AND (:prepType   = '' OR prepType   = :prepType)
          AND (:timeOfDay  = '' OR timeOfDay  = :timeOfDay)
          AND (:posture    = '' OR posture    = :posture)
          AND (:audio      = '' OR audio      = :audio)
        ORDER BY durationMs DESC
        LIMIT 1
    """)
    suspend fun getBestFreeHoldRecord(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
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
