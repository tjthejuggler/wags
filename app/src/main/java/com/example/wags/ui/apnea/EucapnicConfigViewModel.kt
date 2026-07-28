package com.example.wags.ui.apnea

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
    private val pastConfigDao: EucapnicPastConfigurationDao
) : ViewModel() {

    // ── Current configuration ─────────────────────────────────────────────

    private val _config = MutableStateFlow(EucapnicConfig())
    val config: StateFlow<EucapnicConfig> = _config.asStateFlow()

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
     * Update total prep duration. Does not affect BPM or timers.
     */
    fun updatePrepDuration(seconds: Int) {
        _config.value = _config.value.copy(prepDurationSec = seconds)
    }

    /**
     * Update BPM. All timer values are scaled proportionally to maintain
     * the same phase ratios at the new breathing rate.
     */
    fun updateBpm(newBpm: Float) {
        val clamped = scalingEngine.clampBpm(newBpm)
        _config.value = scalingEngine.scaleTimersFromBpm(_config.value, clamped)
    }

    /**
     * Update inhale duration. BPM is recalculated; other timers unchanged.
     */
    fun updateInhale(seconds: Float) {
        val updated = _config.value.copy(inhaleSec = seconds)
        _config.value = scalingEngine.updateBpmFromTimerChange(updated)
    }

    /**
     * Update top pause duration. BPM is recalculated; other timers unchanged.
     */
    fun updateTopPause(seconds: Float) {
        val updated = _config.value.copy(topPauseSec = seconds)
        _config.value = scalingEngine.updateBpmFromTimerChange(updated)
    }

    /**
     * Update exhale duration. BPM is recalculated; other timers unchanged.
     */
    fun updateExhale(seconds: Float) {
        val updated = _config.value.copy(exhaleSec = seconds)
        _config.value = scalingEngine.updateBpmFromTimerChange(updated)
    }

    /**
     * Update bottom pause duration. BPM is recalculated; other timers unchanged.
     */
    fun updateBottomPause(seconds: Float) {
        val updated = _config.value.copy(bottomPauseSec = seconds)
        _config.value = scalingEngine.updateBpmFromTimerChange(updated)
    }

    /**
     * Update breath depth target. Does not affect BPM or timers.
     */
    fun updateBreathDepth(percent: Int) {
        _config.value = _config.value.copy(breathDepthPercent = percent)
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
        _config.value = EucapnicConfig(
            prepDurationSec = entity.prepDurationSec,
            breathsPerMin = entity.breathsPerMin,
            inhaleSec = entity.inhaleSec,
            topPauseSec = entity.topPauseSec,
            exhaleSec = entity.exhaleSec,
            bottomPauseSec = entity.bottomPauseSec,
            breathDepthPercent = entity.breathDepthPercent
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
    }
}
