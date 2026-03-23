package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.DeviceType
import com.example.wags.domain.model.ScannedDevice
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
 * Manages Polar BLE device connections via the Polar SDK.
 *
 * ## Single-device model
 *
 * Only one Polar device is connected at a time. The device type (H10 vs
 * Verity Sense) is determined from the advertised name after connection and
 * exposed via [BleConnectionState.Connected.deviceType].
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
    val accBuffer = CircularBuffer<Triple<Int, Int, Int>>(ACC_BUFFER_SIZE)

    /** Single connection state for the one connected Polar device. */
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    // Legacy accessors — delegate to the single connectionState
    val h10State: StateFlow<BleConnectionState> get() = connectionState
    val verityState: StateFlow<BleConnectionState>
        get() = connectionState // same flow — consumers will check deviceType

    /** Live heart rate in bpm from whichever device is streaming HR. Null when no device connected. */
    private val _liveHr = MutableStateFlow<Int?>(null)
    val liveHr: StateFlow<Int?> = _liveHr.asStateFlow()

    private val streamJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _scanResults = MutableStateFlow<List<PolarDeviceInfo>>(emptyList())
    val scanResults: StateFlow<List<PolarDeviceInfo>> = _scanResults.asStateFlow()

    /** Unified scan results for the UnifiedDeviceManager. */
    val unifiedScanResults: StateFlow<List<ScannedDevice>> = _scanResults.map { polarDevices ->
        polarDevices.map { device ->
            ScannedDevice(
                identifier = device.deviceId,
                name = device.name.ifBlank { "Polar ${device.deviceId}" },
                rssi = device.rssi,
                isPolar = true
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

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
                val deviceType = DeviceType.fromName(deviceName)
                Log.d(TAG, "Device connected: ${polarDeviceInfo.deviceId} name='$deviceName' → type=$deviceType")

                _connectionState.value = BleConnectionState.Connected(
                    polarDeviceInfo.deviceId,
                    deviceName,
                    deviceType
                )
                startHrStream(polarDeviceInfo.deviceId)
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                _connectionState.value = BleConnectionState.Connecting(polarDeviceInfo.deviceId)
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                _connectionState.value = BleConnectionState.Disconnected
                stopAllStreams(polarDeviceInfo.deviceId)
                _liveHr.value = null
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
     * Connect to a Polar device. The device type (H10 vs Verity Sense) is
     * determined automatically from the device name once connected.
     */
    fun connectDevice(deviceId: String) {
        _connectionState.value = BleConnectionState.Connecting(deviceId)
        try {
            polarApi.connectToDevice(deviceId)
        } catch (e: PolarInvalidArgument) {
            _connectionState.value = BleConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    /** @deprecated Use [connectDevice] without isH10 parameter. */
    fun connectDevice(deviceId: String, @Suppress("UNUSED_PARAMETER") isH10: Boolean) {
        connectDevice(deviceId)
    }

    fun disconnectDevice(deviceId: String) {
        try {
            polarApi.disconnectFromDevice(deviceId)
        } catch (e: PolarInvalidArgument) { /* ignore */ }
    }

    /** Disconnect whichever Polar device is currently connected. */
    fun disconnect() {
        val state = _connectionState.value
        when (state) {
            is BleConnectionState.Connected -> disconnectDevice(state.deviceId)
            is BleConnectionState.Connecting -> disconnectDevice(state.deviceId)
            else -> { /* nothing to disconnect */ }
        }
    }

    /**
     * Returns true if the connected device is a Polar H10 (by name).
     * Used by features that require H10-specific capabilities like ECG and ACC.
     */
    fun isH10Connected(): Boolean {
        val state = _connectionState.value
        return state is BleConnectionState.Connected && state.deviceType == DeviceType.POLAR_H10
    }

    /**
     * Returns the device ID of the connected H10 (by name), or null.
     */
    fun connectedH10DeviceId(): String? {
        val state = _connectionState.value
        if (state is BleConnectionState.Connected && state.deviceType == DeviceType.POLAR_H10) {
            return state.deviceId
        }
        return null
    }

    /**
     * Returns the device ID of whichever Polar device is connected, or null.
     */
    fun connectedDeviceId(): String? {
        val state = _connectionState.value
        return if (state is BleConnectionState.Connected) state.deviceId else null
    }

    /**
     * Auto-started on device connect. Streams live HR bpm into [liveHr] and
     * also writes RR intervals to [rrBuffer] for HRV processing.
     */
    private fun startHrStream(deviceId: String) {
        val key = "$deviceId-hr"
        Log.d(TAG, "startHrStream($deviceId) — cancelling existing job? ${streamJobs[key] != null}")
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            Log.d(TAG, "startHrStream($deviceId) — coroutine launched, calling polarApi.startHrStreaming")
            try {
                polarApi.startHrStreaming(deviceId).toKotlinFlow().collect { hrData ->
                    hrData.samples.forEach { sample ->
                        Log.d(TAG, "HR sample: hr=${sample.hr}, rrsMs=${sample.rrsMs}, rrBuffer.totalWrites=${rrBuffer.totalWrites()}")
                        _liveHr.value = sample.hr
                        sample.rrsMs.forEach { rrRaw ->
                            rrBuffer.write(rrRaw * RR_CONVERSION_FACTOR)
                        }
                    }
                }
                Log.d(TAG, "startHrStream($deviceId) — flow completed normally")
            } catch (e: Exception) {
                Log.e(TAG, "startHrStream($deviceId) — exception: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Ensure the HR+RR stream is running for the given device.
     *
     * Delegates to [startHrStream] which writes to both [liveHr] and [rrBuffer].
     * The Polar SDK only supports one active HR stream per device, so we must
     * NOT open a second `startHrStreaming` subscription — that would silently
     * kill the first one and stop [liveHr] updates.
     */
    fun startRrStream(deviceId: String) {
        startHrStream(deviceId)
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
