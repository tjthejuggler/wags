package com.example.wags.domain.usecase.apnea

/**
 * User-customizable vibration warning for apnea drills.
 *
 * A warning plays a sequence of short "beat" pulses during the final
 * [windowSec] seconds before a phase (hold or breath) ends, optionally
 * followed by one long high-impact pulse that covers the final second —
 * the "it ends NOW" indicator.
 *
 * Example: windowSec = 5, intervalSec = 1, finalPulseMs = 1000 →
 * four beats at 1 s intervals during the first 4 s, then one long
 * 1 s intense pulse right at the last second.
 */
data class ApneaVibrationWarningConfig(
    /** Master switch for this warning. */
    val enabled: Boolean = true,
    /** How many seconds before the phase end the warning starts (1–20). */
    val windowSec: Int = 10,
    /** Intensity of the beat pulses, 0–100 %. */
    val intensityPct: Int = 60,
    /** Milliseconds between beat pulses — the "rapidness" / beat (250–2000, ¼–2 s). */
    val intervalMs: Int = 1000,
    /** Special long/intense vibration right at the last second. */
    val finalPulseEnabled: Boolean = true,
    /** Duration of the final pulse in ms (200–2000). */
    val finalPulseMs: Int = 400,
    /** Intensity of the final pulse, 0–100 %. */
    val finalIntensityPct: Int = 100
) {
    val windowMs: Long get() = windowSec * 1000L
    val beatAmplitude: Int get() = pctToAmplitude(intensityPct)
    val finalAmplitude: Int get() = pctToAmplitude(finalIntensityPct)

    companion object {
        /** Maps a 0–100 % intensity to a 0–255 VibrationEffect amplitude. */
        fun pctToAmplitude(pct: Int): Int = (pct.coerceIn(0, 100) * 255) / 100

        /**
         * Default hold-ending warning — mirrors the user's reference setup:
         * 5 s window, strong 1 s beats, then a long 1 s intense final pulse.
         */
        val HOLD_DEFAULT = ApneaVibrationWarningConfig(
            enabled = true,
            windowSec = 5,
            intensityPct = 80,
            intervalMs = 1000,
            finalPulseEnabled = true,
            finalPulseMs = 1000,
            finalIntensityPct = 100
        )

        /**
         * Default breath-ending warning — mirrors the legacy behaviour:
         * ticks once per second during the last 10 s of the breathe phase,
         * with a longer 400 ms high pulse at the final second.
         */
        val BREATH_DEFAULT = ApneaVibrationWarningConfig(
            enabled = true,
            windowSec = 10,
            intensityPct = 60,
            intervalMs = 1000,
            finalPulseEnabled = true,
            finalPulseMs = 400,
            finalIntensityPct = 100
        )
    }
}
