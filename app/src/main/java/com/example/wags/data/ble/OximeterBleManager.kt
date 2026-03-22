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
import com.example.wags.domain.model.OximeterConnectionState
import com.example.wags.domain.model.OximeterReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OximeterBLE"

/**
 * BLE manager for PC-60F / Viatom / Wellue / OxySmart pulse oximeters.
 *
 * Protocol: Nordic UART Service (NUS) — all data arrives on the TX characteristic
 * (6E400003). Packets always start with AA 55. The readings packet has byte[4]==0x01:
 *
 *   AA 55 0F 08 01 [SpO2] [HR] 00 [PI*10] 00 [Status] [CRC]
 *   index:  0  1  2  3  4    5      6   7     8      9    10     11
 *
 * Waveform packets (byte[4]==0x02) and keepalive packets (byte[2]==0xF0) are ignored.
 */
@Singleton
class OximeterBleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Invoked whenever the oximeter disconnects. Set by AutoConnectManager. */
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
        MutableStateFlow<OximeterConnectionState>(OximeterConnectionState.Disconnected)
    val connectionState: StateFlow<OximeterConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<OximeterReading>(replay = 1, extraBufferCapacity = 64)
    val readings: SharedFlow<OximeterReading> = _readings.asSharedFlow()

    /** Live heart rate from the oximeter, null when not connected or no data yet. */
    private val _liveHr = MutableStateFlow<Int?>(null)
    val liveHr: StateFlow<Int?> = _liveHr.asStateFlow()

    /** Live SpO₂ from the oximeter, null when not connected or no data yet. */
    private val _liveSpO2 = MutableStateFlow<Int?>(null)
    val liveSpO2: StateFlow<Int?> = _liveSpO2.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    /**
     * Guards all access to [bluetoothGatt] so that concurrent connect / disconnect
     * calls from the AutoConnectManager loop, background-scan callback, and GATT
     * Binder thread cannot race against each other.
     */
    private val gattLock = Any()

    /** Job for the UI-initiated scan (startScan / stopScan). */
    private var scanJob: Job? = null
    /** Job for the auto-connect scan-then-connect sequence. Never cancelled by stopScan(). */
    private var autoConnectJob: Job? = null
    /** Job for the persistent background scan. */
    private var backgroundScanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Queue of characteristics still needing CCCD writes (BLE requires sequential descriptor writes)
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var notifyQueueBusy = false

    // ── UI Scan ───────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        // Cancel any previous UI scan job but keep a fresh callback instance so
        // stopScan(uiScanCallback) never accidentally kills a background scan.
        scanJob?.cancel()
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(uiScanCallback) } catch (_: Exception) {}
        _scanResults.value = emptyList()
        _connectionState.value = OximeterConnectionState.Scanning(0)

        scanJob = scope.launch {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, uiScanCallback)
            delay(SCAN_TIMEOUT_MS)
            try { scanner.stopScan(uiScanCallback) } catch (_: Exception) {}
            if (_connectionState.value is OximeterConnectionState.Scanning) {
                _connectionState.value = OximeterConnectionState.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(uiScanCallback) } catch (_: Exception) {}
        if (_connectionState.value is OximeterConnectionState.Scanning) {
            _connectionState.value = OximeterConnectionState.Disconnected
        }
    }

    /**
     * Dedicated callback for the user-initiated scan (Scan button).
     * Kept separate from the background-scan callback so [stopScan] never
     * accidentally kills an in-progress background scan.
     *
     * Polar devices (name starts with "Polar ") are excluded — they are handled
     * exclusively by [PolarBleManager] and must never appear in the oximeter list.
     */
    private val uiScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name?.takeIf { it.isNotBlank() } ?: return
            // Exclude Polar devices — they belong in the Polar section only
            if (name.startsWith("Polar ", ignoreCase = true)) return
            val current = _scanResults.value.toMutableList()
            if (current.none { it.device.address == result.device.address }) {
                Log.d(TAG, "Found BLE device: $name  ${result.device.address}")
                current.add(result)
                _scanResults.value = current
                _connectionState.value = OximeterConnectionState.Scanning(current.size)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _connectionState.value = OximeterConnectionState.Error("Scan failed: $errorCode")
        }
    }

    // Keep the old name as an alias so existing call-sites in connect() still compile
    private val scanCallback get() = uiScanCallback

    // ── Background scan ───────────────────────────────────────────────────────

    /**
     * Starts a persistent low-power background BLE scan filtered to [addresses].
     * When any of the target devices is detected, [onKnownDeviceFound] is invoked
     * with the matching address so the AutoConnectManager can trigger an immediate
     * connect.
     *
     * The scan runs in cycles: [BG_SCAN_WINDOW_MS] on, [BG_SCAN_PAUSE_MS] off,
     * to balance responsiveness with battery usage.
     *
     * Call [stopBackgroundScan] to stop.
     */
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

                // If we found a device, stop scanning — AutoConnectManager will
                // restart us after the connect attempt if needed.
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

    /**
     * Direct GATT connect — works reliably for bonded devices or when the BLE
     * radio has recently seen the device (e.g. after a manual scan).
     * Only cancels the UI scan job, never the auto-connect job.
     */
    fun connect(deviceAddress: String) {
        // Stop UI scan only — do NOT cancel autoConnectJob
        scanJob?.cancel()
        scanJob = null
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (_connectionState.value is OximeterConnectionState.Scanning) {
            _connectionState.value = OximeterConnectionState.Disconnected
        }

        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: run {
            _connectionState.value = OximeterConnectionState.Error("Invalid address: $deviceAddress")
            return
        }

        synchronized(gattLock) {
            // If already connected/connecting to this address, skip the redundant attempt
            val currentState = _connectionState.value
            if (currentState is OximeterConnectionState.Connected &&
                currentState.deviceAddress.equals(deviceAddress, ignoreCase = true)
            ) {
                Log.d(TAG, "Already connected to $deviceAddress — skipping")
                return
            }

            // Close any previous GATT instance before opening a new one.
            // Without this, a second concurrent connect() overwrites bluetoothGatt
            // and the old GATT's disconnect callback closes the *new* GATT → crash.
            bluetoothGatt?.let { oldGatt ->
                Log.d(TAG, "Closing stale GATT before new connect")
                try { oldGatt.disconnect() } catch (_: Exception) {}
                try { oldGatt.close() } catch (_: Exception) {}
                bluetoothGatt = null
            }

            Log.d(TAG, "Connecting to $deviceAddress")
            _connectionState.value = OximeterConnectionState.Connecting(deviceAddress)
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    /**
     * Scan-then-connect for the auto-connect loop.
     *
     * Runs a brief BLE scan filtered to [deviceAddress] using a **separate job**
     * that is never cancelled by [stopScan] (which only affects the UI scan).
     * As soon as the device is seen, stops the scan and calls [connect].
     * If the device is not seen within [AUTO_SCAN_TIMEOUT_MS], falls back to a
     * direct [connect] call anyway (works for bonded/cached devices).
     */
    fun connectWithScan(deviceAddress: String) {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            connect(deviceAddress)
            return
        }

        // Cancel any previous auto-connect attempt (not the UI scan)
        autoConnectJob?.cancel()
        Log.d(TAG, "Auto-scan for $deviceAddress …")

        autoConnectJob = scope.launch {
            // Flag to signal the scan found the device — wakes the delay without
            // cancelling the job (so connect() still runs).
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
                    deviceFound.set(true)  // wake the poll loop
                }
            }

            val filters = listOf(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(filters, settings, targetCallback)
            try {
                // Poll instead of delay so we can wake early without cancelling the job
                val deadline = System.currentTimeMillis() + AUTO_SCAN_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline && !deviceFound.get()) {
                    delay(100)
                }
            } finally {
                scanner.stopScan(targetCallback)
            }

            // Connect regardless of whether we found it via scan or timed out
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
                    _connectionState.value = OximeterConnectionState.Connecting(gatt.device.address)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    synchronized(gattLock) {
                        // Only clean up if this callback belongs to the *current* GATT.
                        // A stale GATT from a previous connect attempt must not null-out
                        // the field that now points to a newer, valid GATT instance.
                        val isCurrentGatt = (gatt === bluetoothGatt)
                        if (isCurrentGatt) {
                            // Use Error state for failed connection attempts (status != 0)
                            // so AutoConnectManager can distinguish failure from idle disconnect.
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                _connectionState.value = OximeterConnectionState.Error(
                                    "Connection failed (status=$status)"
                                )
                            } else {
                                _connectionState.value = OximeterConnectionState.Disconnected
                            }
                            _liveHr.value = null
                            _liveSpO2.value = null
                            bluetoothGatt = null
                        } else {
                            Log.d(TAG, "Ignoring disconnect from stale GATT instance")
                        }
                    }
                    // Always close the gatt instance that fired the callback
                    try { gatt.close() } catch (_: Exception) {}
                    // Notify AutoConnectManager so it can re-arm immediately
                    // (only if this was the current GATT — checked above)
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
                _connectionState.value = OximeterConnectionState.Error("Service discovery failed: $status")
                return
            }

            // Log every service + characteristic for debugging
            gatt.services.forEach { svc ->
                Log.d(TAG, "Service: ${svc.uuid}")
                svc.characteristics.forEach { chr ->
                    Log.d(TAG, "  Char: ${chr.uuid}  props=0x${chr.properties.toString(16)}")
                }
            }

            // Enqueue ALL notifiable/indicatable characteristics across all services
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

            _connectionState.value = OximeterConnectionState.Connected(
                gatt.device.address,
                gatt.device.name ?: "Oximeter"
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

        // API 33+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(value)
        }

        // API < 33 fallback
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

        // All packets from this device start with AA 55
        if (bytes.size < 2 || bytes[0].toInt() and 0xFF != 0xAA || bytes[1].toInt() and 0xFF != 0x55) {
            Log.d(TAG, "Skipping non-AA55 packet")
            return
        }

        // Keepalive / status packet: AA 55 F0 ...  — ignore
        if (bytes.size >= 3 && bytes[2].toInt() and 0xFF == 0xF0) {
            Log.d(TAG, "Keepalive packet — ignored")
            return
        }

        // Readings packet: AA 55 0F 08 01 [SpO2] [HR] 00 [PI*10] 00 [Status] [CRC]
        // byte[4] == 0x01 identifies this as the readings payload
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

        // Waveform packet: byte[4] == 0x02 — raw pleth data, not needed for HR/SpO2
        if (bytes.size >= 5 && bytes[4].toInt() and 0xFF == 0x02) {
            Log.d(TAG, "Waveform packet — ignored")
            return
        }

        Log.d(TAG, "Unknown packet type — ignored")
    }

    companion object {
        private const val SCAN_TIMEOUT_MS      = 30_000L
        /** How long to scan for the target device before falling back to direct connect. */
        private const val AUTO_SCAN_TIMEOUT_MS = 8_000L
        /** Background scan window duration. */
        private const val BG_SCAN_WINDOW_MS    = 5_000L
        /** Pause between background scan windows. */
        private const val BG_SCAN_PAUSE_MS     = 10_000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
