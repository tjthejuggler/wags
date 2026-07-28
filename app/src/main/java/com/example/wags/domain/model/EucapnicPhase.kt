package com.example.wags.domain.model

/**
 * Represents the four phases of Eucapnic Diaphragmatic breathing.
 * 
 * The breathing cycle follows this sequence:
 * 1. INHALE - Diaphragmatic inhalation
 * 2. TOP_PAUSE - Brief pause at full lung capacity
 * 3. EXHALE - Controlled exhalation
 * 4. BOTTOM_PAUSE - Brief pause at lung empty
 */
enum class EucapnicPhase {
    INHALE,
    TOP_PAUSE,
    EXHALE,
    BOTTOM_PAUSE;

    /**
     * Get the display name for this phase.
     */
    fun displayName(): String = when (this) {
        INHALE -> "Inhale"
        TOP_PAUSE -> "Hold"
        EXHALE -> "Exhale"
        BOTTOM_PAUSE -> "Pause"
    }

    /**
     * Check if this is an active breathing phase (inhale or exhale).
     */
    fun isActiveBreathing(): Boolean = this == INHALE || this == EXHALE

    /**
     * Check if this is a pause phase (top or bottom).
     */
    fun isPause(): Boolean = this == TOP_PAUSE || this == BOTTOM_PAUSE
}
