package com.example.wags.data.ble

import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for live heart rate and SpO₂ across the app.
 *
 * Priority: Polar (H10 or Verity Sense) takes precedence for HR when connected;
 * oximeter HR is used when no Polar device is streaming.
 * SpO₂ is only available from the oximeter.
 *
 * Also auto-records device labels to [DevicePreferencesRepository] whenever a
 * device connects, so the label history is always up-to-date for the edit
 * dropdown on past records.  Labels use the device's own advertised name
 * (e.g. "Polar H10 A1B2C3D4") without any artificial type prefix.
 */
@Singleton
class HrDataSource @Inject constructor(
    private val polarBleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val devicePrefs: DevicePreferencesRepository
) {
    private val scope = CoroutineScope(SupervisorJob())

    init {
        // Auto-record device labels whenever a device connects, so the label
        // history is always populated for the edit dropdown on past records.
        // We use the device's own advertised name — no artificial type prefix.
        polarBleManager.h10State.onEach { state ->
            if (state is BleConnectionState.Connected) {
                val name = state.deviceName.ifBlank { state.deviceId }
                devicePrefs.recordDeviceLabel(name)
            }
        }.launchIn(scope)

        polarBleManager.verityState.onEach { state ->
            if (state is BleConnectionState.Connected) {
                val name = state.deviceName.ifBlank { state.deviceId }
                devicePrefs.recordDeviceLabel(name)
            }
        }.launchIn(scope)

        oximeterBleManager.connectionState.onEach { state ->
            if (state is OximeterConnectionState.Connected) {
                val name = state.deviceName.ifBlank { state.deviceAddress }
                devicePrefs.recordDeviceLabel(name)
            }
        }.launchIn(scope)
    }

    /**
     * True when at least one HR-capable device (Polar or Oximeter) is connected.
     */
    val isAnyHrDeviceConnected: StateFlow<Boolean> = combine(
        polarBleManager.h10State,
        polarBleManager.verityState,
        oximeterBleManager.connectionState
    ) { h10, verity, oxy ->
        h10 is BleConnectionState.Connected ||
            verity is BleConnectionState.Connected ||
            oxy is OximeterConnectionState.Connected
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Merged live HR in bpm. Polar HR takes priority; falls back to oximeter.
     * Null when no device is connected or no data has arrived yet.
     */
    val liveHr: StateFlow<Int?> = combine(
        polarBleManager.liveHr,
        oximeterBleManager.liveHr
    ) { polarHr, oxyHr ->
        polarHr ?: oxyHr
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Live SpO₂ percentage from the oximeter. Null when oximeter is not connected
     * or no reading has arrived yet. Polar devices do not provide SpO₂.
     */
    val liveSpO2: StateFlow<Int?> = oximeterBleManager.liveSpO2
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * True when the oximeter is the primary (or only) HR/SpO₂ device.
     *
     * SpO₂ data is only meaningful when the oximeter is the device the user
     * intends to use for the hold.  When a Polar device is connected it takes
     * priority for HR, and any SpO₂ readings from a background-connected
     * oximeter are incidental — they should **not** be saved alongside the
     * hold record because they represent resting values (typically 99 %) that
     * would pollute the chart and stats.
     *
     * Returns `true` only when:
     *   • No Polar device is connected, AND
     *   • The oximeter IS connected.
     */
    fun isOximeterPrimaryDevice(): Boolean {
        val h10 = polarBleManager.h10State.value
        val verity = polarBleManager.verityState.value
        val oxy = oximeterBleManager.connectionState.value
        val polarConnected = h10 is BleConnectionState.Connected ||
            verity is BleConnectionState.Connected
        val oxyConnected = oxy is OximeterConnectionState.Connected
        return !polarConnected && oxyConnected
    }

    /**
     * Returns a human-readable label for the currently connected HR/SpO₂ device,
     * suitable for storing alongside recorded data so the source is always known.
     *
     * Uses the device's own advertised name (no artificial type prefix).
     *
     * Priority: Polar H10 → Polar Verity Sense → Oximeter → null (none connected).
     *
     * Examples:
     *   "Polar H10 A1B2C3D4"
     *   "Polar Sense B5C6D7E8"
     *   "PC-60F"
     */
    fun activeHrDeviceLabel(): String? {
        val h10 = polarBleManager.h10State.value
        if (h10 is BleConnectionState.Connected) {
            return h10.deviceName.ifBlank { h10.deviceId }
        }

        val verity = polarBleManager.verityState.value
        if (verity is BleConnectionState.Connected) {
            return verity.deviceName.ifBlank { verity.deviceId }
        }

        val oxy = oximeterBleManager.connectionState.value
        if (oxy is OximeterConnectionState.Connected) {
            return oxy.deviceName.ifBlank { oxy.deviceAddress }
        }

        return null
    }

    /**
     * Returns the Polar device ID of the currently connected H10 or Verity Sense,
     * or null if no Polar device is connected.
     *
     * Used to start RR/HR streams with the correct device identifier.
     */
    fun connectedPolarDeviceId(): String? {
        val h10 = polarBleManager.h10State.value
        if (h10 is BleConnectionState.Connected) return h10.deviceId

        val verity = polarBleManager.verityState.value
        if (verity is BleConnectionState.Connected) return verity.deviceId

        return null
    }
}
