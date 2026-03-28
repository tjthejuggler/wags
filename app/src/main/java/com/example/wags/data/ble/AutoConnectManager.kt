package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AutoConnect"

/**
 * Orchestrates automatic BLE reconnection whenever the app is running but no
 * session is active.
 *
 * ## Strategy (v7 — unified device history)
 *
 * All devices (Polar, oximeter, generic BLE) are stored in a single unified
 * history list in [DevicePreferencesRepository]. The loop cycles through this
 * list and tries to connect each device until one succeeds.
 *
 * 1. **Get unified device history** — single ordered list of all saved devices
 * 2. **Cycle through devices** — for each, route to Polar SDK or generic GATT
 *    based on the `isPolar` flag stored with the device
 * 3. **Stop on first success** — once a device connects, stop trying others
 * 4. **On disconnect** — restart the loop immediately
 * 5. **Background scan** — for non-Polar devices, run a low-power background
 *    scan when no device is connected
 *
 * Call [start] once after Bluetooth permissions are granted.
 * Call [setSessionActive] to pause/resume around sessions.
 */
@Singleton
class AutoConnectManager @Inject constructor(
    private val prefs: DevicePreferencesRepository,
    private val deviceManager: UnifiedDeviceManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True while a session (readiness, meditation, apnea, breathing, …) is running. */
    private val sessionActive = AtomicBoolean(false)

    @Volatile private var reconnectNow = false

    private val connectMutex = Mutex()
    private var loopJob: Job? = null
    private var connectingSince: Long = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        // Wire disconnect callbacks
        deviceManager.polarBleManager.onDisconnected = { onDeviceDisconnected() }
        deviceManager.genericBleManager.onDisconnected = { onDeviceDisconnected() }

        // Wire background scan callback for generic devices
        deviceManager.genericBleManager.onKnownDeviceFound = { address ->
            Log.d(TAG, "Background scan found device $address — triggering connect")
            scope.launch {
                if (connectMutex.tryLock()) {
                    try {
                        connectGenericNow(address)
                    } finally {
                        connectMutex.unlock()
                    }
                } else {
                    Log.d(TAG, "Connect already in progress — skipping background trigger")
                }
            }
        }

        loopJob?.cancel()
        loopJob = scope.launch { mainLoop() }
        Log.d(TAG, "AutoConnectManager started")
    }

    fun setSessionActive(active: Boolean) {
        val wasActive = sessionActive.getAndSet(active)
        if (wasActive && !active) {
            Log.d(TAG, "Session ended — triggering immediate reconnect check")
            reconnectNow = true
        }
    }

    fun onDeviceDisconnected() {
        if (!sessionActive.get()) {
            Log.d(TAG, "Device disconnected — waking reconnect loop")
            reconnectNow = true
        }
    }

    fun cancel() {
        loopJob?.cancel()
        deviceManager.genericBleManager.stopBackgroundScan()
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    private suspend fun mainLoop() {
        Log.d(TAG, "Main loop started")

        while (coroutineContext.isActive) {

            // ── 1. Wait while a session is running ────────────────────────────
            if (sessionActive.get()) {
                Log.d(TAG, "Session active — pausing auto-connect + background scan")
                deviceManager.genericBleManager.stopBackgroundScan()
                while (sessionActive.get() && coroutineContext.isActive) delay(SESSION_POLL_MS)
                Log.d(TAG, "Session ended — resuming")
            }

            // ── 2. Get unified device history ─────────────────────────────────
            val history = prefs.deviceHistory

            Log.d(TAG, "Device history: ${history.map { "${it.identifier}(polar=${it.isPolar})" }}")

            if (history.isEmpty()) {
                Log.d(TAG, "No saved devices — waiting ${NO_DEVICE_WAIT_MS}ms")
                delay(NO_DEVICE_WAIT_MS)
                continue
            }

            // ── 3. Check if already connected ─────────────────────────────────
            val alreadyConnected = deviceManager.connectionState.value is BleConnectionState.Connected

            // ── 4. Attempt connections if not connected ───────────────────────
            var connected = alreadyConnected

            if (!connected && !sessionActive.get()) {
                val isConnecting = deviceManager.connectionState.value is BleConnectionState.Connecting
                val now = System.currentTimeMillis()

                // Check for stale Connecting state
                val staleConnecting = isConnecting &&
                    connectingSince > 0 &&
                    (now - connectingSince) > CONNECTING_TIMEOUT_MS

                if (staleConnecting) {
                    Log.d(TAG, "Stuck in Connecting for >${CONNECTING_TIMEOUT_MS}ms — disconnecting to retry")
                    deviceManager.disconnect()
                    delay(500)
                    connectingSince = 0L
                }

                val needsConnect = !isConnecting || staleConnecting

                if (needsConnect) {
                    connectMutex.withLock {
                        for (device in history) {
                            if (sessionActive.get()) break
                            if (deviceManager.connectionState.value is BleConnectionState.Connected) {
                                connected = true
                                break
                            }

                            Log.d(TAG, "Trying device: ${device.identifier} (polar=${device.isPolar})")

                            if (device.isPolar) {
                                connected = connectPolarNow(device.identifier)
                            } else {
                                connected = connectGenericNow(device.identifier)
                            }

                            if (connected) {
                                Log.d(TAG, "Connected to ${device.identifier}")
                                connectingSince = 0L
                                break
                            }
                        }
                    }
                }
            }

            // ── 5. Start background scan if not connected ─────────────────────
            val genericAddresses = history.filter { !it.isPolar }.map { it.identifier }
            if (!connected && genericAddresses.isNotEmpty() && !sessionActive.get()) {
                Log.d(TAG, "Not connected — starting background scan for generic devices")
                deviceManager.genericBleManager.startBackgroundScan(genericAddresses)
            } else {
                deviceManager.genericBleManager.stopBackgroundScan()
            }

            // ── 6. Park — short interval when disconnected, long when connected
            val recheckMs = if (connected) RECHECK_CONNECTED_MS else RECHECK_DISCONNECTED_MS
            Log.d(TAG, "connected=$connected — rechecking in ${recheckMs}ms")
            reconnectNow = false
            val deadline = System.currentTimeMillis() + recheckMs
            while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
                if (reconnectNow || sessionActive.get()) break
                delay(WAKE_CHECK_INTERVAL_MS)
            }
            reconnectNow = false
        }
    }

    // ── Connect helpers ───────────────────────────────────────────────────────

    private suspend fun connectPolarNow(deviceId: String): Boolean {
        val polarManager = deviceManager.polarBleManager
        // Already connected?
        val currentState = polarManager.connectionState.value
        if (currentState is BleConnectionState.Connected && currentState.deviceId == deviceId) {
            return true
        }

        polarManager.connectDevice(deviceId)
        connectingSince = System.currentTimeMillis()
        delay(POLAR_CONNECT_SETTLE_MS)

        val state = polarManager.connectionState.value
        val connected = state is BleConnectionState.Connected

        // If the Polar device didn't connect in time, explicitly disconnect
        // to cancel the SDK's background connection attempt. Without this,
        // the Polar SDK keeps trying to connect and may fire callbacks later
        // that conflict with a generic device connection.
        if (!connected) {
            Log.d(TAG, "Polar $deviceId didn't connect in time — cancelling SDK attempt")
            polarManager.disconnect()
        }

        return connected
    }

    private suspend fun connectGenericNow(address: String): Boolean {
        val genericManager = deviceManager.genericBleManager
        // Already connected?
        genericManager.connectionState.value.let {
            if (it is BleConnectionState.Connected &&
                it.deviceId.equals(address, ignoreCase = true)
            ) return true
        }

        genericManager.stopBackgroundScan()

        // Ensure no stale Polar SDK connection attempt is running before
        // we try to connect a generic device. The Polar SDK's connectToDevice
        // is fire-and-forget and can interfere with the BLE stack.
        val polarState = deviceManager.polarBleManager.connectionState.value
        if (polarState !is BleConnectionState.Connected) {
            deviceManager.polarBleManager.disconnect()
        }

        return withTimeoutOrNull(GENERIC_CONNECT_TIMEOUT_MS) {
            val resultDeferred = scope.async {
                genericManager.connectionState
                    .filter { state ->
                        when (state) {
                            is BleConnectionState.Connected ->
                                state.deviceId.equals(address, ignoreCase = true)
                            is BleConnectionState.Error -> true
                            is BleConnectionState.Disconnected -> true
                            is BleConnectionState.Connecting -> false
                            is BleConnectionState.Scanning -> false
                        }
                    }
                    .first()
            }

            genericManager.connectWithScan(address)

            val terminal = resultDeferred.await()
            terminal is BleConnectionState.Connected
        } ?: false
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val GENERIC_CONNECT_TIMEOUT_MS = 20_000L
        private const val POLAR_CONNECT_SETTLE_MS = 2_000L
        private const val CONNECTING_TIMEOUT_MS = 30_000L
        private const val WAKE_CHECK_INTERVAL_MS = 500L
        private const val SESSION_POLL_MS = 1_000L
        private const val NO_DEVICE_WAIT_MS = 30_000L
        private const val RECHECK_DISCONNECTED_MS = 5_000L
        private const val RECHECK_CONNECTED_MS = 30_000L
    }
}
