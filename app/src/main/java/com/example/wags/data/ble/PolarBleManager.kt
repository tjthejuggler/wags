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
        private const val HR_STREAM_RETRY_DELAY_MS = 2_000L
        private const val HR_STREAM_MAX_RETRIES = 5
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

    /**
     * Tracks the last device ID we asked the Polar SDK to connect to.
     * The Polar SDK's `connectToDevice` is fire-and-forget — it keeps trying
     * to connect in the background even after we've moved on. We need this
     * to properly cancel stale connection attempts when switching devices.
     */
    @Volatile
    private var lastRequestedDeviceId: String? = null

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
                // HR stream is started from bleSdkFeatureReady(FEATURE_HR) instead
                // of here, because the SDK may not be ready to stream yet.
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

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                Log.d(TAG, "bleSdkFeatureReady: $identifier → $feature")
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) {
                    // The HR feature is now ready — safe to start streaming.
                    startHrStream(identifier)
                }
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
        // If already connected/connecting to a different device, disconnect first
        val currentState = _connectionState.value
        val currentId = when (currentState) {
            is BleConnectionState.Connected -> currentState.deviceId
            is BleConnectionState.Connecting -> currentState.deviceId
            else -> null
        }
        if (currentId != null && currentId != deviceId) {
            Log.d(TAG, "Disconnecting $currentId before connecting $deviceId")
            try { polarApi.disconnectFromDevice(currentId) } catch (_: Exception) {}
            stopAllStreams(currentId)
            _liveHr.value = null
        }

        // Also cancel any lingering SDK connection attempt for a different device
        val lastId = lastRequestedDeviceId
        if (lastId != null && lastId != deviceId && lastId != currentId) {
            Log.d(TAG, "Cancelling stale SDK connection to $lastId")
            try { polarApi.disconnectFromDevice(lastId) } catch (_: Exception) {}
        }

        lastRequestedDeviceId = deviceId
        _connectionState.value = BleConnectionState.Connecting(deviceId)
        try {
            polarApi.connectToDevice(deviceId)
        } catch (e: PolarInvalidArgument) {
            _connectionState.value = BleConnectionState.Error(e.message ?: "Connection failed")
        } catch (e: Exception) {
            Log.e(TAG, "connectDevice($deviceId) threw: ${e.message}")
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
        } catch (e: PolarInvalidArgument) {
            // Device ID was invalid — force state to Disconnected
            Log.w(TAG, "disconnectDevice($deviceId) PolarInvalidArgument: ${e.message}")
            _connectionState.value = BleConnectionState.Disconnected
            _liveHr.value = null
        } catch (e: Exception) {
            // Device already gone (turned off, out of range, BLE stack error)
            Log.w(TAG, "disconnectDevice($deviceId) threw: ${e.message}")
            _connectionState.value = BleConnectionState.Disconnected
            stopAllStreams(deviceId)
            _liveHr.value = null
            onDisconnected?.invoke()
        }
    }

    /**
     * Disconnect whichever Polar device is currently connected, and cancel
     * any lingering SDK connection attempt.
     */
    fun disconnect() {
        val state = _connectionState.value
        when (state) {
            is BleConnectionState.Connected -> disconnectDevice(state.deviceId)
            is BleConnectionState.Connecting -> disconnectDevice(state.deviceId)
            is BleConnectionState.Error -> {
                // Stuck in Error state — force to Disconnected so UI can recover
                Log.d(TAG, "disconnect() from Error state — forcing Disconnected")
                _connectionState.value = BleConnectionState.Disconnected
                _liveHr.value = null
            }
            else -> { /* nothing to disconnect */ }
        }

        // Cancel any lingering SDK connection attempt that may still be running
        // in the background. The Polar SDK's connectToDevice is fire-and-forget
        // and keeps retrying even after our state has moved to Disconnected.
        val lastId = lastRequestedDeviceId
        if (lastId != null) {
            val alreadyHandled = when (state) {
                is BleConnectionState.Connected -> state.deviceId == lastId
                is BleConnectionState.Connecting -> state.deviceId == lastId
                else -> false
            }
            if (!alreadyHandled) {
                Log.d(TAG, "disconnect() — also cancelling stale SDK connect to $lastId")
                try { polarApi.disconnectFromDevice(lastId) } catch (_: Exception) {}
            }
            lastRequestedDeviceId = null
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
     * Started when the FEATURE_HR SDK feature is ready. Streams live HR bpm
     * into [liveHr] and also writes RR intervals to [rrBuffer] for HRV
     * processing.
     *
     * Includes retry logic: if the stream completes or errors while the device
     * is still connected, it retries up to [HR_STREAM_MAX_RETRIES] times with
     * a delay between attempts. This handles the case where the Polar SDK
     * reports the feature as ready but the stream fails to start on the first
     * attempt.
     */
    private fun startHrStream(deviceId: String) {
        val key = "$deviceId-hr"
        Log.d(TAG, "startHrStream($deviceId) — cancelling existing job? ${streamJobs[key] != null}")
        streamJobs[key]?.cancel()
        streamJobs[key] = scope.launch {
            var retries = 0
            while (isActive && retries <= HR_STREAM_MAX_RETRIES) {
                // Only stream while this device is still connected
                val state = _connectionState.value
                if (state !is BleConnectionState.Connected || state.deviceId != deviceId) {
                    Log.d(TAG, "startHrStream($deviceId) — device no longer connected, stopping")
                    break
                }

                Log.d(TAG, "startHrStream($deviceId) — attempt ${retries + 1}/${HR_STREAM_MAX_RETRIES + 1}")
                try {
                    polarApi.startHrStreaming(deviceId).toKotlinFlow().collect { hrData ->
                        retries = 0 // reset on successful data
                        hrData.samples.forEach { sample ->
                            Log.d(TAG, "HR sample: hr=${sample.hr}, rrsMs=${sample.rrsMs}, rrBuffer.totalWrites=${rrBuffer.totalWrites()}")
                            _liveHr.value = sample.hr
                            sample.rrsMs.forEach { rrRaw ->
                                rrBuffer.write(rrRaw * RR_CONVERSION_FACTOR)
                            }
                        }
                    }
                    Log.d(TAG, "startHrStream($deviceId) — flow completed normally")
                } catch (e: CancellationException) {
                    throw e // don't retry on cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "startHrStream($deviceId) — exception: ${e.javaClass.simpleName}: ${e.message}", e)
                }

                // Stream ended — check if device is still connected before retrying
                val postState = _connectionState.value
                if (postState !is BleConnectionState.Connected || postState.deviceId != deviceId) {
                    Log.d(TAG, "startHrStream($deviceId) — device disconnected after stream end, not retrying")
                    break
                }

                retries++
                if (retries <= HR_STREAM_MAX_RETRIES) {
                    Log.d(TAG, "startHrStream($deviceId) — retrying in ${HR_STREAM_RETRY_DELAY_MS}ms (attempt $retries)")
                    delay(HR_STREAM_RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "startHrStream($deviceId) — max retries reached, giving up")
                }
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
