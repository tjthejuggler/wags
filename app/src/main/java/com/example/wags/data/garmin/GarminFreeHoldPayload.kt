package com.example.wags.data.garmin

/**
 * Represents the decoded payload received from the Garmin watch after a Free Hold session.
 *
 * The watch sends a dictionary with bit-packed HR/SpO2 samples.
 * This class holds the unpacked, ready-to-persist data.
 */
data class GarminFreeHoldPayload(
    /** Duration of the hold in milliseconds. */
    val durationMs: Long,
    /** Lung volume setting used on the watch (FULL / EMPTY / PARTIAL). */
    val lungVolume: String,
    /** Prep type setting used on the watch (NO_PREP / RESONANCE / HYPER). */
    val prepType: String,
    /** Time of day auto-detected on the watch (MORNING / DAY / NIGHT). */
    val timeOfDay: String,
    /** Elapsed ms from hold start to first contraction, null if none. */
    val firstContractionMs: Long?,
    /** List of contraction elapsed-ms timestamps. */
    val contractionTimesMs: List<Long>,
    /** Unpacked telemetry samples (HR and SpO2 per second). */
    val telemetrySamples: List<TelemetrySample>,
    /** Epoch ms when the hold started. */
    val startEpochMs: Long,
    /** Epoch ms when the hold ended. */
    val endEpochMs: Long
)

/**
 * A single telemetry sample unpacked from the bit-packed format.
 * HR = null means the watch had no reading; SpO2 = null means no reading.
 */
data class TelemetrySample(
    val heartRateBpm: Int?,
    val spO2: Int?
)
