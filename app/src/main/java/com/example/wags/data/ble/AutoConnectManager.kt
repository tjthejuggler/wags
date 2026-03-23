package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
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
 * ## Strategy (v6 — unified Polar + name-based identification)
 *
 * Polar devices are stored in a single unified history list. The device type
 * (H10 vs Verity Sense) is determined automatically from the device name
 * after connection by [PolarBleManager].
 *
 * The loop runs continuously whenever any device is disconnected:
 *
 * 1. **Polar reconnect** — For each saved Polar device ID, call `connectDevice()`
 *    (no isH10 flag needed). The PolarBleManager routes the device to the
 *    correct slot (h10State or verityState) based on its advertised name.
 *
 * 2. **Oximeter reconnect** — Same as before: direct connect + background scan.
 *
 * 3. **Short recheck when disconnected** — 5s interval. Long recheck when all
 *    connected — 30s.
 *
 * Call [start] once after Bluetooth permissions are granted.
 * Call [setSessionActive] to pause/resume around sessions.
 */
@Singleton
class AutoConnectManager @Inject constructor(
    private val prefs: DevicePreferencesRepository,
    private val polarBleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True while a session (readiness, meditation, apnea, breathing, …) is running. */
    private val sessionActive = AtomicBoolean(false)

    /**
     * Flipped to true by [onDeviceDisconnected] or [setSessionActive] to wake
     * the main loop immediately instead of waiting for the recheck interval.
     */
    @Volatile private var reconnectNow = false

    /**
     * Prevents concurrent oximeter connect attempts.
     */
    private val oximeterConnectMutex = Mutex()

    private var loopJob: Job? = null

    /** Tracks when the Polar connect attempt started so we can detect stale connections. */
    private var polarConnectingSince: Long = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the persistent auto-connect loop.
     * Safe to call multiple times — a running loop is cancelled and restarted.
     */
    fun start() {
        // Wire disconnect callbacks
        polarBleManager.onDisconnected = { onDeviceDisconnected() }
        oximeterBleManager.onDisconnected = { onDeviceDisconnected() }

        // Wire background scan callback for oximeter
        oximeterBleManager.onKnownDeviceFound = { address ->
            Log.d(TAG, "Background scan found oximeter $address — triggering connect")
            scope.launch {
                if (oximeterConnectMutex.tryLock()) {
                    try {
                        connectOximeterNow(address)
                    } finally {
                        oximeterConnectMutex.unlock()
                    }
                } else {
                    Log.d(TAG, "Oximeter connect already in progress — skipping background trigger")
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
        oximeterBleManager.stopBackgroundScan()
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    private suspend fun mainLoop() {
        Log.d(TAG, "Main loop started")

        while (coroutineContext.isActive) {

            // ── 1. Wait while a session is running ────────────────────────────
            if (sessionActive.get()) {
                Log.d(TAG, "Session active — pausing auto-connect + background scan")
                oximeterBleManager.stopBackgroundScan()
                while (sessionActive.get() && coroutineContext.isActive) delay(SESSION_POLL_MS)
                Log.d(TAG, "Session ended — resuming")
            }

            // ── 2. Build candidate lists ──────────────────────────────────────
            val polarHistory    = prefs.polarHistory
            val oximeterHistory = prefs.oximeterHistory

            Log.d(TAG, "History — Polar=$polarHistory Oximeter=$oximeterHistory")

            val hasAnySaved = polarHistory.isNotEmpty() || oximeterHistory.isNotEmpty()
            if (!hasAnySaved) {
                Log.d(TAG, "No saved devices — waiting ${NO_DEVICE_WAIT_MS}ms")
                delay(NO_DEVICE_WAIT_MS)
                continue
            }

            // ── 3. Attempt Polar connections ──────────────────────────────────
            // Try to connect each saved Polar device. The PolarBleManager will
            // auto-detect whether it's an H10 or Verity Sense from the name.
            if (!sessionActive.get() && polarHistory.isNotEmpty()) {
                val h10State = polarBleManager.h10State.value
                val verityState = polarBleManager.verityState.value
                val anyPolarConnected = h10State is BleConnectionState.Connected ||
                    verityState is BleConnectionState.Connected
                val anyPolarConnecting = h10State is BleConnectionState.Connecting ||
                    verityState is BleConnectionState.Connecting

                val now = System.currentTimeMillis()

                // Check for stale Connecting state
                val staleConnecting = anyPolarConnecting &&
                    polarConnectingSince > 0 &&
                    (now - polarConnectingSince) > POLAR_CONNECTING_TIMEOUT_MS

                if (staleConnecting) {
                    Log.d(TAG, "Polar stuck in Connecting for >${POLAR_CONNECTING_TIMEOUT_MS}ms — disconnecting to retry")
                    // Disconnect whichever is stuck
                    if (h10State is BleConnectionState.Connecting) {
                        polarBleManager.disconnectDevice(h10State.deviceId)
                    }
                    if (verityState is BleConnectionState.Connecting) {
                        polarBleManager.disconnectDevice(verityState.deviceId)
                    }
                    delay(500)
                    polarConnectingSince = 0L
                }

                // If no Polar device is connected or connecting, try each saved device
                val needsConnect = !anyPolarConnected &&
                    !(anyPolarConnecting && !staleConnecting)

                if (needsConnect) {
                    for (deviceId in polarHistory) {
                        if (sessionActive.get()) break
                        Log.d(TAG, "Issuing Polar connect: $deviceId")
                        polarBleManager.connectDevice(deviceId)
                        polarConnectingSince = System.currentTimeMillis()
                        delay(POLAR_CONNECT_SETTLE_MS)
                        // Check if any Polar device connected
                        val h10Now = polarBleManager.h10State.value
                        val verityNow = polarBleManager.verityState.value
                        if (h10Now is BleConnectionState.Connected ||
                            verityNow is BleConnectionState.Connected) {
                            polarConnectingSince = 0L
                            break
                        }
                    }
                } else if (anyPolarConnected) {
                    polarConnectingSince = 0L
                }
            }

            // ── 4. Attempt oximeter connection ────────────────────────────────
            var oximeterConnected = oximeterBleManager.connectionState.value is OximeterConnectionState.Connected

            if (!oximeterConnected && !sessionActive.get() && oximeterHistory.isNotEmpty()) {
                oximeterConnectMutex.withLock {
                    for (address in oximeterHistory) {
                        if (sessionActive.get()) break
                        Log.d(TAG, "Trying oximeter: $address")
                        oximeterConnected = connectOximeterNow(address)
                        if (oximeterConnected) {
                            Log.d(TAG, "Oximeter connected: $address")
                            break
                        }
                    }
                }
            }

            // ── 5. Start background scan if oximeter not connected ────────────
            if (!oximeterConnected && oximeterHistory.isNotEmpty() && !sessionActive.get()) {
                Log.d(TAG, "Oximeter not connected — starting background scan")
                oximeterBleManager.startBackgroundScan(oximeterHistory)
            } else {
                oximeterBleManager.stopBackgroundScan()
            }

            // ── 6. Determine whether everything is connected ──────────────────
            val allConnected = run {
                val polarOk = polarHistory.isEmpty() ||
                    polarBleManager.h10State.value is BleConnectionState.Connected ||
                    polarBleManager.verityState.value is BleConnectionState.Connected
                val oxyOk = oximeterHistory.isEmpty() || oximeterConnected
                polarOk && oxyOk
            }

            // ── 7. Park — short interval when disconnected, long when all OK ──
            val recheckMs = if (allConnected) RECHECK_CONNECTED_MS else RECHECK_DISCONNECTED_MS
            Log.d(TAG, "allConnected=$allConnected — rechecking in ${recheckMs}ms")
            reconnectNow = false
            val deadline = System.currentTimeMillis() + recheckMs
            while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
                if (reconnectNow || sessionActive.get()) break
                delay(WAKE_CHECK_INTERVAL_MS)
            }
            reconnectNow = false
        }
    }

    // ── Oximeter connect helper ───────────────────────────────────────────────

    /**
     * Attempts a scan-then-connect for the oximeter and waits for a terminal
     * state.  Returns true if connected, false otherwise.
     */
    private suspend fun connectOximeterNow(address: String): Boolean {
        // Already connected?
        oximeterBleManager.connectionState.value.let {
            if (it is OximeterConnectionState.Connected &&
                it.deviceAddress.equals(address, ignoreCase = true)
            ) return true
        }

        // Stop background scan during active connect attempt
        oximeterBleManager.stopBackgroundScan()

        return withTimeoutOrNull(OXIMETER_CONNECT_TIMEOUT_MS) {
            val resultDeferred = scope.async {
                oximeterBleManager.connectionState
                    .filter { state ->
                        when (state) {
                            is OximeterConnectionState.Connected ->
                                state.deviceAddress.equals(address, ignoreCase = true)
                            is OximeterConnectionState.Error        -> true
                            is OximeterConnectionState.Disconnected -> true
                            is OximeterConnectionState.Connecting   -> false
                            is OximeterConnectionState.Scanning     -> false
                        }
                    }
                    .first()
            }

            oximeterBleManager.connectWithScan(address)

            val terminal = resultDeferred.await()
            terminal is OximeterConnectionState.Connected
        } ?: false
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val OXIMETER_CONNECT_TIMEOUT_MS = 20_000L
        private const val POLAR_CONNECT_SETTLE_MS = 2_000L
        private const val POLAR_CONNECTING_TIMEOUT_MS = 30_000L
        private const val WAKE_CHECK_INTERVAL_MS = 500L
        private const val SESSION_POLL_MS = 1_000L
        private const val NO_DEVICE_WAIT_MS = 30_000L
        private const val RECHECK_DISCONNECTED_MS = 5_000L
        private const val RECHECK_CONNECTED_MS = 30_000L
    }
}
