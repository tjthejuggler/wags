package com.example.wags.domain.usecase.readiness

import kotlin.math.sqrt

/**
 * Detects the precise moment the user stood up using H10 accelerometer data.
 *
 * Algorithm:
 *   1. Compute the vector magnitude of each (x, y, z) ACC sample.
 *   2. Detect a "jerk event" — a window where the magnitude deviates significantly
 *      from the resting baseline (user lying still), indicating the stand motion.
 *   3. After the jerk, wait for the signal to settle back to a low-variance state,
 *      which marks the moment the user is standing still.
 *
 * The detector is armed by calling [arm] when the STAND_PROMPT fires, and
 * [checkSamples] is called periodically with the latest ACC buffer snapshot.
 * Once a stand is confirmed, [detectedTimestampMs] is set and [isDetected] is true.
 *
 * If no stand is detected within [timeoutMs], the fallback timestamp (set via
 * [setFallbackTimestamp]) is used instead.
 *
 * Units: Polar H10 ACC at ±2g, 16-bit → raw values in milli-g (1 g ≈ 1000 raw units).
 */
class StandDetector {

    companion object {
        /** Magnitude deviation above resting baseline that signals a stand motion (milli-g). */
        private const val JERK_THRESHOLD = 300f

        /** Number of consecutive samples that must exceed the jerk threshold. */
        private const val JERK_MIN_SAMPLES = 5

        /**
         * After the jerk, the magnitude variance over a short window must drop below
         * this value to confirm the user has settled into a standing position.
         */
        private const val SETTLE_VARIANCE_THRESHOLD = 8_000f

        /** Window size (samples) used to compute the settle variance check. */
        private const val SETTLE_WINDOW = 40   // ~200ms at 200 Hz

        /** Minimum samples after jerk start before we check for settling. */
        private const val MIN_SAMPLES_AFTER_JERK = 20

        /** How long to wait for a detected stand before giving up (ms). */
        const val TIMEOUT_MS = 8_000L
    }

    /** True once a stand has been confirmed. */
    var isDetected: Boolean = false
        private set

    /** Wall-clock ms of the detected stand moment, or null if not yet detected. */
    var detectedTimestampMs: Long? = null
        private set

    private var isArmed = false
    private var armTimeMs = 0L
    private var fallbackTimestampMs: Long? = null

    // State machine
    private var jerkStartIdx = -1
    private var samplesProcessed = 0

    // Baseline: mean magnitude while supine (computed from the last N samples before arming)
    private var baselineMagnitude = 1000f  // default ~1g

    /**
     * Arm the detector. Call this when the STAND_PROMPT fires.
     * [recentSupineSamples] should be the last ~200 ACC samples (1 second) while supine,
     * used to compute the resting baseline magnitude.
     */
    fun arm(recentSupineSamples: List<Triple<Int, Int, Int>>) {
        isArmed = true
        isDetected = false
        detectedTimestampMs = null
        armTimeMs = System.currentTimeMillis()
        jerkStartIdx = -1
        samplesProcessed = 0

        // Compute baseline magnitude from supine samples
        if (recentSupineSamples.isNotEmpty()) {
            baselineMagnitude = recentSupineSamples
                .map { (x, y, z) -> magnitude(x, y, z) }
                .average()
                .toFloat()
        }
    }

    /** Set the fallback timestamp to use if detection times out. */
    fun setFallbackTimestamp(ts: Long) {
        fallbackTimestampMs = ts
    }

    /**
     * Feed new ACC samples. Returns the detected stand timestamp if a stand was
     * just confirmed, or null otherwise.
     *
     * [samples] should be the latest batch of (x, y, z) ACC samples in order.
     * [batchTimestampMs] is the wall-clock time of the last sample in the batch.
     */
    fun checkSamples(
        samples: List<Triple<Int, Int, Int>>,
        batchTimestampMs: Long = System.currentTimeMillis()
    ): Long? {
        if (!isArmed || isDetected || samples.isEmpty()) return null

        // Timeout: fall back to the FSM timestamp
        if (batchTimestampMs - armTimeMs > TIMEOUT_MS) {
            isDetected = true
            detectedTimestampMs = fallbackTimestampMs ?: batchTimestampMs
            isArmed = false
            return detectedTimestampMs
        }

        val magnitudes = samples.map { (x, y, z) -> magnitude(x, y, z) }

        for ((localIdx, mag) in magnitudes.withIndex()) {
            val deviation = mag - baselineMagnitude

            when {
                // Phase 1: looking for jerk onset
                jerkStartIdx < 0 -> {
                    if (deviation > JERK_THRESHOLD) {
                        jerkStartIdx = samplesProcessed + localIdx
                    }
                }

                // Phase 2: jerk detected — wait for settling
                else -> {
                    val samplesSinceJerk = (samplesProcessed + localIdx) - jerkStartIdx
                    if (samplesSinceJerk >= MIN_SAMPLES_AFTER_JERK) {
                        // Check variance of the last SETTLE_WINDOW magnitudes
                        val windowStart = maxOf(0, localIdx - SETTLE_WINDOW + 1)
                        val window = magnitudes.subList(windowStart, localIdx + 1)
                        if (window.size >= SETTLE_WINDOW / 2) {
                            val variance = window.variance()
                            if (variance < SETTLE_VARIANCE_THRESHOLD) {
                                // Stand confirmed — timestamp is now
                                isDetected = true
                                isArmed = false
                                // Estimate the actual stand moment as jerk onset time.
                                // Approximate: batchTimestampMs is the end of this batch;
                                // each sample is ~5ms apart at 200 Hz.
                                val samplesFromJerkToEnd = magnitudes.size - 1 - localIdx + samplesSinceJerk
                                val jerkOffsetMs = (samplesFromJerkToEnd * 5L)
                                detectedTimestampMs = (batchTimestampMs - jerkOffsetMs)
                                    .coerceAtLeast(armTimeMs)
                                return detectedTimestampMs
                            }
                        }
                    }
                }
            }
        }

        samplesProcessed += samples.size
        return null
    }

    /** Reset to unarmed state. */
    fun reset() {
        isArmed = false
        isDetected = false
        detectedTimestampMs = null
        jerkStartIdx = -1
        samplesProcessed = 0
        fallbackTimestampMs = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun magnitude(x: Int, y: Int, z: Int): Float =
        sqrt((x.toLong() * x + y.toLong() * y + z.toLong() * z).toDouble()).toFloat()

    private fun List<Float>.variance(): Float {
        if (size < 2) return 0f
        val mean = average().toFloat()
        return sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / size
    }
}
