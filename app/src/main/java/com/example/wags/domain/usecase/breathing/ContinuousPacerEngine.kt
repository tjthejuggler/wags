package com.example.wags.domain.usecase.breathing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin

/**
 * Analytically integrated breathing pacer.
 * Uses continuous phase accumulation to prevent discontinuities when rate changes.
 * Supports configurable inhale:exhale ratio.
 *
 * Phase 0.0–inhaleFraction = inhale, inhaleFraction–1.0 = exhale (normalized cycle).
 * Call [tick] at ~60 FPS from a coroutine loop.
 */
class ContinuousPacerEngine @Inject constructor() {

    private val _phase = MutableStateFlow(0f)
    val phase: StateFlow<Float> = _phase.asStateFlow()

    private val _breathPhaseLabel = MutableStateFlow("INHALE")
    val breathPhaseLabel: StateFlow<String> = _breathPhaseLabel.asStateFlow()

    private var accumulatedPhase = 0.0
    private var lastUpdateTimeMs = 0L

    /**
     * Update pacer phase. Call at ~60 FPS from a coroutine loop.
     * @param rateBpm breathing rate in breaths per minute
     * @param ieRatio inhale:exhale ratio (e.g. 1.0 = equal, 1.5 = longer exhale)
     */
    fun tick(rateBpm: Float, ieRatio: Float = 1.0f) {
        val now = System.currentTimeMillis()
        if (lastUpdateTimeMs == 0L) {
            lastUpdateTimeMs = now
            return
        }
        val dtSeconds = (now - lastUpdateTimeMs) / 1000.0
        lastUpdateTimeMs = now

        val cyclesPerSecond = rateBpm / 60.0
        accumulatedPhase = (accumulatedPhase + cyclesPerSecond * dtSeconds) % 1.0

        _phase.value = accumulatedPhase.toFloat()

        val inhaleFraction = 1.0f / (1.0f + ieRatio)
        _breathPhaseLabel.value = if (accumulatedPhase < inhaleFraction) "INHALE" else "EXHALE"
    }

    fun reset() {
        accumulatedPhase = 0.0
        lastUpdateTimeMs = 0L
        _phase.value = 0f
        _breathPhaseLabel.value = "INHALE"
    }

    /**
     * Compute visual pacer radius (0.0–1.0) from current phase.
     * Smooth sine curve for natural feel.
     * @param ieRatio inhale:exhale ratio (must match the value passed to [tick])
     */
    fun getPacerRadius(ieRatio: Float = 1.0f): Float {
        val inhaleFraction = 1.0 / (1.0 + ieRatio)
        return if (accumulatedPhase < inhaleFraction) {
            // Inhale: 0 → 1
            sin(PI * accumulatedPhase / inhaleFraction / 2.0).toFloat()
        } else {
            // Exhale: 1 → 0
            val exhalePhase = (accumulatedPhase - inhaleFraction) / (1.0 - inhaleFraction)
            sin(PI * (1.0 - exhalePhase) / 2.0).toFloat()
        }
    }
}
