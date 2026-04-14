package com.example.wags.data.repository

import com.example.wags.data.db.dao.MorningReadinessDao
import com.example.wags.data.db.dao.MorningReadinessTelemetryDao
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.db.entity.MorningReadinessTelemetryEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MorningReadinessRepository @Inject constructor(
    private val dao: MorningReadinessDao,
    private val telemetryDao: MorningReadinessTelemetryDao
) {
    /** Inserts the entity and returns the auto-generated row id. */
    suspend fun save(entity: MorningReadinessEntity): Long = dao.insert(entity)

    /**
     * Save per-beat telemetry rows for a reading.
     * The readingId must already exist in morning_readiness (insert the entity first).
     */
    suspend fun saveTelemetry(rows: List<MorningReadinessTelemetryEntity>) {
        if (rows.isNotEmpty()) telemetryDao.insertAll(rows)
    }

    /** Load all telemetry rows for a given reading, ordered by timestamp ascending. */
    suspend fun getTelemetry(readingId: Long): List<MorningReadinessTelemetryEntity> =
        telemetryDao.getByReadingId(readingId)

    /** Deletes the reading with the given id (telemetry rows cascade-deleted by FK). */
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun getLatest(): MorningReadinessEntity? = dao.getLatest()

    suspend fun getById(id: Long): MorningReadinessEntity? = dao.getById(id)

    fun observeAll(): Flow<List<MorningReadinessEntity>> = dao.observeAll()

    suspend fun getAll(): List<MorningReadinessEntity> = dao.getAll()

    /** Emits the most recent morning readiness reading taken today, or null if none. */
    fun observeTodayReading(): Flow<MorningReadinessEntity?> {
        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return dao.observeTodayLatest(startOfDay)
    }

    // 7-day acute baseline: ln(RMSSD) values
    suspend fun getAcuteBaselineLnRmssd(): List<Double> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        return dao.getSupineLnRmssdSince(since)
    }

    // 30-day chronic baseline: ln(RMSSD) values
    suspend fun getChronicBaselineLnRmssd(): List<Double> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        return dao.getSupineLnRmssdSince(since)
    }

    // 90-day supine RMSSD history
    suspend fun getSupineRmssd90Days(): List<Double> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        return dao.getSupineRmssdSince(since)
    }

    // 90-day standing RMSSD history
    suspend fun getStandingRmssd90Days(): List<Double> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        return dao.getStandingRmssdSince(since)
    }

    // 90-day supine RHR history
    suspend fun getSupineRhr90Days(): List<Int> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        return dao.getSupineRhrSince(since)
    }

    // 30-day 30:15 ratio history
    suspend fun getThirtyFifteenRatio30Days(): List<Float> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        return dao.getThirtyFifteenRatioSince(since)
    }

    // 30-day OHRR 60s history
    suspend fun getOhrr60s30Days(): List<Float> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        return dao.getOhrrAt60sSince(since)
    }

    // 30-day Hooper Index history
    suspend fun getHooperIndex30Days(): List<Float> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        return dao.getHooperTotalSince(since)
    }

    // Cleanup old data (call periodically)
    suspend fun pruneOldData() {
        val cutoff90Days = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        dao.deleteOlderThan(cutoff90Days)
    }
}
