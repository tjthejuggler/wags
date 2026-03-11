package com.example.wags.data.repository

import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ApneaRepository @Inject constructor(
    private val dao: ApneaRecordDao
) {
    fun getLatestRecords(limit: Int = 20): Flow<List<ApneaRecordEntity>> =
        dao.getLatest(limit)

    fun getByType(type: String): Flow<List<ApneaRecordEntity>> =
        dao.getByType(type)

    /** All records matching the current settings combination (for history screen). */
    fun getBySettings(lungVolume: String, prepType: String): Flow<List<ApneaRecordEntity>> =
        dao.getBySettings(lungVolume, prepType)

    /** Best free-hold duration for the current settings combination. */
    fun getBestFreeHold(lungVolume: String, prepType: String): Flow<Long?> =
        dao.getBestFreeHold(lungVolume, prepType)

    suspend fun saveRecord(entity: ApneaRecordEntity): Long =
        dao.insert(entity)
}
