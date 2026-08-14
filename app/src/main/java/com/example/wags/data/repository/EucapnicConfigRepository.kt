package com.example.wags.data.repository

import android.content.SharedPreferences
import com.example.wags.data.db.dao.EucapnicPastConfigurationDao
import com.example.wags.data.db.entity.EucapnicPastConfigurationEntity
import com.example.wags.domain.model.EucapnicConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Repository for managing Eucapnic Diaphragmatic breathing configurations.
 * Handles seeding of default configurations on first run.
 */
@Singleton
class EucapnicConfigRepository @Inject constructor(
    private val pastConfigDao: EucapnicPastConfigurationDao,
    @Named("apnea_prefs") private val prefs: SharedPreferences,
    private val scope: CoroutineScope
) {

    /**
     * Seed default configurations on first run.
     * This should be called from WagsApplication.onCreate().
     */
    fun seedDefaultConfigurationsIfNeeded() {
        // Check if we've already seeded
        if (prefs.getBoolean(KEY_SEEDED, false)) {
            return
        }

        scope.launch {
            // Check if database is empty
            val count = pastConfigDao.count()
            if (count == 0) {
                // Seed "Balanced Pace" configuration
                val balancedPace = EucapnicPastConfigurationEntity(
                    name = "Balanced Pace",
                    prepDurationSec = 300, // 5 minutes
                    breathsPerMin = 5.5f,
                    inhaleSec = 4.0f,
                    topPauseSec = 0.0f,
                    exhaleSec = 6.0f,
                    bottomPauseSec = 0.9f,
                    breathDepthPercent = 25,
                    createdAtMs = System.currentTimeMillis()
                )
                pastConfigDao.insert(balancedPace)

                // Seed "Deep Relaxation" configuration
                val deepRelaxation = EucapnicPastConfigurationEntity(
                    name = "Deep Relaxation",
                    prepDurationSec = 420, // 7 minutes
                    breathsPerMin = 4.5f,
                    inhaleSec = 4.0f,
                    topPauseSec = 1.0f,
                    exhaleSec = 7.0f,
                    bottomPauseSec = 1.3f,
                    breathDepthPercent = 30,
                    createdAtMs = System.currentTimeMillis()
                )
                pastConfigDao.insert(deepRelaxation)
            }

            // Mark as seeded
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    /**
     * Record that a session was run with the given [config].
     *
     * Called automatically every time a eucapnic prep session starts, so any
     * configuration actually used ends up in the Past Configurations list
     * without requiring an explicit "Save Current" tap.
     *
     * - If an existing saved configuration matches all parameters, its
     *   [EucapnicPastConfigurationEntity.useCount] and last-used timestamp are
     *   bumped instead of creating a duplicate.
     * - Otherwise a new entry is inserted with an auto-generated name.
     */
    suspend fun recordSessionUse(config: EucapnicConfig) {
        val now = System.currentTimeMillis()

        val existing = pastConfigDao.getAll().firstOrNull { it.matches(config) }
        if (existing != null) {
            pastConfigDao.incrementUseCount(existing.configId, now)
            return
        }

        pastConfigDao.insert(
            EucapnicPastConfigurationEntity(
                name = autoName(config),
                prepDurationSec = config.prepDurationSec,
                breathsPerMin = config.breathsPerMin,
                inhaleSec = config.inhaleSec,
                topPauseSec = config.topPauseSec,
                exhaleSec = config.exhaleSec,
                bottomPauseSec = config.bottomPauseSec,
                breathDepthPercent = config.breathDepthPercent,
                createdAtMs = now,
                lastUsedAtMs = now,
                useCount = 1
            )
        )
    }

    /** Human-readable auto-generated label for a session-used configuration. */
    private fun autoName(config: EucapnicConfig): String =
        "Auto · ${config.breathsPerMin} BPM · ${config.prepDurationSec / 60}m${config.prepDurationSec % 60}s"

    private fun EucapnicPastConfigurationEntity.matches(config: EucapnicConfig): Boolean =
        prepDurationSec == config.prepDurationSec &&
            breathDepthPercent == config.breathDepthPercent &&
            floatsEqual(breathsPerMin, config.breathsPerMin) &&
            floatsEqual(inhaleSec, config.inhaleSec) &&
            floatsEqual(topPauseSec, config.topPauseSec) &&
            floatsEqual(exhaleSec, config.exhaleSec) &&
            floatsEqual(bottomPauseSec, config.bottomPauseSec)

    /**
     * Tolerant float comparison: config values round-trip through nav-route
     * string interpolation, so tiny decimal drift must not defeat matching.
     */
    private fun floatsEqual(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < FLOAT_EPSILON

    companion object {
        private const val KEY_SEEDED = "eucapnic_configs_seeded"
        private const val FLOAT_EPSILON = 0.01f
    }
}
