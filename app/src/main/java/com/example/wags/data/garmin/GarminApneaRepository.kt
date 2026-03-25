package com.example.wags.data.garmin

import android.util.Log
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Garmin watch Free Hold data into the existing apnea persistence layer.
 *
 * Listens to [GarminManager.freeHoldPayloads] and converts each
 * [GarminFreeHoldPayload] into [ApneaRecordEntity] + [FreeHoldTelemetryEntity]
 * rows, saving them through the existing [ApneaRepository].
 *
 * This ensures that Garmin-sourced holds appear identically to phone-sourced
 * holds in the history, stats, and analytics screens.
 */
@Singleton
class GarminApneaRepository @Inject constructor(
    private val garminManager: GarminManager,
    private val apneaRepository: ApneaRepository,
    private val devicePrefs: DevicePreferencesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "GarminApneaRepo"

        // Physiological bounds (matching FreeHoldActiveScreen.PhysiologicalBounds)
        private const val HR_MIN = 20
        private const val HR_MAX = 250
        private const val SPO2_MIN = 1
        private const val SPO2_MAX = 100
    }

    /**
     * Start listening for incoming Garmin Free Hold payloads.
     * Call once during app initialization.
     */
    fun startListening() {
        Log.w(TAG, "startListening() called — subscribing to freeHoldPayloads flow")
        scope.launch(ioDispatcher) {
            Log.w(TAG, "Coroutine started, collecting freeHoldPayloads...")
            garminManager.freeHoldPayloads.collect { payload ->
                Log.w(TAG, ">>> Received Garmin Free Hold payload: " +
                        "duration=${payload.durationMs}ms, " +
                        "lungVolume=${payload.lungVolume}, " +
                        "prepType=${payload.prepType}, " +
                        "timeOfDay=${payload.timeOfDay}, " +
                        "samples=${payload.telemetrySamples.size}, " +
                        "contractions=${payload.contractionTimesMs.size}")
                persistFreeHold(payload)
            }
        }
    }

    /**
     * Convert a Garmin payload into database entities and persist them.
     */
    private suspend fun persistFreeHold(payload: GarminFreeHoldPayload) {
        try {
            // Compute HR min/max from telemetry (only valid readings)
            val validHrValues = payload.telemetrySamples
                .mapNotNull { it.heartRateBpm }
                .filter { it in HR_MIN..HR_MAX }

            val validSpO2Values = payload.telemetrySamples
                .mapNotNull { it.spO2 }
                .filter { it in SPO2_MIN..SPO2_MAX }

            val minHr = validHrValues.minOrNull()?.toFloat() ?: 0f
            val maxHr = validHrValues.maxOrNull()?.toFloat() ?: 0f
            val lowestSpO2 = validSpO2Values.minOrNull()

            // Save the record
            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp = payload.endEpochMs,
                    durationMs = payload.durationMs,
                    lungVolume = payload.lungVolume,
                    prepType = payload.prepType,
                    timeOfDay = payload.timeOfDay,
                    posture = "LAYING",
                    audio = "SILENCE",  // Garmin holds always silent (no Spotify on watch)
                    minHrBpm = minHr,
                    maxHrBpm = maxHr,
                    lowestSpO2 = lowestSpO2,
                    tableType = null,  // Free Hold
                    firstContractionMs = payload.firstContractionMs,
                    hrDeviceId = "Garmin Watch"
                )
            )

            if (recordId <= 0) {
                Log.e(TAG, "Failed to save Garmin apnea record")
                return
            }

            // Save telemetry samples
            if (payload.telemetrySamples.isNotEmpty()) {
                val sampleIntervalMs = if (payload.telemetrySamples.size > 1) {
                    payload.durationMs / payload.telemetrySamples.size
                } else {
                    1000L
                }

                val telemetryEntities = payload.telemetrySamples.mapIndexed { index, sample ->
                    val timestampMs = payload.startEpochMs + (index * sampleIntervalMs)
                    val validHr = sample.heartRateBpm?.takeIf { it in HR_MIN..HR_MAX }
                    val validSpO2 = sample.spO2?.takeIf { it in SPO2_MIN..SPO2_MAX }

                    FreeHoldTelemetryEntity(
                        recordId = recordId,
                        timestampMs = timestampMs,
                        heartRateBpm = validHr,
                        spO2 = validSpO2
                    )
                }.filter { it.heartRateBpm != null || it.spO2 != null }

                if (telemetryEntities.isNotEmpty()) {
                    apneaRepository.saveTelemetry(telemetryEntities)
                    Log.d(TAG, "Saved ${telemetryEntities.size} telemetry samples for record $recordId")
                }
            }

            // Record the Garmin Watch label so it appears in the device edit dropdown
            devicePrefs.recordDeviceLabel("Garmin Watch")

            Log.i(TAG, "Garmin Free Hold saved: recordId=$recordId, duration=${payload.durationMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting Garmin Free Hold", e)
        }
    }
}
