package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.OximeterReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors incoming oximeter readings and triggers an abort when SpO₂ drops
 * below the user-defined critical threshold. Delegates audio/haptic alerts to
 * [ApneaAudioHapticEngine].
 */
@Singleton
class OximeterSafetyMonitor @Inject constructor(
    private val audioHapticEngine: ApneaAudioHapticEngine
) {
    private val _abortTriggered = MutableStateFlow(false)
    val abortTriggered: StateFlow<Boolean> = _abortTriggered.asStateFlow()

    private val _criticalSpO2Threshold = MutableStateFlow(80)
    val criticalSpO2Threshold: StateFlow<Int> = _criticalSpO2Threshold.asStateFlow()

    fun setThreshold(threshold: Int) {
        _criticalSpO2Threshold.value = threshold.coerceIn(70, 95)
    }

    fun checkReading(reading: OximeterReading) {
        if (reading.spO2 < _criticalSpO2Threshold.value && !_abortTriggered.value) {
            triggerAbort()
        }
    }

    fun resetAbort() {
        _abortTriggered.value = false
    }

    private fun triggerAbort() {
        _abortTriggered.value = true
        audioHapticEngine.vibrateAbort()
        audioHapticEngine.announceAbort()
    }
}
