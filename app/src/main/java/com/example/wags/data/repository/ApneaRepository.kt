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

    suspend fun saveRecord(entity: ApneaRecordEntity): Long =
        dao.insert(entity)
}
