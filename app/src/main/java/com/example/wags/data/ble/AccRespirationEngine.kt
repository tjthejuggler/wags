package com.example.wags.data.ble

import com.example.wags.domain.model.BreathPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes H10 accelerometer Z-axis data at 200 Hz to detect breathing phase.
 * Algorithm: smoothed delta between recent and older window means, with hysteresis.
 *
 * Calibration profiles are stored per posture (standing/sitting/laying) and
 * whether a breath hold is expected.
 */
@Singleton
class AccRespirationEngine @Inject constructor() {

    companion object {
        private const val RECENT_WINDOW = 20       // ~100ms at 200 Hz
        private const val OLDER_WINDOW_START = 80  // samples back
        private const val OLDER_WINDOW_END = 20    // samples back (exclusive)
        private const val BREATH_DEBOUNCE = 2      // samples before phase flip
        private const val HOLD_DEBOUNCE = 10       // samples before hold detection
        private const val DEFAULT_INHALE_THRESHOLD = 0.3f
        private const val DEFAULT_EXHALE_THRESHOLD = -0.3f
    }

    private val zBuffer = CircularBuffer<Int>(512)  // ~2.5s at 200 Hz

    private val _breathPhase = MutableStateFlow<BreathPhase>(BreathPhase.EXHALING)
    val breathPhase: StateFlow<BreathPhase> = _breathPhase.asStateFlow()

    private val _breathRateBpm = MutableStateFlow<Float?>(null)
    val breathRateBpm: StateFlow<Float?> = _breathRateBpm.asStateFlow()

    // Calibration thresholds (updated from AccCalibrationEntity)
    private var inhaleThreshold = DEFAULT_INHALE_THRESHOLD
    private var exhaleThreshold = DEFAULT_EXHALE_THRESHOLD
    private var holdDebounceCount = HOLD_DEBOUNCE

    // Phase tracking
    private var debounceCounter = 0
    private var pendingPhase: BreathPhase? = null
    private var lastPhaseChangeTime = System.currentTimeMillis()
    private val breathCycleDurationsMs = ArrayDeque<Long>(10)

    /** Feed a new ACC Z-axis sample (raw integer from Polar SDK). */
    fun processSample(zValue: Int) {
        zBuffer.write(zValue)
        if (zBuffer.size() < OLDER_WINDOW_START) return

        val recent = zBuffer.readLast(RECENT_WINDOW)
        val older = zBuffer.readLast(OLDER_WINDOW_START).take(OLDER_WINDOW_START - OLDER_WINDOW_END)

        val recentMean = recent.map { it.toFloat() }.average().toFloat()
        val olderMean = older.map { it.toFloat() }.average().toFloat()
        val delta = recentMean - olderMean

        val detectedPhase = when {
            delta > inhaleThreshold -> BreathPhase.INHALING
            delta < exhaleThreshold -> BreathPhase.EXHALING
            else -> BreathPhase.HOLDING
        }

        if (detectedPhase != _breathPhase.value) {
            if (detectedPhase == pendingPhase) {
                val requiredDebounce = if (detectedPhase == BreathPhase.HOLDING) holdDebounceCount else BREATH_DEBOUNCE
                debounceCounter++
                if (debounceCounter >= requiredDebounce) {
                    commitPhaseChange(detectedPhase)
                }
            } else {
                pendingPhase = detectedPhase
                debounceCounter = 1
            }
        } else {
            pendingPhase = null
            debounceCounter = 0
        }
    }

    private fun commitPhaseChange(newPhase: BreathPhase) {
        val now = System.currentTimeMillis()
        if (newPhase == BreathPhase.INHALING && _breathPhase.value == BreathPhase.EXHALING) {
            val cycleDuration = now - lastPhaseChangeTime
            if (cycleDuration in 2000..15000) {  // 4–30 BPM range
                breathCycleDurationsMs.addLast(cycleDuration)
                if (breathCycleDurationsMs.size > 5) breathCycleDurationsMs.removeFirst()
                val avgCycleMs = breathCycleDurationsMs.average()
                _breathRateBpm.value = (60_000.0 / avgCycleMs).toFloat()
            }
        }
        lastPhaseChangeTime = now
        _breathPhase.value = newPhase
        pendingPhase = null
        debounceCounter = 0
    }

    /** Apply calibration from a stored AccCalibrationEntity. */
    fun applyCalibration(
        inhaleDeltaThreshold: Float,
        exhaleDeltaThreshold: Float,
        holdDebounce: Int
    ) {
        inhaleThreshold = inhaleDeltaThreshold
        exhaleThreshold = exhaleDeltaThreshold
        holdDebounceCount = holdDebounce
    }

    fun reset() {
        zBuffer.clear()
        _breathPhase.value = BreathPhase.EXHALING
        _breathRateBpm.value = null
        debounceCounter = 0
        pendingPhase = null
        breathCycleDurationsMs.clear()
    }
}
