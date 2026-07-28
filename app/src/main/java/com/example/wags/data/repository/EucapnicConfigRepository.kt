package com.example.wags.data.repository

import android.content.SharedPreferences
import com.example.wags.data.db.dao.EucapnicPastConfigurationDao
import com.example.wags.data.db.entity.EucapnicPastConfigurationEntity
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

    companion object {
        private const val KEY_SEEDED = "eucapnic_configs_seeded"
    }
}
