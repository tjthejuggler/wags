package com.example.wags.domain.model

/**
 * Represents the current state of the Eucapnic breathing pacer.
 * 
 * This state is emitted by the pacer engine on each tick and contains
 * all information needed for UI rendering and analytics.
 * 
 * @param phase The current breathing phase
 * @param phaseProgress Progress within the current phase (0.0 to 1.0)
 * @param totalProgress Progress through the entire preparation (0.0 to 1.0)
 * @param breathsCompleted Number of complete breath cycles finished
 * @param currentBpm Current breathing rate in breaths per minute
 * @param elapsedTimeMs Total elapsed time in milliseconds since start
 * @param remainingTimeMs Remaining time in milliseconds until completion
 */
data class PacerState(
    val phase: EucapnicPhase,
    val phaseProgress: Float,           // 0.0-1.0 within current phase
    val totalProgress: Float,           // 0.0-1.0 of total prep duration
    val breathsCompleted: Int,
    val currentBpm: Float,
    val elapsedTimeMs: Long = 0L,
    val remainingTimeMs: Long = 0L
) {
    /**
     * Check if the preparation is complete.
     */
    val isComplete: Boolean
        get() = totalProgress >= 1.0f

    /**
     * Get the current cycle progress (0.0 to 1.0 within the current breath cycle).
     */
    val cycleProgress: Float
        get() = (elapsedTimeMs % (60000f / currentBpm)).toFloat() / (60000f / currentBpm)

    companion object {
        /**
         * Create an initial state for the pacer.
         */
        fun initial(config: EucapnicConfig): PacerState = PacerState(
            phase = EucapnicPhase.INHALE,
            phaseProgress = 0f,
            totalProgress = 0f,
            breathsCompleted = 0,
            currentBpm = config.breathsPerMin,
            elapsedTimeMs = 0L,
            remainingTimeMs = config.prepDurationSec * 1000L
        )
    }
}
