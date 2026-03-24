package com.example.wags.data.repository

import com.example.wags.data.db.dao.AdviceDao
import com.example.wags.data.db.entity.AdviceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple repository wrapping [AdviceDao] for the per-section advice feature.
 */
@Singleton
class AdviceRepository @Inject constructor(
    private val dao: AdviceDao
) {
    /** Observe all advice for a section (reactive). */
    fun observeBySection(section: String): Flow<List<AdviceEntity>> =
        dao.observeBySection(section)

    /** One-shot fetch of all advice for a section. */
    suspend fun getBySection(section: String): List<AdviceEntity> =
        dao.getBySection(section)

    suspend fun add(section: String, text: String): Long =
        dao.insert(AdviceEntity(section = section, text = text))

    suspend fun update(entity: AdviceEntity) =
        dao.update(entity)

    suspend fun delete(id: Long) =
        dao.deleteById(id)
}
