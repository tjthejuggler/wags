package com.example.wags.data.repository

import com.example.wags.data.db.dao.SessionLogDao
import com.example.wags.data.db.entity.SessionLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SessionRepository @Inject constructor(
    private val dao: SessionLogDao
) {
    fun getLatestSessions(limit: Int = 20): Flow<List<SessionLogEntity>> =
        dao.getLatest(limit)

    fun getByType(type: String): Flow<List<SessionLogEntity>> =
        dao.getByType(type)

    suspend fun saveSession(entity: SessionLogEntity): Long =
        dao.insert(entity)
}
