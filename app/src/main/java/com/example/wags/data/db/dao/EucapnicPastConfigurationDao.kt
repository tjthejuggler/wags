package com.example.wags.data.db.dao

import androidx.room.*
import com.example.wags.data.db.entity.EucapnicPastConfigurationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EucapnicPastConfigurationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EucapnicPastConfigurationEntity): Long

    @Update
    suspend fun update(entity: EucapnicPastConfigurationEntity)

    @Delete
    suspend fun delete(entity: EucapnicPastConfigurationEntity)

    @Query("DELETE FROM eucapnic_past_configurations WHERE configId = :configId")
    suspend fun deleteById(configId: Long)

    @Query("SELECT * FROM eucapnic_past_configurations WHERE configId = :configId LIMIT 1")
    suspend fun getById(configId: Long): EucapnicPastConfigurationEntity?

    /**
     * Observe all saved configurations, most recently used first.
     * Configurations that have never been used fall back to creation order.
     */
    @Query("SELECT * FROM eucapnic_past_configurations ORDER BY lastUsedAtMs DESC, createdAtMs DESC")
    fun observeAll(): Flow<List<EucapnicPastConfigurationEntity>>

    /** Return the [limit] most frequently used configurations. */
    @Query("SELECT * FROM eucapnic_past_configurations ORDER BY useCount DESC LIMIT :limit")
    suspend fun getMostUsed(limit: Int): List<EucapnicPastConfigurationEntity>

    /** Return the [limit] most recently created configurations. */
    @Query("SELECT * FROM eucapnic_past_configurations ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun getRecentlyCreated(limit: Int): List<EucapnicPastConfigurationEntity>

    /** Return every saved configuration (table is small; used for in-memory matching). */
    @Query("SELECT * FROM eucapnic_past_configurations")
    suspend fun getAll(): List<EucapnicPastConfigurationEntity>

    @Query("SELECT COUNT(*) FROM eucapnic_past_configurations")
    suspend fun count(): Int

    /**
     * Atomically increment [useCount] and stamp [lastUsedAtMs] for the given
     * configuration. Called every time the user applies a saved configuration.
     */
    @Query("UPDATE eucapnic_past_configurations SET useCount = useCount + 1, lastUsedAtMs = :usedAtMs WHERE configId = :configId")
    suspend fun incrementUseCount(configId: Long, usedAtMs: Long)
}
