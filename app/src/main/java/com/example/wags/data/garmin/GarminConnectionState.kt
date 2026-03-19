package com.example.wags.data.garmin

/**
 * Represents the current state of the Garmin Connect IQ SDK and device connection.
 */
sealed class GarminConnectionState {
    /** SDK not yet initialized. */
    data object Uninitialized : GarminConnectionState()

    /** SDK is initializing (waiting for onSdkReady callback). */
    data object Initializing : GarminConnectionState()

    /** SDK is ready but no device is connected/paired. */
    data object SdkReady : GarminConnectionState()

    /**
     * SDK is ready, device found but waiting for it to connect.
     * The watch must be in range and paired via Garmin Connect Mobile.
     */
    data class DeviceFound(
        val deviceName: String,
        val deviceStatus: String
    ) : GarminConnectionState()

    /**
     * Device is connected but the WAGS CIQ app was not found on it.
     */
    data class WagsAppNotFound(
        val deviceName: String
    ) : GarminConnectionState()

    /** A specific Garmin device is connected and the WAGS app is found on it. */
    data class Connected(
        val deviceName: String,
        val deviceId: Long
    ) : GarminConnectionState()

    /** SDK initialization failed or device disconnected. */
    data class Error(val message: String) : GarminConnectionState()
}
