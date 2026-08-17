package com.example.wags.domain.model

/**
 * Identifies a specific personal-best pool within a drill type.
 *
 * Each unique [DrillContext] gets its own full 6-tier trophy hierarchy
 * (🏆 through 🏆🏆🏆🏆🏆🏆) across the 5 standard apnea settings.
 *
 * Examples:
 *   DrillContext.FREE_HOLD                          → tableType IS NULL
 *   DrillContext.progressiveO2(breathPeriodSec=60)  → tableType='PROGRESSIVE_O2' AND drillParamValue=60
 *   DrillContext.minBreath(sessionDurationSec=300)  → tableType='MIN_BREATH' AND drillParamValue=300
 */
data class DrillContext(
    /** null = free hold, "PROGRESSIVE_O2", "MIN_BREATH", etc. */
    val drillType: String?,
    /** The specific drill-parameter value that partitions PB pools (e.g. 60 for 60s breath period). */
    val drillParamValue: Int? = null,
    /** Human-readable name for UI display. */
    val displayName: String = "Free Hold"
) {
    companion object {
        val FREE_HOLD = DrillContext(drillType = null, displayName = "Free Hold")

        /** Matches ALL Progressive O₂ records regardless of breath period. */
        val PROGRESSIVE_O2_ANY = DrillContext(
            drillType = "PROGRESSIVE_O2",
            drillParamValue = null,
            displayName = "Progressive O₂"
        )

        /** Matches ALL Min Breath records regardless of session duration. */
        val MIN_BREATH_ANY = DrillContext(
            drillType = "MIN_BREATH",
            drillParamValue = null,
            displayName = "Min Breath"
        )

        fun progressiveO2(breathPeriodSec: Int) = DrillContext(
            drillType = "PROGRESSIVE_O2",
            drillParamValue = breathPeriodSec,
            displayName = "Progressive O₂ (${breathPeriodSec}s breath)"
        )

        fun minBreath(sessionDurationSec: Int) = DrillContext(
            drillType = "MIN_BREATH",
            drillParamValue = sessionDurationSec,
            displayName = "Min Breath (${sessionDurationSec / 60}min)"
        )

        /** Till-Contraction mode — one PB pool (best time-to-first-contraction). */
        val CONTRACTION_TILL = DrillContext(
            drillType = "WONKA_FIRST_CONTRACTION",
            drillParamValue = null,
            displayName = "Till Contraction"
        )

        /** Contraction-Count mode — PB pool per contraction target (e.g. 10 contractions). */
        fun contractionCount(target: Int) = DrillContext(
            drillType = "WONKA_ENDURANCE",
            drillParamValue = target,
            displayName = "Contraction Count ($target)"
        )

        /** Matches ALL Contraction-Count records regardless of target. */
        val CONTRACTION_COUNT_ANY = DrillContext(
            drillType = "WONKA_ENDURANCE",
            drillParamValue = null,
            displayName = "Contraction Count"
        )

        /** Reconstruct from navigation arguments. */
        fun fromNavArgs(drillType: String?, drillParamValue: Int?): DrillContext {
            if (drillType.isNullOrEmpty()) return FREE_HOLD
            if (drillParamValue == null) {
                // No specific param → "any" variant (shows PBs across all param values)
                return when (drillType) {
                    "PROGRESSIVE_O2" -> PROGRESSIVE_O2_ANY
                    "MIN_BREATH" -> MIN_BREATH_ANY
                    "WONKA_FIRST_CONTRACTION" -> CONTRACTION_TILL
                    "WONKA_ENDURANCE" -> CONTRACTION_COUNT_ANY
                    else -> DrillContext(drillType, null, drillType)
                }
            }
            return when (drillType) {
                "PROGRESSIVE_O2" -> progressiveO2(drillParamValue)
                "MIN_BREATH" -> minBreath(drillParamValue)
                "WONKA_ENDURANCE" -> contractionCount(drillParamValue)
                else -> DrillContext(drillType, drillParamValue, drillType)
            }
        }
    }
}
