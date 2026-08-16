package com.example.wags.ui.apnea

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.dao.EucapnicPastConfigurationDao
import com.example.wags.data.db.entity.EucapnicPastConfigurationEntity
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.usecase.breathing.EucapnicScalingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * ViewModel for the Eucapnic Diaphragmatic breathing configuration UI.
 *
 * Responsibilities:
 * - Holds the current [EucapnicConfig] as a [StateFlow].
 * - Provides per-parameter update methods that delegate bi-directional
 *   scaling to [EucapnicScalingEngine]:
 *   - BPM change → all timers scale proportionally.
 *   - Individual timer change → BPM is recalculated, other timers unchanged.
 * - Persists / restores named configurations via [EucapnicPastConfigurationDao].
 */
@HiltViewModel
class EucapnicConfigViewModel @Inject constructor(
    private val scalingEngine: EucapnicScalingEngine,
    private val pastConfigDao: EucapnicPastConfigurationDao,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    // ── Current configuration ─────────────────────────────────────────────

    // The current config is the app-wide source of truth for eucapnic prep.
    // It is restored from SharedPreferences on creation so settings survive
    // navigating between screens (pacer round-trips) and app restarts.
    private val _config = MutableStateFlow(restoreSavedConfig())
    val config: StateFlow<EucapnicConfig> = _config.asStateFlow()

    /** Set the config and persist it. */
    private fun setConfig(config: EucapnicConfig) {
        _config.value = config
        persist(config)
    }

    private fun restoreSavedConfig(): EucapnicConfig {
        if (!prefs.getBoolean(KEY_CONFIG_SAVED, false)) return EucapnicConfig()
        return EucapnicConfig(
            prepDurationSec = prefs.getInt(KEY_PREP_DURATION_SEC, 300),
            breathsPerMin = prefs.getFloat(KEY_BREATHS_PER_MIN, 5.5f),
            inhaleSec = prefs.getFloat(KEY_INHALE_SEC, 4.0f),
            topPauseSec = prefs.getFloat(KEY_TOP_PAUSE_SEC, 0.0f),
            exhaleSec = prefs.getFloat(KEY_EXHALE_SEC, 6.0f),
            bottomPauseSec = prefs.getFloat(KEY_BOTTOM_PAUSE_SEC, 0.9f),
            breathDepthPercent = prefs.getInt(KEY_BREATH_DEPTH_PERCENT, 25)
        )
    }

    private fun persist(config: EucapnicConfig) {
        prefs.edit()
            .putBoolean(KEY_CONFIG_SAVED, true)
            .putInt(KEY_PREP_DURATION_SEC, config.prepDurationSec)
            .putFloat(KEY_BREATHS_PER_MIN, config.breathsPerMin)
            .putFloat(KEY_INHALE_SEC, config.inhaleSec)
            .putFloat(KEY_TOP_PAUSE_SEC, config.topPauseSec)
            .putFloat(KEY_EXHALE_SEC, config.exhaleSec)
            .putFloat(KEY_BOTTOM_PAUSE_SEC, config.bottomPauseSec)
            .putInt(KEY_BREATH_DEPTH_PERCENT, config.breathDepthPercent)
            .apply()
    }

    // ── Saved configurations (most recently used first) ───────────────────

    val pastConfigurations: StateFlow<List<EucapnicPastConfigurationEntity>> =
        pastConfigDao.observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    // ── Individual parameter updates ──────────────────────────────────────

    /**
     * Replace the whole configuration in one call. Used by session screens to
     * mirror their dialog edits into this shared, persisted source of truth.
     */
    fun updateConfig(config: EucapnicConfig) {
        setConfig(config)
    }

    /**
     * Update total prep duration. Does not affect BPM or timers.
     */
    fun updatePrepDuration(seconds: Int) {
        setConfig(_config.value.copy(prepDurationSec = seconds))
    }

    /**
     * Update BPM. All timer values are scaled proportionally to maintain
     * the same phase ratios at the new breathing rate.
     */
    fun updateBpm(newBpm: Float) {
        val clamped = scalingEngine.clampBpm(newBpm)
        setConfig(scalingEngine.scaleTimersFromBpm(_config.value, clamped))
    }

    /**
     * Update inhale duration. BPM is recalculated; other timers unchanged.
     */
    fun updateInhale(seconds: Float) {
        val updated = _config.value.copy(inhaleSec = seconds)
        setConfig(scalingEngine.updateBpmFromTimerChange(updated))
    }

    /**
     * Update top pause duration. BPM is recalculated; other timers unchanged.
     */
    fun updateTopPause(seconds: Float) {
        val updated = _config.value.copy(topPauseSec = seconds)
        setConfig(scalingEngine.updateBpmFromTimerChange(updated))
    }

    /**
     * Update exhale duration. BPM is recalculated; other timers unchanged.
     */
    fun updateExhale(seconds: Float) {
        val updated = _config.value.copy(exhaleSec = seconds)
        setConfig(scalingEngine.updateBpmFromTimerChange(updated))
    }

    /**
     * Update bottom pause duration. BPM is recalculated; other timers unchanged.
     */
    fun updateBottomPause(seconds: Float) {
        val updated = _config.value.copy(bottomPauseSec = seconds)
        setConfig(scalingEngine.updateBpmFromTimerChange(updated))
    }

    /**
     * Update breath depth target. Does not affect BPM or timers.
     */
    fun updateBreathDepth(percent: Int) {
        setConfig(_config.value.copy(breathDepthPercent = percent))
    }

    // ── Persistence ───────────────────────────────────────────────────────

    /**
     * Save the current configuration under the given [name].
     */
    fun saveConfiguration(name: String) {
        val current = _config.value
        val entity = EucapnicPastConfigurationEntity(
            name = name.trim().ifBlank { DEFAULT_CONFIG_NAME },
            prepDurationSec = current.prepDurationSec,
            breathsPerMin = current.breathsPerMin,
            inhaleSec = current.inhaleSec,
            topPauseSec = current.topPauseSec,
            exhaleSec = current.exhaleSec,
            bottomPauseSec = current.bottomPauseSec,
            breathDepthPercent = current.breathDepthPercent,
            createdAtMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            pastConfigDao.insert(entity)
        }
    }

    /**
     * Restore a previously saved configuration. Also bumps its use-count
     * and last-used timestamp so it floats to the top of the list.
     */
    fun loadConfiguration(entity: EucapnicPastConfigurationEntity) {
        setConfig(
            EucapnicConfig(
                prepDurationSec = entity.prepDurationSec,
                breathsPerMin = entity.breathsPerMin,
                inhaleSec = entity.inhaleSec,
                topPauseSec = entity.topPauseSec,
                exhaleSec = entity.exhaleSec,
                bottomPauseSec = entity.bottomPauseSec,
                breathDepthPercent = entity.breathDepthPercent
            )
        )
        viewModelScope.launch {
            pastConfigDao.incrementUseCount(entity.configId, System.currentTimeMillis())
        }
    }

    /**
     * Delete a saved configuration.
     */
    fun deleteConfiguration(configId: Long) {
        viewModelScope.launch {
            pastConfigDao.deleteById(configId)
        }
    }

    companion object {
        private const val DEFAULT_CONFIG_NAME = "Unnamed configuration"
        private const val KEY_CONFIG_SAVED = "eucapnic_config_saved"
        private const val KEY_PREP_DURATION_SEC = "eucapnic_prep_duration_sec"
        private const val KEY_BREATHS_PER_MIN = "eucapnic_breaths_per_min"
        private const val KEY_INHALE_SEC = "eucapnic_inhale_sec"
        private const val KEY_TOP_PAUSE_SEC = "eucapnic_top_pause_sec"
        private const val KEY_EXHALE_SEC = "eucapnic_exhale_sec"
        private const val KEY_BOTTOM_PAUSE_SEC = "eucapnic_bottom_pause_sec"
        private const val KEY_BREATH_DEPTH_PERCENT = "eucapnic_breath_depth_percent"
    }
}
