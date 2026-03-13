package com.example.wags.data.repository

import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.entity.DailyReadingEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ReadinessRepository @Inject constructor(
    private val dao: DailyReadingDao
) {
    fun getLatestReadings(limit: Int = 14): Flow<List<DailyReadingEntity>> =
        dao.getLatest(limit)

    fun observeAll(): Flow<List<DailyReadingEntity>> =
        dao.observeAll()

    suspend fun getLast14ForBaseline(): List<DailyReadingEntity> =
        dao.getLast14()

    suspend fun getById(id: Long): DailyReadingEntity? =
        dao.getById(id)

    suspend fun saveReading(entity: DailyReadingEntity): Long =
        dao.insert(entity)
}
