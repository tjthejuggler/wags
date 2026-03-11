package com.example.wags.domain.model

sealed class OximeterConnectionState {
    object Disconnected : OximeterConnectionState()
    data class Scanning(val devicesFound: Int = 0) : OximeterConnectionState()
    data class Connecting(val deviceAddress: String) : OximeterConnectionState()
    data class Connected(val deviceAddress: String, val deviceName: String) : OximeterConnectionState()
    data class Error(val message: String) : OximeterConnectionState()
}
