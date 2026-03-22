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
 * ## Strategy (v4 — continuous scan-and-connect)
 *
 * The loop runs continuously whenever any device is disconnected:
 *
 * 1. **Immediate first attempt** — On start (and after every disconnect) we do
 *    one quick connect attempt for each saved device.
 *
 * 2. **Continuous Polar scan** — We call `connectToDevice()` for each saved
 *    Polar device on every loop iteration when it is not already
 *    connected/connecting.  The Polar SDK's internal reconnection handles the
 *    actual radio work; we just keep re-arming it so a *different* device that
 *    turns on later is also picked up.
 *
 * 3. **Background BLE scan (oximeter)** — For non-Polar devices we run a
 *    continuous low-power BLE scan filtered to known MAC addresses.  The moment
 *    the device is detected by the radio, we connect immediately.
 *
 * 4. **Short recheck when disconnected** — While any device is still
 *    disconnected the loop rechecks every [RECHECK_DISCONNECTED_MS] (5 s) so
 *    a newly-powered device is picked up quickly.  Once everything is connected
 *    the loop parks for [RECHECK_CONNECTED_MS] (30 s) to save battery.
 *
 * 5. **Session guard** — The loop suspends while [sessionActive] is true.
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
     * Prevents concurrent oximeter connect attempts.  The background-scan
     * [onKnownDeviceFound] callback can fire while [connectOximeterNow] is
     * already running from the main loop — without this guard both paths
     * call [OximeterBleManager.connect] nearly simultaneously, causing a
     * GATT race condition that crashes the app.
     */
    private val oximeterConnectMutex = Mutex()

    private var loopJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the persistent auto-connect loop.
     * Safe to call multiple times — a running loop is cancelled and restarted.
     */
    fun start() {
        // Wire disconnect callbacks
        polarBleManager.onDisconnected = { onDeviceDisconnected() }
        oximeterBleManager.onDisconnected = { onDeviceDisconnected() }

        // Wire background scan callback — when the oximeter is detected nearby,
        // immediately trigger a connect.  The mutex prevents this from racing
        // with an already-running connectOximeterNow() from the main loop.
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
            val h10History      = prefs.h10History
            val verityHistory   = prefs.verityHistory
            val oximeterHistory = prefs.oximeterHistory

            Log.d(TAG, "History — H10=$h10History Verity=$verityHistory Oximeter=$oximeterHistory")

            val hasAnySaved = h10History.isNotEmpty() || verityHistory.isNotEmpty() || oximeterHistory.isNotEmpty()
            if (!hasAnySaved) {
                Log.d(TAG, "No saved devices — waiting ${NO_DEVICE_WAIT_MS}ms")
                delay(NO_DEVICE_WAIT_MS)
                continue
            }

            // ── 3. Attempt Polar connections ──────────────────────────────────
            // Re-issue connectDevice() for every saved Polar device that is not
            // already connected or connecting.  The Polar SDK handles the actual
            // radio reconnection internally; calling connectDevice() again for a
            // *different* device (e.g. the user turned off H10 and turned on a
            // different one) ensures the SDK starts scanning for the new device.

            if (!sessionActive.get()) {
                val h10State = polarBleManager.h10State.value
                val needsH10Connect = h10History.isNotEmpty() &&
                    h10State !is BleConnectionState.Connected &&
                    h10State !is BleConnectionState.Connecting
                if (needsH10Connect) {
                    // Try each saved H10 in MRU order; stop as soon as one connects
                    for (deviceId in h10History) {
                        if (sessionActive.get()) break
                        Log.d(TAG, "Issuing Polar connect for H10: $deviceId")
                        polarBleManager.connectDevice(deviceId, isH10 = true)
                        delay(POLAR_CONNECT_SETTLE_MS)
                        if (polarBleManager.h10State.value is BleConnectionState.Connected) break
                    }
                }
            }

            if (!sessionActive.get()) {
                val verityState = polarBleManager.verityState.value
                val needsVerityConnect = verityHistory.isNotEmpty() &&
                    verityState !is BleConnectionState.Connected &&
                    verityState !is BleConnectionState.Connecting
                if (needsVerityConnect) {
                    for (deviceId in verityHistory) {
                        if (sessionActive.get()) break
                        Log.d(TAG, "Issuing Polar connect for Verity: $deviceId")
                        polarBleManager.connectDevice(deviceId, isH10 = false)
                        delay(POLAR_CONNECT_SETTLE_MS)
                        if (polarBleManager.verityState.value is BleConnectionState.Connected) break
                    }
                }
            }

            // ── 4. Attempt oximeter connection ────────────────────────────────
            // Try a direct connect for each known oximeter.  If none succeeds,
            // start the background scan so we detect it the moment it turns on.

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
                val h10Ok = h10History.isEmpty() ||
                    polarBleManager.h10State.value is BleConnectionState.Connected ||
                    polarBleManager.h10State.value is BleConnectionState.Connecting
                val verityOk = verityHistory.isEmpty() ||
                    polarBleManager.verityState.value is BleConnectionState.Connected ||
                    polarBleManager.verityState.value is BleConnectionState.Connecting
                val oxyOk = oximeterHistory.isEmpty() || oximeterConnected
                h10Ok && verityOk && oxyOk
            }

            // ── 7. Park — short interval when disconnected, long when all OK ──
            // When any device is still disconnected we recheck frequently so a
            // newly-powered device is picked up within a few seconds.
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
            // Subscribe BEFORE issuing the connect so we cannot miss the callback.
            val resultDeferred = scope.async {
                oximeterBleManager.connectionState
                    .filter { state ->
                        when (state) {
                            is OximeterConnectionState.Connected ->
                                state.deviceAddress.equals(address, ignoreCase = true)
                            is OximeterConnectionState.Error        -> true
                            is OximeterConnectionState.Disconnected -> true  // failed connect → back to disconnected
                            is OximeterConnectionState.Connecting   -> false // still in progress
                            is OximeterConnectionState.Scanning     -> false // still in progress
                        }
                    }
                    .first()
            }

            // Issue the connect (scan-then-connect).
            oximeterBleManager.connectWithScan(address)

            // Wait for terminal state.
            val terminal = resultDeferred.await()
            terminal is OximeterConnectionState.Connected
        } ?: false
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * Oximeter connect timeout.  Covers the 8s scan window + GATT connect.
         */
        private const val OXIMETER_CONNECT_TIMEOUT_MS = 20_000L

        /** Brief pause after issuing a Polar connect to let the SDK settle. */
        private const val POLAR_CONNECT_SETTLE_MS = 2_000L

        /** How often to check for early-wake signals. */
        private const val WAKE_CHECK_INTERVAL_MS = 500L

        /** Poll interval while a session is active. */
        private const val SESSION_POLL_MS = 1_000L

        /** How long to wait before rechecking when no devices are saved. */
        private const val NO_DEVICE_WAIT_MS = 30_000L

        /**
         * Recheck interval when at least one device is still disconnected.
         * Short so a newly-powered device is picked up within a few seconds.
         */
        private const val RECHECK_DISCONNECTED_MS = 5_000L

        /**
         * Recheck interval when all saved devices are connected/connecting.
         * Longer to save battery — disconnect callbacks will wake us early anyway.
         */
        private const val RECHECK_CONNECTED_MS = 30_000L
    }
}
