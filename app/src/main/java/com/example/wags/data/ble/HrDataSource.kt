package com.example.wags.data.ble

import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.DeviceCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for live heart rate and SpO₂ across the app.
 *
 * Delegates to [UnifiedDeviceManager] which merges Polar and generic BLE
 * backends. Consumers only need this class for live sensor data.
 *
 * Also auto-records device labels to [DevicePreferencesRepository] whenever a
 * device connects, so the label history is always up-to-date for the edit
 * dropdown on past records.
 */
@Singleton
class HrDataSource @Inject constructor(
    private val deviceManager: UnifiedDeviceManager,
    private val devicePrefs: DevicePreferencesRepository
) {
    private val scope = CoroutineScope(SupervisorJob())

    init {
        // Auto-record device labels whenever a device connects
        deviceManager.connectionState.onEach { state ->
            if (state is BleConnectionState.Connected) {
                val name = state.deviceName.ifBlank { state.deviceId }
                devicePrefs.recordDeviceLabel(name)
            }
        }.launchIn(scope)
    }

    /**
     * True when at least one HR-capable device is connected.
     */
    val isAnyHrDeviceConnected: StateFlow<Boolean> = deviceManager.isConnected

    /**
     * Merged live HR in bpm. Null when no device is connected or no data yet.
     */
    val liveHr: StateFlow<Int?> = deviceManager.liveHr

    /**
     * Live SpO₂ percentage. Null when no device with SPO2 capability is
     * connected or no reading has arrived yet.
     */
    val liveSpO2: StateFlow<Int?> = deviceManager.liveSpO2

    /**
     * True when the connected device has SpO₂ capability and no Polar device
     * is also connected (i.e. the oximeter is the primary device).
     *
     * SpO₂ data is only meaningful when the oximeter is the device the user
     * intends to use for the hold. When a Polar device is connected it takes
     * priority for HR, and any SpO₂ readings from a background-connected
     * oximeter are incidental.
     */
    fun isOximeterPrimaryDevice(): Boolean {
        val state = deviceManager.connectionState.value
        return state is BleConnectionState.Connected &&
            state.deviceType.has(DeviceCapability.SPO2)
    }

    /**
     * Returns a human-readable label for the currently connected device,
     * suitable for storing alongside recorded data.
     *
     * Uses the device's own advertised name (no artificial type prefix).
     */
    fun activeHrDeviceLabel(): String? {
        return deviceManager.connectedDeviceName()
    }

    /**
     * Returns the device ID of the currently connected Polar device, or null.
     * Used to start RR/HR streams with the correct device identifier.
     */
    fun connectedPolarDeviceId(): String? {
        val state = deviceManager.connectionState.value
        if (state is BleConnectionState.Connected && state.deviceType.isPolar) {
            return state.deviceId
        }
        return null
    }

    /**
     * Returns the device ID of whichever device is connected, or null.
     */
    fun connectedDeviceId(): String? = deviceManager.connectedDeviceId()
}
