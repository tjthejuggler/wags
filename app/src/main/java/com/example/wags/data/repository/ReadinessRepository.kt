package com.example.wags.data.repository

import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.entity.DailyReadingEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class ReadinessRepository @Inject constructor(
    private val dao: DailyReadingDao
) {
    fun getLatestReadings(limit: Int = 14): Flow<List<DailyReadingEntity>> =
        dao.getLatest(limit)

    fun observeAll(): Flow<List<DailyReadingEntity>> =
        dao.observeAll()

    /** Emits the most recent HRV readiness reading taken today, or null if none. */
    fun observeTodayReading(): Flow<DailyReadingEntity?> {
        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return dao.observeTodayLatest(startOfDay)
    }

    suspend fun getAll(): List<DailyReadingEntity> =
        dao.getAll()

    suspend fun getLast14ForBaseline(): List<DailyReadingEntity> =
        dao.getLast14()

    suspend fun getById(id: Long): DailyReadingEntity? =
        dao.getById(id)

    suspend fun saveReading(entity: DailyReadingEntity): Long =
        dao.insert(entity)

    suspend fun deleteReading(id: Long) =
        dao.deleteById(id)
}
