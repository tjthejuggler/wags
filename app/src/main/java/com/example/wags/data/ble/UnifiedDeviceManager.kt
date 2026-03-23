package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.DeviceCapability
import com.example.wags.domain.model.DeviceType
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UnifiedDevice"

/**
 * Unified facade over [PolarBleManager] and [GenericBleManager].
 *
 * Consumers (ViewModels, HrDataSource, etc.) interact with this single manager
 * instead of knowing about two separate backends. The manager routes connections
 * to the correct backend based on whether the device is a Polar device or not.
 *
 * ## Key behaviours
 *
 * - **Single connection state** — merges both backends into one [connectionState]
 * - **Unified scan results** — merges Polar SDK scan + generic BLE scan
 * - **Device type detection** — from name after connection, exposed via
 *   [BleConnectionState.Connected.deviceType]
 * - **Capability queries** — check if the connected device supports HR, RR, ECG, etc.
 */
@Singleton
class UnifiedDeviceManager @Inject constructor(
    val polarBleManager: PolarBleManager,
    val genericBleManager: GenericBleManager,
    private val devicePrefs: DevicePreferencesRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Connection state ──────────────────────────────────────────────────────

    /**
     * Unified connection state. Connected if either backend is connected.
     * Priority: Polar > Generic (if somehow both are connected, Polar wins).
     */
    val connectionState: StateFlow<BleConnectionState> = combine(
        polarBleManager.connectionState,
        genericBleManager.connectionState
    ) { polar, generic ->
        when {
            polar is BleConnectionState.Connected -> polar
            generic is BleConnectionState.Connected -> generic
            polar is BleConnectionState.Connecting -> polar
            generic is BleConnectionState.Connecting -> generic
            polar is BleConnectionState.Error -> polar
            generic is BleConnectionState.Error -> generic
            else -> BleConnectionState.Disconnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, BleConnectionState.Disconnected)

    /**
     * True when any device is connected.
     */
    val isConnected: StateFlow<Boolean> = connectionState.map { state ->
        state is BleConnectionState.Connected
    }.stateIn(scope, SharingStarted.Eagerly, false)

    // ── Live data ─────────────────────────────────────────────────────────────

    /** Merged live HR in bpm. Polar HR takes priority; falls back to generic. */
    val liveHr: StateFlow<Int?> = combine(
        polarBleManager.liveHr,
        genericBleManager.liveHr
    ) { polarHr, genericHr ->
        val merged = polarHr ?: genericHr
        Log.d(TAG, "liveHr combine: polar=$polarHr, generic=$genericHr → merged=$merged")
        merged
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Live SpO₂ — only available from devices with SPO2 capability. */
    val liveSpO2: StateFlow<Int?> = genericBleManager.liveSpO2
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Raw oximeter readings stream (for recording during holds). */
    val oximeterReadings: SharedFlow<OximeterReading> = genericBleManager.readings

    // ── Scan ──────────────────────────────────────────────────────────────────

    /** Unified scan results from both backends. */
    val scanResults: StateFlow<List<ScannedDevice>> = combine(
        polarBleManager.unifiedScanResults,
        genericBleManager.unifiedScanResults
    ) { polar, generic ->
        polar + generic
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True while either backend is scanning. */
    val isScanning: StateFlow<Boolean> = combine(
        polarBleManager.isScanning,
        genericBleManager.connectionState.map { it is BleConnectionState.Scanning }
    ) { polarScanning, genericScanning ->
        polarScanning || genericScanning
    }.stateIn(scope, SharingStarted.Eagerly, false)

    fun startScan() {
        polarBleManager.startScan()
        genericBleManager.startScan()
    }

    fun stopScan() {
        polarBleManager.stopScan()
        genericBleManager.stopScan()
    }

    // ── Connect / Disconnect ─────────────────────────────────────────────────

    /**
     * Connect to a device. Routes to the correct backend based on [isPolar].
     *
     * @param identifier Polar device ID or BLE MAC address.
     * @param name       Device name (for saving to history).
     * @param isPolar    True if this device should use the Polar SDK.
     */
    fun connect(identifier: String, name: String = "", isPolar: Boolean) {
        Log.d(TAG, "connect($identifier, name='$name', isPolar=$isPolar)")
        // Save to unified history
        devicePrefs.addDevice(identifier, name, isPolar)

        if (isPolar) {
            polarBleManager.connectDevice(identifier)
        } else {
            genericBleManager.connect(identifier)
        }
    }

    /**
     * Connect to a scanned device.
     */
    fun connect(device: ScannedDevice) {
        connect(device.identifier, device.name, device.isPolar)
    }

    /**
     * Disconnect whichever device is currently connected.
     */
    fun disconnect() {
        val state = connectionState.value
        if (state !is BleConnectionState.Connected) return

        if (state.deviceType.isPolar) {
            polarBleManager.disconnect()
        } else {
            genericBleManager.disconnect()
        }
    }

    /**
     * Disconnect a specific device by identifier.
     */
    fun disconnect(identifier: String) {
        // Check if it's the Polar device
        val polarState = polarBleManager.connectionState.value
        if (polarState is BleConnectionState.Connected && polarState.deviceId == identifier) {
            polarBleManager.disconnectDevice(identifier)
            return
        }
        if (polarState is BleConnectionState.Connecting && polarState.deviceId == identifier) {
            polarBleManager.disconnectDevice(identifier)
            return
        }
        // Otherwise try generic
        val genericState = genericBleManager.connectionState.value
        if (genericState is BleConnectionState.Connected && genericState.deviceId == identifier) {
            genericBleManager.disconnect()
        }
    }

    // ── Capability queries ────────────────────────────────────────────────────

    /** Returns the connected device's type, or null if not connected. */
    fun connectedDeviceType(): DeviceType? {
        val state = connectionState.value
        return if (state is BleConnectionState.Connected) state.deviceType else null
    }

    /** Returns the connected device's ID, or null if not connected. */
    fun connectedDeviceId(): String? {
        val state = connectionState.value
        return if (state is BleConnectionState.Connected) state.deviceId else null
    }

    /** Returns the connected device's name, or null if not connected. */
    fun connectedDeviceName(): String? {
        val state = connectionState.value
        return if (state is BleConnectionState.Connected) state.deviceName else null
    }

    /** True if the connected device has the given capability. */
    fun hasCapability(capability: DeviceCapability): Boolean {
        return connectedDeviceType()?.has(capability) == true
    }

    /** True if the connected device is a Polar H10. */
    fun isH10Connected(): Boolean = connectedDeviceType() == DeviceType.POLAR_H10

    /** True if the connected device can provide SpO₂ data. */
    fun hasSpO2(): Boolean = hasCapability(DeviceCapability.SPO2)

    /** True if the connected device can provide RR intervals. */
    fun hasRR(): Boolean = hasCapability(DeviceCapability.RR)

    // ── Stream delegation (Polar-specific) ────────────────────────────────────

    /** Access to Polar RR buffer for HRV analysis. */
    val rrBuffer get() = polarBleManager.rrBuffer
    val ecgBuffer get() = polarBleManager.ecgBuffer
    val ppiBuffer get() = polarBleManager.ppiBuffer
    val accBuffer get() = polarBleManager.accBuffer

    fun startRrStream(deviceId: String) = polarBleManager.startRrStream(deviceId)
    fun startEcgStream(deviceId: String) = polarBleManager.startEcgStream(deviceId)
    fun startAccStream(deviceId: String) = polarBleManager.startAccStream(deviceId)
    fun startPpiStream(deviceId: String) = polarBleManager.startPpiStream(deviceId)
    fun stopStream(deviceId: String, streamType: String) = polarBleManager.stopStream(deviceId, streamType)
    fun stopAllStreams(deviceId: String) = polarBleManager.stopAllStreams(deviceId)

    fun cleanup() {
        polarBleManager.cleanup()
        genericBleManager.release()
    }
}
