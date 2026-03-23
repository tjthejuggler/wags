package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PolarBleManager"

/**
 * Manages Polar BLE device connections.
 *
 * ## Name-based device identification
 *
 * Devices are identified by their advertised name **after** connection:
 *   - Name contains "H10" → routed to [h10State] (chest strap: HR, RR, ECG, ACC)
 *   - Name contains "Sense" → routed to [verityState] (optical: HR, PPI)
 *   - Anything else → routed to [h10State] as fallback
 *
 * The caller never needs to specify which type a device is — just call
 * [connectDevice] with the device ID and the manager figures it out.
 */
@Singleton
class PolarBleManager @Inject constructor(
    private val polarApi: PolarBleApi
) {
    /** Invoked whenever a Polar device disconnects. Set by AutoConnectManager. */
    var onDisconnected: (() -> Unit)? = null

    companion object {
        const val RR_BUFFER_SIZE = 1024
        const val ECG_BUFFER_SIZE = 8192
        const val PPI_BUFFER_SIZE = 1024
        const val ACC_BUFFER_SIZE = 4096   // 200 Hz × ~20s
        const val PPI_ERROR_THRESHOLD_MS = 10
        private const val RR_CONVERSION_FACTOR = 1000.0 / 1024.0
    }

    val rrBuffer = CircularBuffer<Double>(RR_BUFFER_SIZE)
    val ecgBuffer = CircularBuffer<Int>(ECG_BUFFER_SIZE)
    val ppiBuffer = CircularBuffer<Int>(PPI_BUFFER_SIZE)
    val accBuffer = CircularBuffer<Triple<Int, Int, Int>>(ACC_BUFFER_SIZE)  // (x, y, z)

    private val _h10State = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val h10State: StateFlow<BleConnectionState> = _h10State.asStateFlow()

    private val _verityState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val verityState: StateFlow<BleConnectionState> = _verityState.asStateFlow()

    /** Live heart rate in bpm from whichever device is streaming HR. Null when no device connected. */
    private val _liveHr = MutableStateFlow<Int?>(null)
    val liveHr: StateFlow<Int?> = _liveHr.asStateFlow()

    private val streamJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _scanResults = MutableStateFlow<List<PolarDeviceInfo>>(emptyList())
    val scanResults: StateFlow<List<PolarDeviceInfo>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Slot registry: maps a Polar deviceId → which slot ("h10" or "verity")
     * it was assigned to. Populated automatically based on device name when
     * the device connects, or set to a temporary value during the Connecting phase.
     */
    private val deviceSlotRegistry = mutableMapOf<String, String>()   // deviceId → "h10"|"verity"

    fun startScan() {
        scanJob?.cancel()
        _scanResults.value = emptyList()
        _isScanning.value = true
        scanJob = scope.launch {
            try {
                polarApi.searchForDevice().toKotlinFlow().collect { device ->
                    val current = _scanResults.value.toMutableList()
                    if (current.none { it.deviceId == device.deviceId }) {
                        current.add(device)
                        _scanResults.value = current
                    }
                }
            } catch (e: Exception) {
                // scan ended or cancelled
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    init {
        polarApi.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                val deviceName = polarDeviceInfo.name ?: "Unknown"
                // Auto-detect device type from name and update the slot registry
                val slot = detectSlotFromName(deviceName)
                deviceSlotRegistry[polarDeviceInfo.deviceId] = slot
                Log.d(TAG, "Device connected: ${polarDeviceInfo.deviceId} name='$deviceName' → slot=$slot")

                val state = BleConnectionState.Connected(
                    polarDeviceInfo.deviceId,
                    deviceName
                )
                updateState(polarDeviceInfo.deviceId, state)
                startHrStream(polarDeviceInfo.deviceId)
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                // During connecting, we may not have the name yet.
                // If the device is already in the registry (from a previous connection),
                // keep that slot. Otherwise, use a temporary slot that will be corrected
                // in deviceConnected when we learn the actual name.
                if (polarDeviceInfo.deviceId !in deviceSlotRegistry) {
                    // Temporary: guess from name if available, otherwise default to "h10"
                    val name = polarDeviceInfo.name ?: ""
                    deviceSlotRegistry[polarDeviceInfo.deviceId] = detectSlotFromName(name)
                }
                updateState(
                    polarDeviceInfo.deviceId,
                    BleConnectionState.Connecting(polarDeviceInfo.deviceId)
                )
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                updateState(polarDeviceInfo.deviceId, BleConnectionState.Disconnected)
                stopAllStreams(polarDeviceInfo.deviceId)
                // Clear HR if no device remains connected
                val anyConnected = _h10State.value is BleConnectionState.Connected ||
                    _verityState.value is BleConnectionState.Connected
                if (!anyConnected) _liveHr.value = null
                // Notify AutoConnectManager so it can re-arm immediately
                onDisconnected?.invoke()
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                // DIS information received (UUID-based)
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
                // DIS information received (DisInfo-based)
            }
        })
    }

    /**
     * Determines the slot ("h10" or "verity") from the device's advertised name.
     *
     * - Contains "H10" → "h10" (chest strap with ECG + ACC)
     * - Contains "Sense" → "verity" (optical HR with PPI)
     * - Anything else → "h10" (safe default — H10 features are a superset)
     */
    private fun detectSlotFromName(name: String): String {
        val upper = name.uppercase()
        return when {
            upper.contains("H10") -> "h10"
            upper.contains("SENSE") -> "verity"
            else -> "h10"  // default: treat unknown Polar devices as H10
        }
    }

    /**
     * Routes a state update to the correct StateFlow using [deviceSlotRegistry].
     *
     * Falls back to the old heuristic (check current state value) only if the
     * device was never registered — e.g. a spontaneous SDK callback for a device
     * we didn't initiate a connect for.
     */
    private fun updateState(deviceId: String, state: BleConnectionState) {
        val slot = deviceSlotRegistry[deviceId]
        when {
            slot == "h10"   -> _h10State.value = state
            slot == "verity" -> _verityState.value = state
            else -> {
                // Fallback: infer from current state (legacy path, should rarely hit)
                val h10Matches = _h10State.value.let {
                    (it is BleConnectionState.Connecting && it.deviceId == deviceId) ||
                    (it is BleConnectionState.Connected  && it.deviceId == deviceId)
                }
                if (h10Matches) _h10State.value = state else _verityState.value = state
            }
        }
    }

    /**
     * Connect to a Polar device. The device type (H10 vs Verity Sense) is
     * determined automatically from the device name once connected.
     *
     * During the Connecting phase, if the device was previously connected
     * its known slot is reused; otherwise it defaults to "h10" until the
     * actual name is received.
     */
    fun connectDevice(deviceId: String) {
        // If we already know this device's slot from a previous connection, reuse it.
        // Otherwise, set a temporary slot that will be corrected in deviceConnected.
        if (deviceId !in deviceSlotRegistry) {
            deviceSlotRegistry[deviceId] = "h10"  // temporary default
        }
        val slot = deviceSlotRegistry[deviceId]!!
        if (slot == "h10") _h10State.value = BleConnectionState.Connecting(deviceId)
        else _verityState.value = BleConnectionState.Connecting(deviceId)
        try {
            polarApi.connectToDevice(deviceId)
        } catch (e: PolarInvalidArgument) {
            val error = BleConnectionState.Error(e.message ?: "Connection failed")
            if (slot == "h10") _h10State.value = error else _verityState.value = error
        }
    }

    /**
     * @deprecated Use [connectDevice] without isH10 parameter.
     * Kept temporarily for backward compatibility during migration.
     */
    fun connectDevice(deviceId: String, @Suppress("UNUSED_PARAMETER") isH10: Boolean) {
        connectDevice(deviceId)
    }

    fun disconnectDevice(deviceId: String) {
        try {
            polarApi.disconnectFromDevice(deviceId)
        } catch (e: PolarInvalidArgument) { /* ignore */ }
    }

    /**
     * Returns true if the device currently in the H10 slot is actually an H10
     * (i.e. its name contains "H10"). Used by features that require H10-specific
     * capabilities like ECG and ACC streaming.
     */
    fun isH10Connected(): Boolean {
        val state = _h10State.value
        if (state !is BleConnectionState.Connected) return false
        return state.deviceName.uppercase().contains("H10")
    }

    /**
     * Returns the device ID of the connected H10 (by name), or null.
     */
    fun connectedH10DeviceId(): String? {
        val state = _h10State.value
        if (state is BleConnectionState.Connected && state.deviceName.uppercase().contains("H10")) {
            return state.deviceId
        }
        return null
    }

    /**
     * Auto-started on device connect. Streams live HR bpm into [liveHr] and
     * also writes RR intervals to [rrBuffer] for HRV processing.
     */
    private fun startHrStream(deviceId: String) {
        val key = "$deviceId-hr"
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            try {
                polarApi.startHrStreaming(deviceId).toKotlinFlow().collect { hrData ->
                    hrData.samples.forEach { sample ->
                        _liveHr.value = sample.hr
                        sample.rrsMs.forEach { rrRaw ->
                            rrBuffer.write(rrRaw * RR_CONVERSION_FACTOR)
                        }
                    }
                }
            } catch (e: Exception) {
                // stream ended or device disconnected
            }
        }
    }

    /** Start H10 RR stream. Converts raw 1/1024s units to milliseconds. */
    fun startRrStream(deviceId: String) {
        val key = "$deviceId-rr"
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            try {
                polarApi.startHrStreaming(deviceId).toKotlinFlow().collect { hrData ->
                    hrData.samples.forEach { sample ->
                        sample.rrsMs.forEach { rrRaw ->
                            val rrMs = rrRaw * RR_CONVERSION_FACTOR
                            rrBuffer.write(rrMs)
                        }
                    }
                }
            } catch (e: Exception) {
                // stream ended or device not connected
            }
        }
    }

    /** Start H10 ECG stream. Writes batches to ECG circular buffer. */
    fun startEcgStream(deviceId: String) {
        val key = "$deviceId-ecg"
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            val settings = PolarSensorSetting(
                mapOf(
                    PolarSensorSetting.SettingType.SAMPLE_RATE to 130,
                    PolarSensorSetting.SettingType.RESOLUTION to 14
                )
            )
            polarApi.startEcgStreaming(deviceId, settings).toKotlinFlow().collect { ecgData ->
                ecgBuffer.writeBatch(ecgData.samples.map { it.voltage })
            }
        }
    }

    /** Start H10 ACC stream at 200 Hz for respiration detection. */
    fun startAccStream(deviceId: String) {
        val key = "$deviceId-acc"
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            val settings = PolarSensorSetting(
                mapOf(
                    PolarSensorSetting.SettingType.SAMPLE_RATE to 200,
                    PolarSensorSetting.SettingType.RESOLUTION to 16,
                    PolarSensorSetting.SettingType.RANGE to 2
                )
            )
            polarApi.startAccStreaming(deviceId, settings).toKotlinFlow().collect { accData ->
                accBuffer.writeBatch(accData.samples.map { Triple(it.x, it.y, it.z) })
            }
        }
    }

    /** Start Verity Sense PPI stream. Enables SDK mode first, validates samples. */
    fun startPpiStream(deviceId: String) {
        val key = "$deviceId-ppi"
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            try {
                polarApi.enableSDKMode(deviceId).blockingAwait()
            } catch (e: Exception) { /* SDK mode may already be active */ }
            polarApi.startPpiStreaming(deviceId).toKotlinFlow().collect { ppiData ->
                ppiData.samples.forEach { sample ->
                    if (sample.skinContactSupported && !sample.skinContactStatus) return@forEach
                    if (sample.errorEstimate > PPI_ERROR_THRESHOLD_MS) return@forEach
                    ppiBuffer.write(sample.ppi)
                }
            }
        }
    }

    fun stopStream(deviceId: String, streamType: String) {
        streamJobs["$deviceId-$streamType"]?.cancel()
        streamJobs.remove("$deviceId-$streamType")
    }

    fun stopAllStreams(deviceId: String) {
        streamJobs.keys.filter { it.startsWith(deviceId) }.forEach { key ->
            streamJobs[key]?.cancel()
            streamJobs.remove(key)
        }
    }

    fun cleanup() {
        scope.cancel()
        polarApi.cleanup()
    }
}
