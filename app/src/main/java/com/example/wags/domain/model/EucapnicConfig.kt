package com.example.wags.domain.model

/**
 * Configuration for Eucapnic Diaphragmatic breathing preparation.
 * 
 * This breathing pattern is designed to reduce CO2 levels through controlled
 * diaphragmatic breathing with specific timing parameters.
 * 
 * @param prepDurationSec Total duration of the preparation phase in seconds
 * @param breathsPerMin Target breathing rate in breaths per minute
 * @param inhaleSec Duration of inhale phase in seconds
 * @param topPauseSec Duration of pause after inhale (at top of breath) in seconds
 * @param exhaleSec Duration of exhale phase in seconds
 * @param bottomPauseSec Duration of pause after exhale (at bottom of breath) in seconds
 * @param breathDepthPercent Target breath depth as percentage of maximum capacity
 */
data class EucapnicConfig(
    val prepDurationSec: Int = 300,
    val breathsPerMin: Float = 5.5f,
    val inhaleSec: Float = 4.0f,
    val topPauseSec: Float = 0.0f,
    val exhaleSec: Float = 6.0f,
    val bottomPauseSec: Float = 0.9f,
    val breathDepthPercent: Int = 25
) {
    /**
     * Calculate the total cycle time in seconds.
     * T_cycle = T_inhale + T_top_pause + T_exhale + T_bottom_pause
     */
    val cycleTimeSec: Float
        get() = inhaleSec + topPauseSec + exhaleSec + bottomPauseSec

    /**
     * Validate the configuration.
     * @throws IllegalArgumentException if any values are invalid
     */
    fun validate() {
        require(prepDurationSec > 0) { "prepDurationSec must be positive" }
        require(breathsPerMin > 0) { "breathsPerMin must be positive" }
        require(inhaleSec > 0) { "inhaleSec must be positive" }
        require(topPauseSec >= 0) { "topPauseSec must be non-negative" }
        require(exhaleSec > 0) { "exhaleSec must be positive" }
        require(bottomPauseSec >= 0) { "bottomPauseSec must be non-negative" }
        require(breathDepthPercent in 1..100) { "breathDepthPercent must be between 1 and 100" }
    }

    /**
     * Calculate the actual BPM based on current timer values.
     * BPM = 60 / T_cycle
     */
    fun calculateBpm(): Float {
        val cycleTime = cycleTimeSec
        return if (cycleTime > 0) 60f / cycleTime else 0f
    }
}
