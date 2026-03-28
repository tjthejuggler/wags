package com.example.wags.data.repository

import com.example.wags.data.db.dao.RfAssessmentDao
import com.example.wags.data.db.entity.RfAssessmentEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RfAssessmentRepository @Inject constructor(
    private val dao: RfAssessmentDao
) {
    /** Insert a completed session. */
    suspend fun saveSession(entity: RfAssessmentEntity) {
        dao.insert(entity)
    }

    /** Get all sessions, newest first. */
    fun getAllSessions(): Flow<List<RfAssessmentEntity>> =
        dao.getLatest(Int.MAX_VALUE)

    /** Get the most recent session for a given protocol. */
    suspend fun getLatestForProtocol(protocol: String): RfAssessmentEntity? =
        dao.getLatestForProtocol(protocol)

    /** Get the best (highest score) session overall. */
    suspend fun getBestSession(): RfAssessmentEntity? =
        dao.getBestValid()

    /** Check if any sessions exist (used to enable/disable TARGETED protocol). */
    suspend fun hasAnySession(): Boolean =
        dao.hasAnySession()

    /** Get a session by its timestamp. */
    suspend fun getByTimestamp(timestamp: Long): RfAssessmentEntity? =
        dao.getByTimestamp(timestamp)

    /** Observe all sessions (newest first) as a Flow. */
    fun observeAll(): Flow<List<RfAssessmentEntity>> =
        dao.observeAll()

    /** Delete a session by its timestamp. */
    suspend fun deleteByTimestamp(timestamp: Long) =
        dao.deleteByTimestamp(timestamp)

    /** Get all assessments since a given timestamp. */
    suspend fun getSince(sinceMs: Long): List<RfAssessmentEntity> = dao.getSince(sinceMs)
}
