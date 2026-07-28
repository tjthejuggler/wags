package com.example.wags.domain.usecase.breathing

import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.model.EucapnicPhase
import com.example.wags.domain.model.PacerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Pacer engine for Eucapnic Diaphragmatic breathing preparation.
 * 
 * This engine implements a 4-phase breathing cycle:
 * 1. INHALE - Diaphragmatic inhalation
 * 2. TOP_PAUSE - Brief pause at full lung capacity
 * 3. EXHALE - Controlled exhalation
 * 4. BOTTOM_PAUSE - Brief pause at lung empty
 * 
 * The engine is designed to be called at ~60 FPS from a coroutine loop.
 * It provides reactive state management via StateFlow for UI updates.
 * 
 * @param config The eucapnic breathing configuration
 */
@Singleton
class EucapnicPacerEngine @Inject constructor() {

    private val _state = MutableStateFlow<PacerState?>(null)
    val state: StateFlow<PacerState?> = _state.asStateFlow()

    private val _phase = MutableStateFlow(EucapnicPhase.INHALE)
    val phase: StateFlow<EucapnicPhase> = _phase.asStateFlow()

    private val _phaseProgress = MutableStateFlow(0f)
    val phaseProgress: StateFlow<Float> = _phaseProgress.asStateFlow()

    private val _totalProgress = MutableStateFlow(0f)
    val totalProgress: StateFlow<Float> = _totalProgress.asStateFlow()

    private val _breathsCompleted = MutableStateFlow(0)
    val breathsCompleted: StateFlow<Int> = _breathsCompleted.asStateFlow()

    private var config: EucapnicConfig? = null
    private var startTimeMs: Long = 0L
    private var lastUpdateTimeMs: Long = 0L
    private var isRunning = false
    private var lastPhase: EucapnicPhase = EucapnicPhase.INHALE
    private var breathsCompletedCount = 0

    /**
     * Initialize the pacer with a configuration.
     * 
     * @param config The eucapnic breathing configuration
     */
    fun initialize(config: EucapnicConfig) {
        this.config = config
        reset()
    }

    /**
     * Start the pacer.
     * Call this before beginning the tick loop.
     */
    fun start() {
        if (config == null) {
            throw IllegalStateException("Pacer must be initialized before starting")
        }
        isRunning = true
        startTimeMs = System.currentTimeMillis()
        lastUpdateTimeMs = startTimeMs
        emitInitialState()
    }

    /**
     * Stop the pacer.
     */
    fun stop() {
        isRunning = false
    }

    /**
     * Reset the pacer to initial state.
     */
    fun reset() {
        isRunning = false
        startTimeMs = 0L
        lastUpdateTimeMs = 0L
        lastPhase = EucapnicPhase.INHALE
        breathsCompletedCount = 0
        
        config?.let { cfg ->
            _phase.value = EucapnicPhase.INHALE
            _phaseProgress.value = 0f
            _totalProgress.value = 0f
            _breathsCompleted.value = 0
            _state.value = PacerState.initial(cfg)
        }
    }

    /**
     * Update pacer state. Call at ~60 FPS from a coroutine loop.
     * 
     * @param elapsedTimeMs Optional elapsed time in milliseconds. If null, uses system time.
     * @return The current pacer state, or null if not running
     */
    fun tick(elapsedTimeMs: Long? = null): PacerState? {
        if (!isRunning || config == null) {
            return _state.value
        }

        val now = System.currentTimeMillis()
        val elapsed = elapsedTimeMs ?: (now - startTimeMs)
        
        // Update last update time for delta calculations
        if (elapsedTimeMs == null) {
            lastUpdateTimeMs = now
        }

        val cfg = config!!
        val prepDurationMs = cfg.prepDurationSec * 1000L

        // Check if preparation is complete
        if (elapsed >= prepDurationMs) {
            return emitCompleteState(cfg, prepDurationMs)
        }

        // Calculate total progress
        val totalProg = min(1.0f, elapsed.toFloat() / prepDurationMs)
        _totalProgress.value = totalProg

        // Calculate current phase and progress within phase
        val cycleTimeMs = (cfg.cycleTimeSec * 1000).toLong()
        val timeInCycle = elapsed % cycleTimeMs

        val (currentPhase, phaseProg) = determinePhaseAndProgress(cfg, timeInCycle)

        // Detect breath completion (transition from BOTTOM_PAUSE to INHALE)
        if (lastPhase == EucapnicPhase.BOTTOM_PAUSE && currentPhase == EucapnicPhase.INHALE) {
            breathsCompletedCount++
            _breathsCompleted.value = breathsCompletedCount
        }
        lastPhase = currentPhase

        // Update phase state
        _phase.value = currentPhase
        _phaseProgress.value = phaseProg

        // Calculate current BPM (may vary slightly due to timing)
        val currentBpm = if (cycleTimeMs > 0) 60000f / cycleTimeMs else cfg.breathsPerMin

        // Create and emit state
        val pacerState = PacerState(
            phase = currentPhase,
            phaseProgress = phaseProg,
            totalProgress = totalProg,
            breathsCompleted = breathsCompletedCount,
            currentBpm = currentBpm,
            elapsedTimeMs = elapsed,
            remainingTimeMs = max(0L, prepDurationMs - elapsed)
        )

        _state.value = pacerState
        return pacerState
    }

