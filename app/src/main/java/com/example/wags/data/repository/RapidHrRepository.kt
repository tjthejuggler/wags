package com.example.wags.data.repository

import com.example.wags.data.db.dao.RapidHrPreset
import com.example.wags.data.db.dao.RapidHrSessionDao
import com.example.wags.data.db.dao.RapidHrTelemetryDao
import com.example.wags.data.db.entity.RapidHrSessionEntity
import com.example.wags.data.db.entity.RapidHrTelemetryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RapidHrRepository @Inject constructor(
    private val sessionDao: RapidHrSessionDao,
    private val telemetryDao: RapidHrTelemetryDao
) {

    // ── Sessions ───────────────────────────────────────────────────────────────

    suspend fun saveSession(session: RapidHrSessionEntity): Long =
        sessionDao.insert(session)

    fun observeAll(): Flow<List<RapidHrSessionEntity>> =
        sessionDao.observeAll()

    fun observeByDirection(direction: String): Flow<List<RapidHrSessionEntity>> =
        sessionDao.observeByDirection(direction)

    suspend fun getAllSessions(): List<RapidHrSessionEntity> =
        sessionDao.getAll()

    suspend fun getSessionById(id: Long): RapidHrSessionEntity? =
        sessionDao.getById(id)

    // ── Presets ────────────────────────────────────────────────────────────────

    fun getPresetsByDirection(direction: String): Flow<List<RapidHrPreset>> =
        sessionDao.getPresetsByDirection(direction)

    suspend fun getBestTransitionTime(direction: String, high: Int, low: Int): Long? =
        sessionDao.getBestTransitionTime(direction, high, low)

    // ── Telemetry ──────────────────────────────────────────────────────────────

    suspend fun saveTelemetry(samples: List<RapidHrTelemetryEntity>) =
        telemetryDao.insertAll(samples)

    suspend fun getTelemetryForSession(sessionId: Long): List<RapidHrTelemetryEntity> =
        telemetryDao.getBySessionId(sessionId)
}
