package com.example.wags.domain.model

/**
 * Recognized BLE device types, determined from the device's advertised name
 * after connection.
 *
 * Each type declares the [capabilities] it supports so session logic can check
 * whether the connected device can provide the data it needs (e.g. ACC for
 * stand detection, SPO2 for oxygen saturation).
 */
enum class DeviceType(val capabilities: Set<DeviceCapability>) {

    /** Polar H10 chest strap — HR, RR intervals, ECG, accelerometer. */
    POLAR_H10(setOf(
        DeviceCapability.HR,
        DeviceCapability.RR,
        DeviceCapability.ECG,
        DeviceCapability.ACC
    )),

    /** Polar Verity Sense optical sensor — HR, PPI (pulse-to-pulse intervals). */
    POLAR_VERITY(setOf(
        DeviceCapability.HR,
        DeviceCapability.RR,
        DeviceCapability.PPI
    )),

    /** OxySmart / PC-60F / Viatom / Wellue pulse oximeter — HR, SpO₂, RR (synthesized from HR). */
    OXIMETER(setOf(
        DeviceCapability.HR,
        DeviceCapability.SPO2,
        DeviceCapability.RR
    )),

    /** Any other BLE device — HR, and RR (synthesized from HR or extracted from standard BLE HR packets). */
    GENERIC_BLE(setOf(
        DeviceCapability.HR,
        DeviceCapability.RR
    ));

    /** True if this device uses the Polar SDK for connection and streaming. */
    val isPolar: Boolean get() = this == POLAR_H10 || this == POLAR_VERITY

    fun has(capability: DeviceCapability): Boolean = capability in capabilities

    companion object {
        /**
         * Detect the device type from its advertised BLE name.
         *
         * Rules:
         *   - Contains "H10" → [POLAR_H10]
         *   - Contains "Sense" → [POLAR_VERITY]
         *   - Contains "OxySmart", "PC-60", "Viatom", "Wellue", or "O2Ring" → [OXIMETER]
         *   - Starts with "Polar " (but not H10/Sense) → [POLAR_H10] (safe default)
         *   - Anything else → [GENERIC_BLE]
         */
        fun fromName(name: String): DeviceType {
            val upper = name.uppercase()
            return when {
                upper.contains("H10") -> POLAR_H10
                upper.contains("SENSE") -> POLAR_VERITY
                upper.contains("OXYSMART") -> OXIMETER
                upper.contains("PC-60") -> OXIMETER
                upper.contains("VIATOM") -> OXIMETER
                upper.contains("WELLUE") -> OXIMETER
                upper.contains("O2RING") -> OXIMETER
                upper.startsWith("POLAR ") -> POLAR_H10 // unknown Polar → H10 default
                else -> GENERIC_BLE
            }
        }
    }
}