    /**
     * Get the current phase without updating state.
     */
    fun getPhase(): EucapnicPhase = _phase.value

    /**
     * Get the current phase progress without updating state.
     */
    fun getProgress(): Float = _phaseProgress.value

    /**
     * Check if the pacer is currently running.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Check if the preparation is complete.
     */
    fun isComplete(): Boolean = _state.value?.isComplete ?: false

    /**
     * Determine the current phase and progress within that phase.
     */
    private fun determinePhaseAndProgress(
        config: EucapnicConfig,
        timeInCycleMs: Long
    ): Pair<EucapnicPhase, Float> {
        val timeInCycleSec = timeInCycleMs / 1000f

        // Calculate phase boundaries
        val inhaleEnd = config.inhaleSec
        val topPauseEnd = inhaleEnd + config.topPauseSec
        val exhaleEnd = topPauseEnd + config.exhaleSec
        val bottomPauseEnd = exhaleEnd + config.bottomPauseSec

        return when {
            timeInCycleSec < inhaleEnd -> {
                val progress = timeInCycleSec / config.inhaleSec
                Pair(EucapnicPhase.INHALE, progress.coerceIn(0f, 1f))
            }
            timeInCycleSec < topPauseEnd -> {
                val progress = (timeInCycleSec - inhaleEnd) / config.topPauseSec
                Pair(EucapnicPhase.TOP_PAUSE, progress.coerceIn(0f, 1f))
            }
            timeInCycleSec < exhaleEnd -> {
                val progress = (timeInCycleSec - topPauseEnd) / config.exhaleSec
                Pair(EucapnicPhase.EXHALE, progress.coerceIn(0f, 1f))
            }
            else -> {
                val progress = (timeInCycleSec - exhaleEnd) / config.bottomPauseSec
                Pair(EucapnicPhase.BOTTOM_PAUSE, progress.coerceIn(0f, 1f))
            }
        }
    }

    /**
     * Emit the initial state when starting.
     */
    private fun emitInitialState() {
        config?.let { cfg ->
            _state.value = PacerState.initial(cfg)
        }
    }

    /**
     * Emit the final state when preparation is complete.
     */
    private fun emitCompleteState(config: EucapnicConfig, prepDurationMs: Long): PacerState {
        val completeState = PacerState(
            phase = EucapnicPhase.BOTTOM_PAUSE,
            phaseProgress = 1f,
            totalProgress = 1f,
            breathsCompleted = breathsCompletedCount,
            currentBpm = config.breathsPerMin,
            elapsedTimeMs = prepDurationMs,
            remainingTimeMs = 0L
        )
        
        _state.value = completeState
        _totalProgress.value = 1f
        isRunning = false
        
        return completeState
    }

    /**
     * Get visual pacer radius (0.0-1.0) for the current phase.
     * 
     * This provides a smooth visual representation suitable for UI rendering.
     * - INHALE: 0.0 -> 1.0 (expanding)
     * - TOP_PAUSE: 1.0 (held)
     * - EXHALE: 1.0 -> 0.0 (contracting)
     * - BOTTOM_PAUSE: 0.0 (held)
     */
    fun getPacerRadius(): Float {
        val currentPhase = _phase.value
        val progress = _phaseProgress.value

        return when (currentPhase) {
            EucapnicPhase.INHALE -> {
                // Smooth sine curve for inhale: 0 -> 1
                val angle = progress * (Math.PI / 2)
                kotlin.math.sin(angle).toFloat()
            }
            EucapnicPhase.TOP_PAUSE -> {
                // Hold at full expansion
                1f
            }
            EucapnicPhase.EXHALE -> {
                // Smooth sine curve for exhale: 1 -> 0
                val angle = (1f - progress) * (Math.PI / 2)
                kotlin.math.sin(angle).toFloat()
            }
            EucapnicPhase.BOTTOM_PAUSE -> {
                // Hold at full contraction
                0f
            }
        }
    }

    /**
     * Get the estimated time remaining in the current phase (milliseconds).
     */
    fun getTimeRemainingInPhaseMs(): Long {
        if (config == null || !isRunning) return 0L

        val cfg = config!!
        val currentPhase = _phase.value
        val progress = _phaseProgress.value

        val phaseDurationSec = when (currentPhase) {
            EucapnicPhase.INHALE -> cfg.inhaleSec
            EucapnicPhase.TOP_PAUSE -> cfg.topPauseSec
            EucapnicPhase.EXHALE -> cfg.exhaleSec
            EucapnicPhase.BOTTOM_PAUSE -> cfg.bottomPauseSec
        }

        val remainingSec = phaseDurationSec * (1f - progress)
        return (remainingSec * 1000).toLong()
    }
}
