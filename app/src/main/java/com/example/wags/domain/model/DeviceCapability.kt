package com.example.wags.domain.model

/**
 * Data-streaming capabilities that a BLE device may support.
 * Used by session logic to verify the connected device can provide
 * the data required for a particular session type.
 */
enum class DeviceCapability {
    /** Live heart rate in BPM. */
    HR,
    /** RR intervals (inter-beat intervals) for HRV analysis. */
    RR,
    /** Electrocardiogram waveform (Polar H10 only). */
    ECG,
    /** Accelerometer data (Polar H10 only — used for stand detection). */
    ACC,
    /** Pulse-to-pulse intervals from optical sensor (Polar Verity Sense). */
    PPI,
    /** Blood oxygen saturation percentage (pulse oximeters). */
    SPO2
}
