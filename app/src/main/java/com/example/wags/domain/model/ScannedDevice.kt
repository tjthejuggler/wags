package com.example.wags.domain.model

/**
 * A BLE device discovered during scanning, before connection.
 *
 * This is a unified representation — the UI shows a single list of these
 * regardless of whether the device will be handled by the Polar SDK or
 * raw Android BLE GATT internally.
 *
 * @param identifier Polar device ID (e.g. "A1B2C3D4") or BLE MAC address.
 * @param name       Advertised device name, or the identifier if no name.
 * @param rssi       Signal strength in dBm (for sorting by proximity).
 * @param isPolar    True if this device was found by the Polar SDK scan.
 *                   Determines which backend is used for connection.
 */
data class ScannedDevice(
    val identifier: String,
    val name: String,
    val rssi: Int = 0,
    val isPolar: Boolean = false
)
