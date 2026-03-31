package com.example.wags.ui.common

/**
 * String constants for the advice sections.
 * These match the `section` column stored in the `advice` table.
 */
object AdviceSection {
    const val HOME = "home"
    const val APNEA = "apnea"
    const val APNEA_HYPER = "apnea_hyper"
    const val BREATHING = "breathing"
    const val READINESS = "readiness"
    const val MORNING = "morning"
    const val MEDITATION = "meditation"

    /** Human-readable label for each section key. */
    fun label(section: String): String = when (section) {
        HOME -> "Home"
        APNEA -> "Apnea Training"
        APNEA_HYPER -> "Apnea – Hyperventilating"
        BREATHING -> "Resonance Breathing"
        READINESS -> "HRV Readiness"
        MORNING -> "Morning Readiness"
        MEDITATION -> "Meditation / NSDR"
        else -> section
    }

    /** All sections in display order. */
    val all = listOf(HOME, APNEA, APNEA_HYPER, BREATHING, READINESS, MORNING, MEDITATION)
}
