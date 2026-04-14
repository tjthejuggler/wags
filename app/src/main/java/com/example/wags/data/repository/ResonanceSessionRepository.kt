package com.example.wags.data.repository

import com.example.wags.data.db.dao.ResonanceSessionDao
import com.example.wags.data.db.entity.ResonanceSessionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResonanceSessionRepository @Inject constructor(
    private val dao: ResonanceSessionDao
) {
    suspend fun save(entity: ResonanceSessionEntity): Long = dao.insert(entity)

    fun observeAll(): Flow<List<ResonanceSessionEntity>> = dao.observeAll()

    suspend fun getAll(): List<ResonanceSessionEntity> = dao.getAll()

    suspend fun getSince(sinceMs: Long): List<ResonanceSessionEntity> = dao.getSince(sinceMs)

    suspend fun getByTimestamp(timestamp: Long): ResonanceSessionEntity? = dao.getByTimestamp(timestamp)

    suspend fun getById(sessionId: Long): ResonanceSessionEntity? = dao.getById(sessionId)

    suspend fun deleteByTimestamp(timestamp: Long) = dao.deleteByTimestamp(timestamp)

    suspend fun deleteById(sessionId: Long) = dao.deleteById(sessionId)
}
