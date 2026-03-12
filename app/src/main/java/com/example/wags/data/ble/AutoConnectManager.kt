package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AutoConnect"

/**
 * Orchestrates automatic BLE connection on app start.
 *
 * Strategy:
 * 1. Determine time window (morning = 3 am–11 am, day = 11 am–3 am).
 * 2. Try to connect the *primary* device for that window.
 * 3. If the primary device is not configured or fails to connect within
 *    [CONNECT_TIMEOUT_MS], fall back to the *secondary* device.
 * 4. If neither time-based default is configured, fall back to the last
 *    individually saved device IDs (H10 → Verity → Oximeter).
 *
 * Call [attemptAutoConnect] once after the app has Bluetooth permissions.
 * It is safe to call multiple times; it will no-op if already connected.
 */
@Singleton
class AutoConnectManager @Inject constructor(
    private val prefs: DevicePreferencesRepository,
    private val polarBleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoConnectJob: Job? = null

    /**
     * Kick off the auto-connect sequence. Safe to call from the main thread.
     * Does nothing if a Polar or Oximeter device is already connected.
     */
    fun attemptAutoConnect() {
        // Don't start if already connected
        if (isAnyDeviceConnected()) {
            Log.d(TAG, "Already connected — skipping auto-connect")
            return
        }

        autoConnectJob?.cancel()
        autoConnectJob = scope.launch {
            Log.d(TAG, "Starting auto-connect sequence")
            runAutoConnect()
        }
    }

    private fun isAnyDeviceConnected(): Boolean {
        return polarBleManager.h10State.value is BleConnectionState.Connected ||
            polarBleManager.verityState.value is BleConnectionState.Connected ||
            oximeterBleManager.connectionState.value is OximeterConnectionState.Connected
    }

    private suspend fun runAutoConnect() {
        val isMorning = DevicePreferencesRepository.isMorningWindow()
        Log.d(TAG, "Time window: ${if (isMorning) "MORNING (3am-11am)" else "DAY (11am-3am)"}")

        // Build ordered list: [primary, secondary, legacy-fallbacks]
        val candidates = buildCandidateList(isMorning)

        if (candidates.isEmpty()) {
            Log.d(TAG, "No saved devices to auto-connect to")
            return
        }

        for (candidate in candidates) {
            if (isAnyDeviceConnected()) {
                Log.d(TAG, "Connected — stopping auto-connect loop")
                break
            }
            Log.d(TAG, "Trying candidate: ${candidate.label} (${candidate.deviceId})")
            val connected = tryConnect(candidate)
            if (connected) {
                Log.d(TAG, "Auto-connect succeeded: ${candidate.label}")
                break
            } else {
                Log.d(TAG, "Auto-connect failed for ${candidate.label} — trying next")
            }
        }
    }

    /**
     * Builds an ordered list of devices to try.
     * Primary slot for the current time window comes first,
     * then the other window's slot, then legacy saved IDs as last resort.
     */
    private fun buildCandidateList(isMorning: Boolean): List<DeviceCandidate> {
        val list = mutableListOf<DeviceCandidate>()

        val primaryId   = if (isMorning) prefs.morningDeviceId   else prefs.dayDeviceId
        val primaryType = if (isMorning) prefs.morningDeviceType else prefs.dayDeviceType
        val primaryName = if (isMorning) prefs.morningDeviceName else prefs.dayDeviceName

        val secondaryId   = if (isMorning) prefs.dayDeviceId     else prefs.morningDeviceId
        val secondaryType = if (isMorning) prefs.dayDeviceType   else prefs.morningDeviceType
        val secondaryName = if (isMorning) prefs.dayDeviceName   else prefs.morningDeviceName

        if (primaryId.isNotBlank() && primaryType.isNotBlank()) {
            list += DeviceCandidate(
                deviceId = primaryId,
                deviceType = primaryType,
                label = "${if (isMorning) "Morning" else "Day"} default ($primaryName)"
            )
        }

        if (secondaryId.isNotBlank() && secondaryType.isNotBlank()) {
            list += DeviceCandidate(
                deviceId = secondaryId,
                deviceType = secondaryType,
                label = "${if (isMorning) "Day" else "Morning"} fallback ($secondaryName)"
            )
        }

        // Legacy fallbacks: last individually saved devices (avoid duplicates)
        val alreadyQueued = list.map { it.deviceId }.toSet()

        if (prefs.savedH10Id.isNotBlank() && prefs.savedH10Id !in alreadyQueued) {
            list += DeviceCandidate(prefs.savedH10Id, "h10", "Last H10 (${prefs.savedH10Id})")
        }
        if (prefs.savedVerityId.isNotBlank() && prefs.savedVerityId !in alreadyQueued) {
            list += DeviceCandidate(prefs.savedVerityId, "verity", "Last Verity (${prefs.savedVerityId})")
        }
        if (prefs.savedOximeterAddress.isNotBlank() && prefs.savedOximeterAddress !in alreadyQueued) {
            list += DeviceCandidate(prefs.savedOximeterAddress, "oximeter", "Last Oximeter (${prefs.savedOximeterAddress})")
        }

        return list
    }

    /**
     * Attempts to connect a single candidate and waits up to [CONNECT_TIMEOUT_MS].
     * Returns true if the device reached Connected state within the timeout.
     */
    private suspend fun tryConnect(candidate: DeviceCandidate): Boolean {
        return when (candidate.deviceType) {
            "h10" -> tryConnectPolar(candidate.deviceId, isH10 = true)
            "verity" -> tryConnectPolar(candidate.deviceId, isH10 = false)
            "oximeter" -> tryConnectOximeter(candidate.deviceId)
            else -> false
        }
    }

    private suspend fun tryConnectPolar(deviceId: String, isH10: Boolean): Boolean {
        polarBleManager.connectDevice(deviceId, isH10)
        val stateFlow = if (isH10) polarBleManager.h10State else polarBleManager.verityState
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            stateFlow.first { it is BleConnectionState.Connected || it is BleConnectionState.Error }
            stateFlow.value is BleConnectionState.Connected
        } ?: false
    }

    private suspend fun tryConnectOximeter(address: String): Boolean {
        oximeterBleManager.connect(address)
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            oximeterBleManager.connectionState.first {
                it is OximeterConnectionState.Connected || it is OximeterConnectionState.Error
            }
            oximeterBleManager.connectionState.value is OximeterConnectionState.Connected
        } ?: false
    }

    fun cancel() {
        autoConnectJob?.cancel()
    }

    companion object {
        /** How long to wait for a single device to connect before trying the next. */
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }
}

private data class DeviceCandidate(
    val deviceId: String,
    val deviceType: String,   // "h10" | "verity" | "oximeter"
    val label: String
)
