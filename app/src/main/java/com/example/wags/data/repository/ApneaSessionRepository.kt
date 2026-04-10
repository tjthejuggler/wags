package com.example.wags.data.repository

import com.example.wags.data.db.dao.ApneaSessionDao
import com.example.wags.data.db.dao.ContractionDao
import com.example.wags.data.db.dao.TelemetryDao
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.ContractionEntity
import com.example.wags.data.db.entity.TelemetryEntity
import com.example.wags.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApneaSessionRepository @Inject constructor(
    private val apneaSessionDao: ApneaSessionDao,
    private val contractionDao: ContractionDao,
    private val telemetryDao: TelemetryDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun saveSession(session: ApneaSessionEntity): Long = withContext(ioDispatcher) {
        apneaSessionDao.insert(session)
    }

    suspend fun saveContractions(contractions: List<ContractionEntity>) = withContext(ioDispatcher) {
        contractionDao.insertAll(contractions)
    }

    suspend fun saveTelemetry(telemetry: List<TelemetryEntity>) = withContext(ioDispatcher) {
        telemetryDao.insertAll(telemetry)
    }

    suspend fun getRecentSessions(limit: Int = 50): List<ApneaSessionEntity> = withContext(ioDispatcher) {
        apneaSessionDao.getLatest(limit)
    }

    suspend fun getSessionsByType(type: String): List<ApneaSessionEntity> = withContext(ioDispatcher) {
        apneaSessionDao.getByType(type)
    }

    suspend fun getSessionById(sessionId: Long): ApneaSessionEntity? = withContext(ioDispatcher) {
        apneaSessionDao.getById(sessionId)
    }

    /** Find a session entity matching a record's timestamp and table type. */
    suspend fun getSessionByTimestampAndType(timestamp: Long, tableType: String): ApneaSessionEntity? =
        withContext(ioDispatcher) {
            apneaSessionDao.getByTimestampAndType(timestamp, tableType)
        }

    suspend fun getContractionsForSession(sessionId: Long): List<ContractionEntity> = withContext(ioDispatcher) {
        contractionDao.getForSession(sessionId)
    }

    suspend fun getTelemetryForSession(sessionId: Long): List<TelemetryEntity> = withContext(ioDispatcher) {
        telemetryDao.getForSession(sessionId)
    }

    /** One-shot: all sessions ever, oldest first — used for time charts. */
    suspend fun getAllSessionsOnce(): List<ApneaSessionEntity> = withContext(ioDispatcher) {
        apneaSessionDao.getAllOnce()
    }
}
