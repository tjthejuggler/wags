package com.example.wags.ui.apnea

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.model.EucapnicPhase
import com.example.wags.domain.model.PacerState
import com.example.wags.domain.usecase.breathing.EucapnicPacerEngine
import com.example.wags.ui.common.WagsFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the active Eucapnic Diaphragmatic breathing pacer session.
 *
 * Manages the [EucapnicPacerEngine] lifecycle, drives the ~60 FPS tick loop,
 * and exposes reactive state for the UI. Also handles audio/haptic feedback
 * on phase transitions and session completion.
 *
 * Lifecycle:
 * 1. [startPrep] — initialises the engine with a config and begins the tick loop.
 * 2. [pausePrep] / [resumePrep] — pauses/resumes the tick loop (e.g. app backgrounded).
 * 3. [stopPrep] — stops the engine and cancels the tick loop.
 * 4. Engine auto-completes when prep duration elapses → [stopPrep] is called internally.
 */
@HiltViewModel
class EucapnicPacerViewModel @Inject constructor(
    private val pacerEngine: EucapnicPacerEngine,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // ── Engine state (exposed directly) ─────────────────────────────────────

    /** Current pacer state emitted by the engine on each tick. */
    val pacerState: StateFlow<PacerState?> = pacerEngine.state

    /** Current breathing phase. */
    val phase: StateFlow<EucapnicPhase> = pacerEngine.phase

    /** Progress within the current phase (0.0–1.0). */
    val phaseProgress: StateFlow<Float> = pacerEngine.phaseProgress

    /** Progress through the entire prep duration (0.0–1.0). */
    val totalProgress: StateFlow<Float> = pacerEngine.totalProgress

    /** Number of complete breath cycles finished. */
    val breathsCompleted: StateFlow<Int> = pacerEngine.breathsCompleted

    // ── UI-specific state ───────────────────────────────────────────────────

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    /** Whether haptic feedback (vibration) is enabled for phase transitions. */
    private val _vibrationEnabled = MutableStateFlow(true)
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    /** Remaining time in the total prep duration (milliseconds). */
    private val _remainingTimeMs = MutableStateFlow(0L)
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()

    /** Current BPM from the engine. */
    private val _currentBpm = MutableStateFlow(0f)
    val currentBpm: StateFlow<Float> = _currentBpm.asStateFlow()

    /** Visual radius for the gauge (0.0–1.0), computed by the engine. */
    private val _pacerRadius = MutableStateFlow(0f)
    val pacerRadius: StateFlow<Float> = _pacerRadius.asStateFlow()

    /** The config used for this session (stored for depth scaling in UI). */
    private val _config = MutableStateFlow<EucapnicConfig?>(null)
    val config: StateFlow<EucapnicConfig?> = _config.asStateFlow()

    // ── Internal ────────────────────────────────────────────────────────────

    private var tickJob: Job? = null
    private var lastPhase: EucapnicPhase = EucapnicPhase.INHALE
    private var completionFeedbackFired = false

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Start the preparation pacing session with the given configuration.
     */
    fun startPrep(config: EucapnicConfig) {
        config.validate()
        _config.value = config
        _isComplete.value = false
        _isPaused.value = false
        completionFeedbackFired = false
        lastPhase = EucapnicPhase.INHALE

        pacerEngine.initialize(config)
        pacerEngine.start()
        _isRunning.value = true
        _remainingTimeMs.value = config.prepDurationSec * 1000L
        _currentBpm.value = config.breathsPerMin

        startTickLoop()
    }

    /**
     * Stop the pacing session. Cancels the tick loop and stops the engine.
     */
    fun stopPrep() {
        tickJob?.cancel()
        tickJob = null
        pacerEngine.stop()
        _isRunning.value = false
        _isPaused.value = false
    }

    /**
     * Pause the tick loop (e.g. when app goes to background).
     * The engine state is preserved; the loop simply stops ticking.
     */
    fun pausePrep() {
        if (!_isRunning.value || _isComplete.value) return
        tickJob?.cancel()
        tickJob = null
        _isPaused.value = true
    }

    /**
     * Resume the tick loop after a pause.
     */
    fun resumePrep() {
        if (!_isRunning.value || !_isPaused.value || _isComplete.value) return
        _isPaused.value = false
        startTickLoop()
    }

    /**
     * Enable or disable haptic feedback (vibration) for phase transitions.
     */
    fun setVibrationEnabled(enabled: Boolean) {
        _vibrationEnabled.value = enabled
    }

    // ── Tick loop ───────────────────────────────────────────────────────────

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive && pacerEngine.isRunning()) {
                delay(TICK_INTERVAL_MS)

                val state = pacerEngine.tick() ?: break

                // Update UI-facing flows
                _pacerRadius.value = pacerEngine.getPacerRadius()
                _remainingTimeMs.value = state.remainingTimeMs
                _currentBpm.value = state.currentBpm

                // Haptic feedback on phase transitions
                if (state.phase != lastPhase) {
                    lastPhase = state.phase
                    firePhaseTransitionHaptic(state.phase)
                }

                // Check completion
                if (state.isComplete) {
                    _isComplete.value = true
                    _isRunning.value = false
                    if (!completionFeedbackFired) {
                        completionFeedbackFired = true
                        WagsFeedback.sessionEnd(appContext)
                    }
                    break
                }
            }
        }
    }

    // ── Haptic feedback ─────────────────────────────────────────────────────

    private fun firePhaseTransitionHaptic(newPhase: EucapnicPhase) {
        if (!_vibrationEnabled.value) return
        when (newPhase) {
            EucapnicPhase.INHALE       -> WagsFeedback.breathInhale(appContext)
            EucapnicPhase.EXHALE       -> WagsFeedback.breathExhale(appContext)
            EucapnicPhase.TOP_PAUSE    -> WagsFeedback.breathExhale(appContext)
            EucapnicPhase.BOTTOM_PAUSE -> WagsFeedback.breathInhale(appContext)
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopPrep()
    }

    companion object {
        /** ~60 FPS tick interval. */
        private const val TICK_INTERVAL_MS = 16L
    }
}
