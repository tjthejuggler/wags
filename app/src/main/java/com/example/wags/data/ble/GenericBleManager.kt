package com.example.wags.data.ble

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
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

    /** The device type of the currently connected device (for protocol selection). */
    @Volatile private var connectedDeviceType: DeviceType = DeviceType.GENERIC_BLE

    private var scanJob: Job? = null
    private var autoConnectJob: Job? = null
    private var backgroundScanJob: Job? = null
    private var o2RingPollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var notifyQueueBusy = false

    // ── UI Scan ───────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanJob?.cancel()
        try { scanner.stopScan(uiScanCallback) } catch (_: Exception) {}
        try { scanner.stopScan(o2RingScanCallback) } catch (_: Exception) {}
        _scanResults.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning(0)

        scanJob = scope.launch {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Main unfiltered scan — finds most BLE devices
            scanner.startScan(null, settings, uiScanCallback)

            // Parallel service-UUID-filtered scan for O2Ring / Viatom devices.
            // Some devices only advertise their custom service UUID and are not
            // found by an unfiltered scan on certain Android versions/phones.
            val o2RingFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(O2RING_SERVICE_UUID))
                .build()
            try {
                scanner.startScan(listOf(o2RingFilter), settings, o2RingScanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "O2Ring filtered scan failed to start: ${e.message}")
            }

            delay(SCAN_TIMEOUT_MS)
            try { scanner.stopScan(uiScanCallback) } catch (_: Exception) {}
            try { scanner.stopScan(o2RingScanCallback) } catch (_: Exception) {}
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(uiScanCallback) } catch (_: Exception) {}
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(o2RingScanCallback) } catch (_: Exception) {}
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

    /**
     * Separate scan callback for the O2Ring service-UUID-filtered scan.
     * Feeds results into the same [_scanResults] list as the main scan.
     */
    private val o2RingScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
            val current = _scanResults.value.toMutableList()
            if (current.none { it.device.address == result.device.address }) {
                val displayName = name ?: "O2Ring ${result.device.address.takeLast(5)}"
                Log.d(TAG, "O2Ring scan found: $displayName  ${result.device.address}")
                current.add(result)
                _scanResults.value = current
                _connectionState.value = BleConnectionState.Scanning(current.size)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Don't set Error state — the main scan is still running
            Log.w(TAG, "O2Ring filtered scan failed: $errorCode")
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
        o2RingPollJob?.cancel()
        o2RingPollJob = null
        synchronized(gattLock) {
            val gatt = bluetoothGatt
            if (gatt == null) {
                // No GATT — just ensure state is Disconnected
                val cur = _connectionState.value
                if (cur !is BleConnectionState.Disconnected && cur !is BleConnectionState.Scanning) {
                    _connectionState.value = BleConnectionState.Disconnected
                    _liveHr.value = null
                    _liveSpO2.value = null
                }
                return
            }
            try {
                gatt.disconnect()
            } catch (e: Exception) {
                // Device already gone (turned off, out of range, etc.)
                // Force-clean the state so the UI doesn't get stuck.
                Log.w(TAG, "disconnect() threw — force-cleaning: ${e.message}")
                _connectionState.value = BleConnectionState.Disconnected
                _liveHr.value = null
                _liveSpO2.value = null
                bluetoothGatt = null
                try { gatt.close() } catch (_: Exception) {}
                scope.launch { onDisconnected?.invoke() }
            }
        }
    }

    fun release() {
        scanJob?.cancel()
        autoConnectJob?.cancel()
        backgroundScanJob?.cancel()
        o2RingPollJob?.cancel()
        synchronized(gattLock) {
            try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
            try { bluetoothGatt?.close() } catch (_: Exception) {}
            bluetoothGatt = null
        }
        _connectionState.value = BleConnectionState.Disconnected
        _liveHr.value = null
        _liveSpO2.value = null
        scope.cancel()
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState device=${gatt.device.address}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Only proceed if this is still the current GATT instance
                    synchronized(gattLock) {
                        if (gatt !== bluetoothGatt) {
                            Log.d(TAG, "Ignoring connect from stale GATT instance")
                            try { gatt.close() } catch (_: Exception) {}
                            return
                        }
                    }
                    Log.d(TAG, "Connected — discovering services")
                    _connectionState.value = BleConnectionState.Connecting(gatt.device.address)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    o2RingPollJob?.cancel()
                    o2RingPollJob = null
                    var shouldNotify = false
                    synchronized(gattLock) {
                        val isCurrentGatt = (gatt === bluetoothGatt)
                        if (isCurrentGatt) {
                            // Always go to Disconnected — the Error state was causing
                            // the UI to get stuck and preventing reconnect/disconnect.
                            _connectionState.value = BleConnectionState.Disconnected
                            _liveHr.value = null
                            _liveSpO2.value = null
                            bluetoothGatt = null
                            shouldNotify = true
                        } else {
                            Log.d(TAG, "Ignoring disconnect from stale GATT instance")
                        }
                        // Close inside the lock so a racing connect() won't see
                        // the old GATT reference after we've nulled it.
                        try { gatt.close() } catch (_: Exception) {}
                    }
                    if (shouldNotify) {
                        onDisconnected?.invoke()
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

            val deviceName = gatt.device.name ?: "BLE Device"
            val deviceType = DeviceType.fromName(deviceName)
            connectedDeviceType = deviceType

            // Polar devices should be handled by the Polar SDK, not generic GATT.
            // If a Polar device ended up here (e.g. its MAC was saved as non-Polar
            // in the device history), disconnect immediately so the Polar SDK can
            // handle it on the next auto-connect cycle.
            if (deviceType.isPolar) {
                Log.w(TAG, "Polar device '$deviceName' connected via generic GATT — disconnecting to let Polar SDK handle it")
                synchronized(gattLock) {
                    try { gatt.disconnect() } catch (_: Exception) {}
                    try { gatt.close() } catch (_: Exception) {}
                    bluetoothGatt = null
                }
                _connectionState.value = BleConnectionState.Disconnected
                return
            }

            // Check if this is an O2Ring (Viatom/Wellue proprietary service)
            val isO2Ring = gatt.getService(O2RING_SERVICE_UUID) != null
            Log.d(TAG, "Device type: $deviceType, isO2Ring=$isO2Ring")

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

            _connectionState.value = BleConnectionState.Connected(
                gatt.device.address,
                deviceName,
                deviceType
            )

            // Start periodic live-data polling for O2Ring devices
            if (isO2Ring) {
                startO2RingPolling(gatt)
            }
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

    /**
     * Buffer for reassembling multi-notification O2Ring response packets.
     * The O2Ring splits responses across multiple BLE notifications due to
     * the 20-byte MTU limit.
     */
    private val o2RingBuffer = java.io.ByteArrayOutputStream(256)
    private var o2RingExpectedLen = -1

    private fun handleCharacteristicData(bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Packet [${bytes.size}]: $hex")

        if (bytes.isEmpty()) return

        val header = bytes[0].toInt() and 0xFF

        // ── Standard BLE Heart Rate Measurement (UUID 0x2A37) ─────────────────
        // Flags byte (header): bit 0 = HR format (0=uint8, 1=uint16),
        // bit 4 = RR intervals present. Works with any standard HR strap.
        if (bytes.size >= 2 && isStandardHrPacket(bytes)) {
            handleStandardHrPacket(bytes)
            return
        }

        // ── OxySmart / PC-60F protocol (AA 55 …) ─────────────────────────────
        if (header == 0xAA && bytes.size >= 2 && bytes[1].toInt() and 0xFF == 0x55) {
            handleOxySmartPacket(bytes)
            return
        }

        // ── Wellue O2Ring / Viatom response (header 0x55) ─────────────────────
        // The O2Ring sends commands with 0xAA but RESPONDS with 0x55.
        // Responses may be split across multiple BLE notifications.
        if (header == 0x55 || o2RingBuffer.size() > 0) {
            handleO2RingChunk(bytes)
            return
        }

        // ── Wellue O2Ring / Viatom command echo (AA <cmd> …) ──────────────────
        if (header == 0xAA && bytes.size >= 2) {
            handleO2RingPacket(bytes)
            return
        }

        Log.d(TAG, "Skipping unrecognized packet (header=0x${"%02X".format(header)})")
    }

    /**
     * Heuristic to detect standard BLE Heart Rate Measurement packets.
     *
     * The flags byte encodes the HR format and optional fields. We check that
     * the HR value extracted from the packet is in a plausible range (20–250 bpm)
     * and that the packet length is consistent with the flags.
     */
    private fun isStandardHrPacket(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val flags = bytes[0].toInt() and 0xFF
        val hrIs16Bit = (flags and 0x01) != 0
        val hrOffset = 1
        val hr = if (hrIs16Bit) {
            if (bytes.size < 3) return false
            (bytes[hrOffset].toInt() and 0xFF) or ((bytes[hrOffset + 1].toInt() and 0xFF) shl 8)
        } else {
            bytes[hrOffset].toInt() and 0xFF
        }
        return hr in 20..250
    }

    /**
     * Parse a standard BLE Heart Rate Measurement packet (UUID 0x2A37).
     *
     * Format per Bluetooth SIG specification:
     *   [0] Flags: bit 0 = HR format (0=uint8, 1=uint16)
     *              bit 1-2 = sensor contact status
     *              bit 3 = energy expended present
     *              bit 4 = RR intervals present
     *   [1] HR value (uint8) or [1-2] HR value (uint16 LE)
     *   [...] optional energy expended (uint16 LE) if bit 3 set
     *   [...] optional RR intervals (uint16 LE each, in 1/1024 sec) if bit 4 set
     */
    private fun handleStandardHrPacket(bytes: ByteArray) {
        val flags = bytes[0].toInt() and 0xFF
        val hrIs16Bit = (flags and 0x01) != 0
        var offset = 1

        val hr = if (hrIs16Bit) {
            val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            v
        } else {
            val v = bytes[offset].toInt() and 0xFF
            offset += 1
            v
        }

        // Skip energy expended if present (bit 3)
        if ((flags and 0x08) != 0 && offset + 2 <= bytes.size) {
            offset += 2
        }

        Log.d(TAG, "Standard BLE HR: $hr bpm (flags=0x${"%02X".format(flags)})")

        if (hr in 20..250) {
            _liveHr.value = hr
        }
    }

    /**
     * Buffer incoming O2Ring response chunks until we have a complete packet.
     *
     * O2Ring response format (header 0x55):
     *   [0] 0x55 (response header)
     *   [1] command echo
     *   [2] ~command (validation)
     *   [3..4] block number (little-endian)
     *   [5..6] payload length (little-endian)
     *   [7..7+len-1] payload
     *   [last] CRC-8
     */
    private fun handleO2RingChunk(bytes: ByteArray) {
        o2RingBuffer.write(bytes)
        val buf = o2RingBuffer.toByteArray()

        // Need at least 7 bytes to read the header + length fields
        if (buf.size < 7) return

        // Parse expected total length from the header
        if (o2RingExpectedLen < 0) {
            val payloadLen = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
            o2RingExpectedLen = 7 + payloadLen + 1 // header(7) + payload + CRC(1)
            Log.d(TAG, "O2Ring response: payloadLen=$payloadLen, expectedTotal=$o2RingExpectedLen")
        }

        if (buf.size >= o2RingExpectedLen) {
            // Complete packet — parse it
            val fullHex = buf.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "O2Ring complete response [${buf.size}]: $fullHex")
            parseO2RingResponse(buf)

            // Reset buffer
            o2RingBuffer.reset()
            o2RingExpectedLen = -1
        } else {
            Log.d(TAG, "O2Ring buffering: have ${buf.size}/$o2RingExpectedLen bytes")
        }
    }

    /**
     * Parse a complete O2Ring response packet (header 0x55).
     *
     * The response payload for live data contains SpO2 at offset [7] and
     * HR at offset [8]. Validated against real device output:
     *   55 00 FF 00 00 0D 00 [SpO2] [HR] ...
     */
    private fun parseO2RingResponse(packet: ByteArray) {
        if (packet.size < 8 || packet[0].toInt() and 0xFF != 0x55) return

        val command = packet[1].toInt() and 0xFF
        Log.d(TAG, "O2Ring response cmd=0x${"%02X".format(command)}, size=${packet.size}")

        // The O2Ring live data response has payload starting at byte 7.
        // Regardless of the command byte, try to extract SpO2 and HR if
        // the packet is large enough and the values look valid.
        if (packet.size >= 9) {
            val spO2 = packet[7].toInt() and 0xFF
            val hr = packet[8].toInt() and 0xFF
            Log.d(TAG, "O2Ring parsed: SpO2=$spO2%, HR=$hr bpm (cmd=0x${"%02X".format(command)})")
            if (spO2 in 50..100 && hr in 20..250) {
                emitReadings(spO2, hr)
                return
            }
        }

        Log.d(TAG, "O2Ring response cmd=0x${"%02X".format(command)} — no valid readings")
    }

    /** Parse OxySmart / PC-60F packets (header AA 55). */
    private fun handleOxySmartPacket(bytes: ByteArray) {
        if (bytes.size >= 3 && bytes[2].toInt() and 0xFF == 0xF0) {
            Log.d(TAG, "Keepalive packet — ignored")
            return
        }

        if (bytes.size >= 7 && bytes[4].toInt() and 0xFF == 0x01) {
            val spO2 = bytes[5].toInt() and 0xFF
            val hr   = bytes[6].toInt() and 0xFF
            Log.d(TAG, "OxySmart readings: SpO2=$spO2%  HR=$hr bpm")
            emitReadings(spO2, hr)
            return
        }

        if (bytes.size >= 5 && bytes[4].toInt() and 0xFF == 0x02) {
            Log.d(TAG, "Waveform packet — ignored")
            return
        }

        Log.d(TAG, "Unknown OxySmart packet type — ignored")
    }

    /**
     * Parse Wellue O2Ring / Viatom packets.
     *
     * Packet format:
     *   [0] 0xAA (header)
     *   [1] command byte
     *   [2] ~command (validation)
     *   [3..4] block number (little-endian)
     *   [5..6] data length (little-endian)
     *   [7..7+len-1] payload
     *   [last] CRC-8
     *
     * Command 0x17 response contains live SpO2, HR, and motion data.
     */
    private fun handleO2RingPacket(bytes: ByteArray) {
        if (bytes.size < 8) {
            Log.d(TAG, "O2Ring packet too short (${bytes.size} bytes) — ignored")
            return
        }

        val command = bytes[1].toInt() and 0xFF

        when (command) {
            O2RING_CMD_LIVE_DATA -> {
                // Live data response — payload starts at index 7
                if (bytes.size >= 12) {
                    val spO2 = bytes[7].toInt() and 0xFF
                    // HR can be 1 or 2 bytes depending on firmware
                    val hr = (bytes[8].toInt() and 0xFF) or
                             ((bytes[9].toInt() and 0xFF) shl 8)
                    val motion = bytes[10].toInt() and 0xFF
                    Log.d(TAG, "O2Ring live: SpO2=$spO2%, HR=$hr bpm, motion=$motion")

                    // Only emit valid readings (0 means no finger / no data)
                    if (spO2 in 1..100 && hr in 1..300) {
                        emitReadings(spO2, hr)
                    }
                }
            }
            O2RING_CMD_DEVICE_INFO -> {
                Log.d(TAG, "O2Ring device info response — acknowledged")
            }
            else -> {
                Log.d(TAG, "O2Ring command 0x${"%02X".format(command)} — ignored")
            }
        }
    }

    /** Emit SpO2 + HR readings to all consumers. */
    private fun emitReadings(spO2: Int, hr: Int) {
        _liveSpO2.value = spO2
        _liveHr.value = hr
        val reading = OximeterReading(spO2 = spO2, heartRateBpm = hr)
        scope.launch { _readings.emit(reading) }
    }

    // ── O2Ring command sending ────────────────────────────────────────────────

    /**
     * Build a Viatom/Wellue protocol packet.
     *
     * Format: AA | CMD | ~CMD | BLOCK(2) | LEN(2) | PAYLOAD(len) | CRC8
     */
    private fun buildO2RingPacket(cmd: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val invCmd = (cmd.toInt() xor 0xFF).toByte()
        val len = payload.size
        val packet = ByteArray(7 + len + 1) // header(1) + cmd(1) + inv(1) + block(2) + len(2) + payload + crc(1)
        packet[0] = 0xAA.toByte()
        packet[1] = cmd
        packet[2] = invCmd
        packet[3] = 0x00 // block low
        packet[4] = 0x00 // block high
        packet[5] = (len and 0xFF).toByte()
        packet[6] = ((len shr 8) and 0xFF).toByte()
        payload.copyInto(packet, 7)
        packet[packet.size - 1] = crc8(packet, 0, packet.size - 1)
        return packet
    }

    /** CRC-8 checksum used by the Viatom/Wellue protocol. */
    private fun crc8(data: ByteArray, offset: Int, length: Int): Byte {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc.toByte()
    }

    /** Send the "get live data" command (0x17) to the O2Ring. */
    private fun requestO2RingLiveData(gatt: BluetoothGatt) {
        val service = gatt.getService(O2RING_SERVICE_UUID) ?: return
        val writeChar = service.getCharacteristic(O2RING_WRITE_UUID) ?: return

        val packet = buildO2RingPacket(O2RING_CMD_LIVE_DATA.toByte())
        Log.d(TAG, "Sending O2Ring live data request: ${packet.joinToString(" ") { "%02X".format(it) }}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                writeChar,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            writeChar.value = packet
            @Suppress("DEPRECATION")
            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(writeChar)
        }
    }

    /**
     * Start periodic polling for live data from the O2Ring.
     * The ring doesn't push data automatically — we must request it.
     */
    private fun startO2RingPolling(gatt: BluetoothGatt) {
        o2RingPollJob?.cancel()
        o2RingPollJob = scope.launch {
            // Small delay to let notification subscriptions settle
            delay(O2RING_INITIAL_DELAY_MS)
            while (isActive) {
                synchronized(gattLock) {
                    if (bluetoothGatt === gatt) {
                        try { requestO2RingLiveData(gatt) } catch (e: Exception) {
                            Log.w(TAG, "O2Ring poll failed: ${e.message}")
                        }
                    }
                }
                delay(O2RING_POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "O2Ring polling started (interval=${O2RING_POLL_INTERVAL_MS}ms)")
    }

    companion object {
        private const val SCAN_TIMEOUT_MS      = 30_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 8_000L
        private const val BG_SCAN_WINDOW_MS    = 5_000L
        private const val BG_SCAN_PAUSE_MS     = 10_000L

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ── Wellue O2Ring / Viatom UUIDs ──────────────────────────────────────
        private val O2RING_SERVICE_UUID = UUID.fromString("14839ac4-7d7e-415c-9a42-167340cf2339")
        private val O2RING_WRITE_UUID   = UUID.fromString("8b00ace7-eb0b-49b0-bbe9-9aee0a26e1a3")
        // Notify UUID (subscribed automatically via the notify queue):
        // 0734594a-a8e7-4b1a-a6b1-cd5243059a57

        // ── O2Ring protocol commands ──────────────────────────────────────────
        private const val O2RING_CMD_LIVE_DATA  = 0x17
        private const val O2RING_CMD_DEVICE_INFO = 0x14

        // ── O2Ring polling timing ─────────────────────────────────────────────
        private const val O2RING_POLL_INTERVAL_MS = 2_000L
        private const val O2RING_INITIAL_DELAY_MS = 1_500L
    }
}
