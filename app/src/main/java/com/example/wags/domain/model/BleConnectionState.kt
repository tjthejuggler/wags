package com.example.wags.domain.model

/**
 * Unified connection state for any BLE device (Polar, oximeter, or generic).
 *
 * The [Connected] state includes the [DeviceType] so consumers can check
 * capabilities without knowing which backend manager handles the device.
 */
sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    data class Scanning(val devicesFound: Int = 0) : BleConnectionState()
    data class Connecting(val deviceId: String) : BleConnectionState()
    data class Connected(
        val deviceId: String,
        val deviceName: String,
        val deviceType: DeviceType = DeviceType.fromName(deviceName)
    ) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
