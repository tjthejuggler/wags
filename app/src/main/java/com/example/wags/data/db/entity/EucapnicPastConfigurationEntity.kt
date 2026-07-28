package com.example.wags.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved eucapnic diaphragmatic breathing preparation configuration.
 *
 * Stores the full parameter set of a past eucapnic prep so the user can
 * quickly re-apply a previously used configuration without re-entering
 * every value.
 */
@Entity(tableName = "eucapnic_past_configurations")
data class EucapnicPastConfigurationEntity(
    @PrimaryKey(autoGenerate = true) val configId: Long = 0,
    /** Human-readable label for this configuration (e.g. "Morning 5.5 bpm"). */
    val name: String,
    /** Total prep duration in seconds. */
    val prepDurationSec: Int,
    /** Target breathing rate in breaths per minute. */
    val breathsPerMin: Float,
    /** Inhale phase duration in seconds. */
    val inhaleSec: Float,
    /** Breath-hold pause at the top of the inhale, in seconds. */
    val topPauseSec: Float,
    /** Exhale phase duration in seconds. */
    val exhaleSec: Float,
    /** Breath-hold pause at the bottom of the exhale, in seconds. */
    val bottomPauseSec: Float,
    /** Breath depth as a percentage of vital capacity (0–100). */
    val breathDepthPercent: Int,
    /** Unix epoch ms when this configuration was first saved. */
    val createdAtMs: Long,
    /** Unix epoch ms when this configuration was last applied. Null if never used. */
    val lastUsedAtMs: Long? = null,
    /** Number of times this configuration has been applied. */
    val useCount: Int = 0
)
