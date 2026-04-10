package com.example.wags.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FreeHoldTelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<FreeHoldTelemetryEntity>)

    @Query("SELECT * FROM free_hold_telemetry WHERE recordId = :recordId ORDER BY timestampMs ASC")
    suspend fun getForRecord(recordId: Long): List<FreeHoldTelemetryEntity>

    /** Explicit delete — also triggered automatically via CASCADE when the parent record is deleted. */
    @Query("DELETE FROM free_hold_telemetry WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)

    // ── Session-start / session-end aggregate stats ───────────────────────────
    // "Start" = the first telemetry sample for each record; "End" = the last.
    // We join to apnea_records to apply settings filters.
    //
    // Physiological bounds applied to every HR/SpO2 predicate:
    //   HR   : 20–250 bpm
    //   SpO2 : 1–100 %  (0 = no-signal artefact; real extreme dives can go very low)

    // ── Start HR ─────────────────────────────────────────────────────────────

    @Query("""
        SELECT MAX(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMaxStartHr(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm DESC LIMIT 1
    """)
    fun getMaxStartHrRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    @Query("""
        SELECT MIN(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMinStartHr(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm ASC LIMIT 1
    """)
    fun getMinStartHrRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    // ── Start SpO2 ───────────────────────────────────────────────────────────

    @Query("""
        SELECT MAX(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMaxStartSpO2(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 DESC LIMIT 1
    """)
    fun getMaxStartSpO2RecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    @Query("""
        SELECT MIN(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMinStartSpO2(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs
            FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 ASC LIMIT 1
    """)
    fun getMinStartSpO2RecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    // ── End HR ───────────────────────────────────────────────────────────────

    @Query("""
        SELECT MAX(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMaxEndHr(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm DESC LIMIT 1
    """)
    fun getMaxEndHrRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    @Query("""
        SELECT MIN(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMinEndHr(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm ASC LIMIT 1
    """)
    fun getMinEndHrRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    // ── End SpO2 ─────────────────────────────────────────────────────────────

    @Query("""
        SELECT MAX(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMaxEndSpO2(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 DESC LIMIT 1
    """)
    fun getMaxEndSpO2RecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    @Query("""
        SELECT MIN(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMinEndSpO2(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN apnea_records r ON r.recordId = t.recordId
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs
            FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE (:lungVolume = 'ALL' OR r.lungVolume = :lungVolume) AND (:prepType = 'ALL' OR r.prepType = :prepType) AND (:timeOfDay = 'ALL' OR r.timeOfDay = :timeOfDay) AND (:posture = 'ALL' OR r.posture = :posture)
          AND t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 ASC LIMIT 1
    """)
    fun getMinEndSpO2RecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?>

    // ── All-settings variants ─────────────────────────────────────────────────

    @Query("""
        SELECT MAX(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMaxStartHrAll(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm DESC LIMIT 1
    """)
    fun getMaxStartHrRecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MIN(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMinStartHrAll(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm ASC LIMIT 1
    """)
    fun getMinStartHrRecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MAX(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMaxStartSpO2All(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 DESC LIMIT 1
    """)
    fun getMaxStartSpO2RecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MIN(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMinStartSpO2All(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MIN(timestampMs) AS firstTs FROM free_hold_telemetry GROUP BY recordId
        ) first ON first.recordId = t.recordId AND t.timestampMs = first.firstTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 ASC LIMIT 1
    """)
    fun getMinStartSpO2RecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MAX(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMaxEndHrAll(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm DESC LIMIT 1
    """)
    fun getMaxEndHrRecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MIN(t.heartRateBpm)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
    """)
    fun getMinEndHrAll(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.heartRateBpm IS NOT NULL AND t.heartRateBpm BETWEEN 20 AND 250
        ORDER BY t.heartRateBpm ASC LIMIT 1
    """)
    fun getMinEndHrRecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MAX(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMaxEndSpO2All(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 DESC LIMIT 1
    """)
    fun getMaxEndSpO2RecordIdAll(): Flow<Long?>

    @Query("""
        SELECT MIN(t.spO2)
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
    """)
    fun getMinEndSpO2All(): Flow<Int?>

    @Query("""
        SELECT t.recordId
        FROM free_hold_telemetry t
        INNER JOIN (
            SELECT recordId, MAX(timestampMs) AS lastTs FROM free_hold_telemetry GROUP BY recordId
        ) last ON last.recordId = t.recordId AND t.timestampMs = last.lastTs
        WHERE t.spO2 IS NOT NULL AND t.spO2 BETWEEN 1 AND 100
        ORDER BY t.spO2 ASC LIMIT 1
    """)
    fun getMinEndSpO2RecordIdAll(): Flow<Long?>
}
