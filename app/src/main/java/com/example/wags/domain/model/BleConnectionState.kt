package com.example.wags.domain.model

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    data class Connecting(val deviceId: String) : BleConnectionState()
    data class Connected(val deviceId: String, val deviceName: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
