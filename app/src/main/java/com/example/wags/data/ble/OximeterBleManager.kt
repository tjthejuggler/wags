package com.example.wags.data.ble

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.example.wags.domain.model.OximeterConnectionState
import com.example.wags.domain.model.OximeterReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OximeterBleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState =
        MutableStateFlow<OximeterConnectionState>(OximeterConnectionState.Disconnected)
    val connectionState: StateFlow<OximeterConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<OximeterReading>(replay = 1, extraBufferCapacity = 64)
    val readings: SharedFlow<OximeterReading> = _readings.asSharedFlow()

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Scan ─────────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanJob?.cancel()
        _scanResults.value = emptyList()
        _connectionState.value = OximeterConnectionState.Scanning(0)

        scanJob = scope.launch {
            scanner.startScan(scanCallback)
            delay(SCAN_TIMEOUT_MS)
            scanner.stopScan(scanCallback)
            if (_connectionState.value is OximeterConnectionState.Scanning) {
                _connectionState.value = OximeterConnectionState.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is OximeterConnectionState.Scanning) {
            _connectionState.value = OximeterConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith("OxySmart") && name != "PC-60F") return
            val current = _scanResults.value.toMutableList()
            if (current.none { it.device.address == result.device.address }) {
                current.add(result)
                _scanResults.value = current
                _connectionState.value = OximeterConnectionState.Scanning(current.size)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = OximeterConnectionState.Error("Scan failed: $errorCode")
        }
    }

    // ── Connect / Disconnect ─────────────────────────────────────────────────

    fun connect(deviceAddress: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: run {
            _connectionState.value = OximeterConnectionState.Error("Invalid address: $deviceAddress")
            return
        }
        _connectionState.value = OximeterConnectionState.Connecting(deviceAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    fun release() {
        scanJob?.cancel()
        bluetoothGatt?.close()
        bluetoothGatt = null
        scope.cancel()
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = OximeterConnectionState.Connecting(gatt.device.address)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = OximeterConnectionState.Disconnected
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotifications(gatt)
                _connectionState.value = OximeterConnectionState.Connected(
                    gatt.device.address,
                    gatt.device.name ?: "Oximeter"
                )
            } else {
                _connectionState.value = OximeterConnectionState.Error("Service discovery failed: $status")
            }
        }

        // API 33+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parsePacket(value)?.let { reading ->
                scope.launch { _readings.emit(reading) }
            }
        }

        // API < 33 fallback
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                parsePacket(characteristic.value ?: return)?.let { reading ->
                    scope.launch { _readings.emit(reading) }
                }
            }
        }
    }

    // ── Enable Notifications ─────────────────────────────────────────────────

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(NORDIC_UART_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID) ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    // ── Packet Parser ────────────────────────────────────────────────────────

    private fun parsePacket(bytes: ByteArray): OximeterReading? {
        if (bytes.size < 9) return null
        if (bytes[0].toInt() and 0xFF != 0xAA) return null

        val cmd = bytes[1].toInt() and 0xFF
        val validation = bytes[2].toInt() and 0xFF
        if (validation != (cmd xor 0xFF)) return null

        val payloadLength = (bytes[5].toInt() and 0xFF) or ((bytes[6].toInt() and 0xFF) shl 8)
        if (bytes.size < 7 + payloadLength + 1) return null

        val payload = bytes.copyOfRange(7, 7 + payloadLength)
        val receivedCrc = bytes[7 + payloadLength].toInt() and 0xFF
        if (receivedCrc != calculateCrc8Ccitt(payload)) return null

        if (payload.size <= maxOf(SPO2_PAYLOAD_INDEX, HR_PAYLOAD_INDEX)) return null

        val spO2 = payload[SPO2_PAYLOAD_INDEX].toInt() and 0xFF
        val hr = payload[HR_PAYLOAD_INDEX].toInt() and 0xFF

        if (spO2 < 50 || spO2 > 100) return null
        if (hr < 20 || hr > 300) return null

        return OximeterReading(spO2 = spO2, heartRateBpm = hr)
    }

    private fun calculateCrc8Ccitt(data: ByteArray): Int {
        var crc = 0xFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc
    }

    companion object {
        var SPO2_PAYLOAD_INDEX = 4
        var HR_PAYLOAD_INDEX = 5
        private const val SCAN_TIMEOUT_MS = 30_000L
        private val NORDIC_UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
