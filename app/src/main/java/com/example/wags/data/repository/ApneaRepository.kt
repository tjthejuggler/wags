package com.example.wags.data.repository

import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.FreeHoldTelemetryDao
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ApneaRepository @Inject constructor(
    private val dao: ApneaRecordDao,
    private val telemetryDao: FreeHoldTelemetryDao
) {
    fun getLatestRecords(limit: Int = 20): Flow<List<ApneaRecordEntity>> =
        dao.getLatest(limit)

    fun getByType(type: String): Flow<List<ApneaRecordEntity>> =
        dao.getByType(type)

    /** All records matching the current settings combination (for history / recent records). */
    fun getBySettings(lungVolume: String, prepType: String, timeOfDay: String): Flow<List<ApneaRecordEntity>> =
        dao.getBySettings(lungVolume, prepType, timeOfDay)

    /** Best free-hold duration for the current settings combination. */
    fun getBestFreeHold(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?> =
        dao.getBestFreeHold(lungVolume, prepType, timeOfDay)

    suspend fun getById(recordId: Long): ApneaRecordEntity? =
        dao.getById(recordId)

    suspend fun saveRecord(entity: ApneaRecordEntity): Long =
        dao.insert(entity)

    /** Permanently delete a record; CASCADE removes its telemetry automatically. */
    suspend fun deleteRecord(recordId: Long) =
        dao.deleteById(recordId)

    // ── Free-hold telemetry ───────────────────────────────────────────────

    suspend fun saveTelemetry(samples: List<FreeHoldTelemetryEntity>) =
        telemetryDao.insertAll(samples)

    suspend fun getTelemetryForRecord(recordId: Long): List<FreeHoldTelemetryEntity> =
        telemetryDao.getForRecord(recordId)
}
