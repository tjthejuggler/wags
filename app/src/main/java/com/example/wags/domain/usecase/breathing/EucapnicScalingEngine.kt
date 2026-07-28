package com.example.wags.domain.usecase.breathing

import com.example.wags.domain.model.EucapnicConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Bi-directional scaling engine for Eucapnic Diaphragmatic breathing configuration.
 * 
 * This engine handles the mathematical relationships between:
 * - BPM (breaths per minute) and individual timer values
 * - Individual timer changes and resulting BPM
 * 
 * Core formulas:
 * - T_cycle = T_inhale + T_top_pause + T_exhale + T_bottom_pause
 * - BPM = 60 / T_cycle
 * 
 * When BPM changes: All timers scale proportionally to maintain the same ratios
 * When individual timer changes: BPM is recalculated, other timers remain unchanged
 */
@Singleton
class EucapnicScalingEngine @Inject constructor() {

    /**
     * Calculate BPM from the current timer configuration.
     * 
     * @param config The current eucapnic configuration
     * @return The calculated BPM, or 0f if cycle time is invalid
     */
    fun calculateBpmFromTimers(config: EucapnicConfig): Float {
        val cycleTime = config.cycleTimeSec
        return if (cycleTime > 0) {
            60f / cycleTime
        } else {
            0f
        }
    }

    /**
     * Scale all timer values proportionally when BPM changes.
     * 
     * This maintains the relative ratios between all phases while adjusting
     * the overall breathing rate.
     * 
     * @param config The current configuration
     * @param newBpm The target BPM
     * @return A new configuration with scaled timers
     * @throws IllegalArgumentException if newBpm is not positive
     */
    fun scaleTimersFromBpm(config: EucapnicConfig, newBpm: Float): EucapnicConfig {
        require(newBpm > 0) { "BPM must be positive" }

        val currentBpm = calculateBpmFromTimers(config)
        if (currentBpm == 0f) {
            // If current BPM is invalid, return config with default timers for new BPM
            return createDefaultConfigForBpm(newBpm)
        }

        val scalingFactor = currentBpm / newBpm

        return config.copy(
            breathsPerMin = newBpm,
            inhaleSec = (config.inhaleSec * scalingFactor).coerceAtLeast(0.1f),
            topPauseSec = (config.topPauseSec * scalingFactor).coerceAtLeast(0f),
            exhaleSec = (config.exhaleSec * scalingFactor).coerceAtLeast(0.1f),
            bottomPauseSec = (config.bottomPauseSec * scalingFactor).coerceAtLeast(0f)
        )
    }

    /**
     * Update BPM when an individual timer value changes.
     * 
     * This recalculates the BPM based on the new cycle time while keeping
     * all other timer values unchanged.
     * 
     * @param config The configuration with one timer modified
     * @return A new configuration with updated BPM
     */
    fun updateBpmFromTimerChange(config: EucapnicConfig): EucapnicConfig {
        val newBpm = calculateBpmFromTimers(config)
        return config.copy(breathsPerMin = newBpm)
    }

    /**
     * Calculate the inhale:exhale ratio as a pair of percentages.
     * 
     * @param config The current configuration
     * @return Pair of (inhalePercentage, exhalePercentage) that sum to 1.0
     */
    fun calculateInhaleExhaleRatio(config: EucapnicConfig): Pair<Float, Float> {
        val activeBreathingTime = config.inhaleSec + config.exhaleSec
        return if (activeBreathingTime > 0) {
            val inhaleRatio = config.inhaleSec / activeBreathingTime
            val exhaleRatio = config.exhaleSec / activeBreathingTime
            Pair(inhaleRatio, exhaleRatio)
        } else {
            Pair(0.5f, 0.5f) // Default to equal split if no active time
        }
    }

    /**
     * Calculate the phase distribution as percentages of the total cycle.
     * 
     * @param config The current configuration
     * @return Map of phase to its percentage of total cycle time
     */
    fun calculatePhaseDistribution(config: EucapnicConfig): Map<String, Float> {
        val cycleTime = config.cycleTimeSec
        return if (cycleTime > 0) {
            mapOf(
                "inhale" to (config.inhaleSec / cycleTime),
                "topPause" to (config.topPauseSec / cycleTime),
                "exhale" to (config.exhaleSec / cycleTime),
                "bottomPause" to (config.bottomPauseSec / cycleTime)
            )
        } else {
            mapOf(
                "inhale" to 0.25f,
                "topPause" to 0.0f,
                "exhale" to 0.5f,
                "bottomPause" to 0.25f
            )
        }
    }

    /**
     * Validate that the configuration is internally consistent.
     * 
     * @param config The configuration to validate
     * @return true if valid, false otherwise
     */
    fun validateConsistency(config: EucapnicConfig): Boolean {
        val calculatedBpm = calculateBpmFromTimers(config)
        val bpmDifference = kotlin.math.abs(calculatedBpm - config.breathsPerMin)
        
        // Allow small floating point differences (within 0.1 BPM)
        val isBpmConsistent = bpmDifference < 0.1f
        
        // Validate that all timers are non-negative and at least one is positive
        val areTimersValid = config.inhaleSec > 0 && 
                           config.exhaleSec > 0 && 
                           config.topPauseSec >= 0 && 
                           config.bottomPauseSec >= 0
        
        return isBpmConsistent && areTimersValid
    }

    /**
     * Create a default configuration for a given BPM.
     * Uses standard eucapnic ratios: 40% inhale, 0% top pause, 50% exhale, 10% bottom pause
     */
    private fun createDefaultConfigForBpm(bpm: Float): EucapnicConfig {
        val cycleTime = 60f / bpm
        return EucapnicConfig(
            breathsPerMin = bpm,
            inhaleSec = cycleTime * 0.4f,
            topPauseSec = 0f,
            exhaleSec = cycleTime * 0.5f,
            bottomPauseSec = cycleTime * 0.1f
        )
    }

    /**
     * Clamp BPM to a reasonable physiological range.
     * 
     * @param bpm The BPM to clamp
     * @return BPM clamped to [3.0, 15.0]
     */
    fun clampBpm(bpm: Float): Float {
        return max(3.0f, min(15.0f, bpm))
    }

    /**
     * Clamp timer values to reasonable ranges.
     * 
     * @param config The configuration to clamp
     * @return Configuration with clamped timer values
     */
    fun clampTimers(config: EucapnicConfig): EucapnicConfig {
        return config.copy(
            inhaleSec = max(0.5f, min(20.0f, config.inhaleSec)),
            topPauseSec = max(0f, min(5.0f, config.topPauseSec)),
            exhaleSec = max(0.5f, min(20.0f, config.exhaleSec)),
            bottomPauseSec = max(0f, min(5.0f, config.bottomPauseSec))
        )
    }
}
