package com.example.wags.ui.settings

import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.OximeterBleManager
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import com.polar.sdk.api.model.PolarDeviceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val PREFS_NAME = "wags_device_prefs"
private const val KEY_H10_ID = "h10_device_id"
private const val KEY_VERITY_ID = "verity_device_id"

data class SettingsUiState(
    val h10State: BleConnectionState = BleConnectionState.Disconnected,
    val verityState: BleConnectionState = BleConnectionState.Disconnected,
    val oximeterState: OximeterConnectionState = OximeterConnectionState.Disconnected,
    /** true while either Polar or Oximeter scan is running */
    val isScanning: Boolean = false,
    val polarScanResults: List<PolarDeviceInfo> = emptyList(),
    val oximeterScanResults: List<ScanResult> = emptyList(),
    val savedH10Id: String = "",
    val savedVerityId: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val uiState: StateFlow<SettingsUiState> = combine(
        bleManager.h10State,
        bleManager.verityState,
        bleManager.isScanning,
        bleManager.scanResults,
        oximeterBleManager.connectionState,
        oximeterBleManager.scanResults
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val h10State        = args[0] as BleConnectionState
        @Suppress("UNCHECKED_CAST")
        val verityState     = args[1] as BleConnectionState
        val polarScanning   = args[2] as Boolean
        @Suppress("UNCHECKED_CAST")
        val polarResults    = args[3] as List<PolarDeviceInfo>
        val oximeterState   = args[4] as OximeterConnectionState
        @Suppress("UNCHECKED_CAST")
        val oximeterResults = args[5] as List<ScanResult>

        val oximeterScanning = oximeterState is OximeterConnectionState.Scanning

        SettingsUiState(
            h10State = h10State,
            verityState = verityState,
            oximeterState = oximeterState,
            isScanning = polarScanning || oximeterScanning,
            polarScanResults = polarResults,
            oximeterScanResults = oximeterResults,
            savedH10Id = prefs.getString(KEY_H10_ID, "") ?: "",
            savedVerityId = prefs.getString(KEY_VERITY_ID, "") ?: ""
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            savedH10Id = prefs.getString(KEY_H10_ID, "") ?: "",
            savedVerityId = prefs.getString(KEY_VERITY_ID, "") ?: ""
        )
    )

    // ── Unified scan ─────────────────────────────────────────────────────────

    /** Starts both Polar and Oximeter scans simultaneously. */
    fun startScan() {
        bleManager.startScan()
        oximeterBleManager.startScan()
    }

    /** Stops both scans. */
    fun stopScan() {
        bleManager.stopScan()
        oximeterBleManager.stopScan()
    }

    // ── Polar connections ─────────────────────────────────────────────────────

    fun connectH10(device: PolarDeviceInfo) {
        // Disconnect oximeter first — only one HR source at a time
        oximeterBleManager.disconnect()
        prefs.edit().putString(KEY_H10_ID, device.deviceId).apply()
        bleManager.connectDevice(device.deviceId, isH10 = true)
    }

    fun connectVerity(device: PolarDeviceInfo) {
        // Disconnect oximeter first — only one HR source at a time
        oximeterBleManager.disconnect()
        prefs.edit().putString(KEY_VERITY_ID, device.deviceId).apply()
        bleManager.connectDevice(device.deviceId, isH10 = false)
    }

    fun disconnectH10() {
        val id = prefs.getString(KEY_H10_ID, "") ?: ""
        if (id.isNotBlank()) bleManager.disconnectDevice(id)
    }

    fun disconnectVerity() {
        val id = prefs.getString(KEY_VERITY_ID, "") ?: ""
        if (id.isNotBlank()) bleManager.disconnectDevice(id)
    }

    // ── Oximeter connections ──────────────────────────────────────────────────

    fun connectOximeter(address: String) {
        stopScan()
        // Disconnect Polar devices first — only one HR source at a time
        disconnectH10()
        disconnectVerity()
        oximeterBleManager.connect(address)
    }

    fun disconnectOximeter() = oximeterBleManager.disconnect()

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
