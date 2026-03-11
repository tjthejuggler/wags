package com.example.wags.data.ble

import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for live heart rate and SpO₂ across the app.
 *
 * Priority: Polar (H10 or Verity Sense) takes precedence for HR when connected;
 * oximeter HR is used when no Polar device is streaming.
 * SpO₂ is only available from the oximeter.
 */
@Singleton
class HrDataSource @Inject constructor(
    private val polarBleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager
) {
    private val scope = CoroutineScope(SupervisorJob())

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
}
