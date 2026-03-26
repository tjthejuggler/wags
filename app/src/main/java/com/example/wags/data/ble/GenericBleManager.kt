package com.example.wags.data.ble

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.DeviceType
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.ScannedDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenericBLE"

/**
 * BLE manager for non-Polar devices (oximeters, generic HR sensors, etc.)
 * using raw Android BLE GATT.
 *
 * Renamed from OximeterBleManager — now handles any non-Polar BLE device.
 * Device type is determined from the advertised name after connection.
 *
 * Protocol for oximeters: Nordic UART Service (NUS) — all data arrives on
 * the TX characteristic (6E400003). Packets always start with AA 55.
 */
@Singleton
class GenericBleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Invoked whenever the device disconnects. Set by AutoConnectManager. */
    var onDisconnected: (() -> Unit)? = null

    /**
     * Invoked when the background scan detects a known device.
     * Set by AutoConnectManager to trigger an immediate connect attempt.
     */
    var onKnownDeviceFound: ((address: String) -> Unit)? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState =
        MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<OximeterReading>(replay = 1, extraBufferCapacity = 64)
    val readings: SharedFlow<OximeterReading> = _readings.asSharedFlow()

    /** Live heart rate, null when not connected or no data yet. */
    private val _liveHr = MutableStateFlow<Int?>(null)
    val liveHr: StateFlow<Int?> = _liveHr.asStateFlow()

    /** Live SpO₂, null when not connected or no data yet. */
    private val _liveSpO2 = MutableStateFlow<Int?>(null)
    val liveSpO2: StateFlow<Int?> = _liveSpO2.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    /** Unified scan results for the UnifiedDeviceManager. */
    val unifiedScanResults: StateFlow<List<ScannedDevice>> = _scanResults.map { results ->
        results.map { result ->
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
                ?: result.device.address
            ScannedDevice(
                identifier = result.device.address,
                name = name,
                rssi = result.rssi,
                isPolar = false
            )
        }
    }.stateIn(CoroutineScope(Dispatchers.IO + SupervisorJob()), SharingStarted.Eagerly, emptyList())

    private var bluetoothGatt: BluetoothGatt? = null
    private val gattLock = Any()

    private var scanJob: Job? = null
    private var autoConnectJob: Job? = null
    private var backgroundScanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var notifyQueueBusy = false

    // ── UI Scan ───────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanJob?.cancel()
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(uiScanCallback) } catch (_: Exception) {}
        _scanResults.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning(0)

        scanJob = scope.launch {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, uiScanCallback)
            delay(SCAN_TIMEOUT_MS)
            try { scanner.stopScan(uiScanCallback) } catch (_: Exception) {}
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(uiScanCallback) } catch (_: Exception) {}
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    /**
     * Scan callback for user-initiated scan. Excludes Polar devices since
     * those are handled by [PolarBleManager].
     */
    private val uiScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
            // Exclude Polar devices — they belong in the Polar scan only
            if (name != null && name.startsWith("Polar ", ignoreCase = true)) return
            val current = _scanResults.value.toMutableList()
            if (current.none { it.device.address == result.device.address }) {
                val displayName = name ?: result.device.address
                Log.d(TAG, "Found BLE device: $displayName  ${result.device.address}")
                current.add(result)
                _scanResults.value = current
                _connectionState.value = BleConnectionState.Scanning(current.size)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _connectionState.value = BleConnectionState.Error("Scan failed: $errorCode")
        }
    }

    private val scanCallback get() = uiScanCallback

    // ── Background scan ───────────────────────────────────────────────────────

    fun startBackgroundScan(addresses: List<String>) {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (addresses.isEmpty()) return

        stopBackgroundScan()
        Log.d(TAG, "Starting background scan for ${addresses.size} device(s): $addresses")

        backgroundScanJob = scope.launch {
            val found = AtomicBoolean(false)

            val bgCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val addr = result.device.address
                    if (addresses.any { it.equals(addr, ignoreCase = true) }) {
                        if (found.compareAndSet(false, true)) {
                            Log.d(TAG, "Background scan found known device: $addr")
                            onKnownDeviceFound?.invoke(addr)
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Background scan failed: $errorCode")
                }
            }

            val filters = addresses.map { addr ->
                ScanFilter.Builder().setDeviceAddress(addr).build()
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()

            while (isActive) {
                found.set(false)
                try {
                    scanner.startScan(filters, settings, bgCallback)
                    delay(BG_SCAN_WINDOW_MS)
                    scanner.stopScan(bgCallback)
                } catch (e: CancellationException) {
                    scanner.stopScan(bgCallback)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Background scan error: ${e.message}")
                }

                if (found.get()) break
                delay(BG_SCAN_PAUSE_MS)
            }
        }
    }

    fun stopBackgroundScan() {
        backgroundScanJob?.cancel()
        backgroundScanJob = null
    }

    // ── Connect / Disconnect ─────────────────────────────────────────────────

    fun connect(deviceAddress: String) {
        scanJob?.cancel()
        scanJob = null
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }

        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: run {
            _connectionState.value = BleConnectionState.Error("Invalid address: $deviceAddress")
            return
        }

        synchronized(gattLock) {
            val currentState = _connectionState.value
            if (currentState is BleConnectionState.Connected &&
                currentState.deviceId.equals(deviceAddress, ignoreCase = true)
            ) {
                Log.d(TAG, "Already connected to $deviceAddress — skipping")
                return
            }

            bluetoothGatt?.let { oldGatt ->
                Log.d(TAG, "Closing stale GATT before new connect")
                try { oldGatt.disconnect() } catch (_: Exception) {}
                try { oldGatt.close() } catch (_: Exception) {}
                bluetoothGatt = null
            }

            Log.d(TAG, "Connecting to $deviceAddress")
            _connectionState.value = BleConnectionState.Connecting(deviceAddress)
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun connectWithScan(deviceAddress: String) {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            connect(deviceAddress)
            return
        }

        autoConnectJob?.cancel()
        Log.d(TAG, "Auto-scan for $deviceAddress …")

        autoConnectJob = scope.launch {
            val deviceFound = AtomicBoolean(false)

            val targetCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.device.address.equals(deviceAddress, ignoreCase = true)) {
                        Log.d(TAG, "Auto-scan found $deviceAddress")
                        deviceFound.set(true)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Auto-scan failed: $errorCode — will fall back to direct connect")
                    deviceFound.set(true)
                }
            }

            val filters = listOf(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(filters, settings, targetCallback)
            try {
                val deadline = System.currentTimeMillis() + AUTO_SCAN_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline && !deviceFound.get()) {
                    delay(100)
                }
            } finally {
                scanner.stopScan(targetCallback)
            }

            Log.d(TAG, "Auto-scan done (found=${deviceFound.get()}) — connecting to $deviceAddress")
            connect(deviceAddress)
        }
    }

    fun disconnect() {
        synchronized(gattLock) {
            bluetoothGatt?.disconnect()
        }
    }

    fun release() {
        scanJob?.cancel()
        autoConnectJob?.cancel()
        backgroundScanJob?.cancel()
        synchronized(gattLock) {
            try { bluetoothGatt?.close() } catch (_: Exception) {}
            bluetoothGatt = null
        }
        scope.cancel()
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState device=${gatt.device.address}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected — discovering services")
                    _connectionState.value = BleConnectionState.Connecting(gatt.device.address)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    synchronized(gattLock) {
                        val isCurrentGatt = (gatt === bluetoothGatt)
                        if (isCurrentGatt) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                _connectionState.value = BleConnectionState.Error(
                                    "Connection failed (status=$status)"
                                )
                            } else {
                                _connectionState.value = BleConnectionState.Disconnected
                            }
                            _liveHr.value = null
                            _liveSpO2.value = null
                            bluetoothGatt = null
                        } else {
                            Log.d(TAG, "Ignoring disconnect from stale GATT instance")
                        }
                    }
                    try { gatt.close() } catch (_: Exception) {}
                    synchronized(gattLock) {
                        if (bluetoothGatt == null) {
                            onDisconnected?.invoke()
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = BleConnectionState.Error("Service discovery failed: $status")
                return
            }

            gatt.services.forEach { svc ->
                Log.d(TAG, "Service: ${svc.uuid}")
                svc.characteristics.forEach { chr ->
                    Log.d(TAG, "  Char: ${chr.uuid}  props=0x${chr.properties.toString(16)}")
                }
            }

            notifyQueue.clear()
            notifyQueueBusy = false
            gatt.services.forEach { svc ->
                svc.characteristics.forEach { chr ->
                    val props = chr.properties
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (canNotify || canIndicate) {
                        notifyQueue.addLast(chr)
                    }
                }
            }

            Log.d(TAG, "Queued ${notifyQueue.size} characteristics for notification")
            drainNotifyQueue(gatt)

            val deviceName = gatt.device.name ?: "BLE Device"
            val deviceType = DeviceType.fromName(deviceName)
            _connectionState.value = BleConnectionState.Connected(
                gatt.device.address,
                deviceName,
                deviceType
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite ${descriptor.characteristic.uuid} status=$status")
            notifyQueueBusy = false
            drainNotifyQueue(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicData(characteristic.value ?: return)
            }
        }
    }

    // ── Notification queue drain ──────────────────────────────────────────────

    private fun drainNotifyQueue(gatt: BluetoothGatt) {
        if (notifyQueueBusy || notifyQueue.isEmpty()) return
        val chr = notifyQueue.removeFirst()
        notifyQueueBusy = true
        enableNotificationForChar(gatt, chr)
    }

    private fun enableNotificationForChar(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(chr, true)
        val descriptor = chr.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "No CCCD on ${chr.uuid} — skipping descriptor write")
            notifyQueueBusy = false
            drainNotifyQueue(gatt)
            return
        }
        val value = if ((chr.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    // ── Data handler ─────────────────────────────────────────────────────────

    private fun handleCharacteristicData(bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Packet [${bytes.size}]: $hex")

        if (bytes.size < 2 || bytes[0].toInt() and 0xFF != 0xAA || bytes[1].toInt() and 0xFF != 0x55) {
            Log.d(TAG, "Skipping non-AA55 packet")
            return
        }

        if (bytes.size >= 3 && bytes[2].toInt() and 0xFF == 0xF0) {
            Log.d(TAG, "Keepalive packet — ignored")
            return
        }

        if (bytes.size >= 7 && bytes[4].toInt() and 0xFF == 0x01) {
            val spO2 = bytes[5].toInt() and 0xFF
            val hr   = bytes[6].toInt() and 0xFF
            Log.d(TAG, "Readings: SpO2=$spO2%  HR=$hr bpm")
            _liveSpO2.value = spO2
            _liveHr.value = hr
            val reading = OximeterReading(spO2 = spO2, heartRateBpm = hr)
            scope.launch { _readings.emit(reading) }
            return
        }

        if (bytes.size >= 5 && bytes[4].toInt() and 0xFF == 0x02) {
            Log.d(TAG, "Waveform packet — ignored")
            return
        }

        Log.d(TAG, "Unknown packet type — ignored")
    }

    companion object {
        private const val SCAN_TIMEOUT_MS      = 30_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 8_000L
        private const val BG_SCAN_WINDOW_MS    = 5_000L
        private const val BG_SCAN_PAUSE_MS     = 10_000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
