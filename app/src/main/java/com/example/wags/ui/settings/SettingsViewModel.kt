package com.example.wags.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.domain.model.BleConnectionState
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
    val isScanning: Boolean = false,
    val scanResults: List<PolarDeviceInfo> = emptyList(),
    val savedH10Id: String = "",
    val savedVerityId: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: PolarBleManager
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val uiState: StateFlow<SettingsUiState> = combine(
        bleManager.h10State,
        bleManager.verityState,
        bleManager.isScanning,
        bleManager.scanResults
    ) { h10State, verityState, isScanning, scanResults ->
        SettingsUiState(
            h10State = h10State,
            verityState = verityState,
            isScanning = isScanning,
            scanResults = scanResults,
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

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    fun connectH10(device: PolarDeviceInfo) {
        prefs.edit().putString(KEY_H10_ID, device.deviceId).apply()
        bleManager.connectDevice(device.deviceId, isH10 = true)
    }

    fun connectVerity(device: PolarDeviceInfo) {
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

    override fun onCleared() {
        bleManager.stopScan()
        super.onCleared()
    }
}
