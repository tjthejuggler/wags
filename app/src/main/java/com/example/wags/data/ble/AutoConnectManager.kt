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

private const val TAG = "AutoConnect"

/**
 * Orchestrates automatic BLE reconnection whenever the app is running but no
 * session is active.
 *
 * ## Strategy (v3 — background-scan driven)
 *
 * The previous approach was poll-and-retry with exponential backoff: try to
 * connect, fail, wait 10s → 15s → 22s → … → 120s, try again.  This meant the
 * user could wait up to **2 minutes** after turning on a device before the app
 * noticed.
 *
 * The new approach:
 *
 * 1. **Immediate first attempt** — On start (and after every disconnect) we do
 *    one quick connect attempt for each saved device.
 *
 * 2. **Background BLE scan** — For the oximeter (non-Polar devices), we run a
 *    continuous low-power BLE scan filtered to known MAC addresses.  The moment
 *    the device is detected by the radio, we connect immediately.  This gives
 *    sub-second response time.
 *
 * 3. **Polar auto-connect** — The Polar SDK has its own internal reconnection
 *    mechanism.  Once we call `connectToDevice()`, the SDK keeps trying in the
 *    background.  We just need to call it once and monitor the state.
 *
 * 4. **Session guard** — The loop suspends while [sessionActive] is true.
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
     * the main loop immediately.
     */
    @Volatile private var reconnectNow = false

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
        // immediately trigger a connect.
        oximeterBleManager.onKnownDeviceFound = { address ->
            Log.d(TAG, "Background scan found oximeter $address — triggering connect")
            scope.launch { connectOximeterNow(address) }
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
            // The Polar SDK has built-in reconnection: once we call connectToDevice(),
            // it keeps trying internally.  We just need to issue the call once for
            // each device that isn't already connected/connecting.

            if (!sessionActive.get()) {
                for (deviceId in h10History) {
                    val state = polarBleManager.h10State.value
                    if (state is BleConnectionState.Connected || state is BleConnectionState.Connecting) break
                    Log.d(TAG, "Issuing Polar connect for H10: $deviceId")
                    polarBleManager.connectDevice(deviceId, isH10 = true)
                    // Give the SDK a moment to start its internal reconnection
                    delay(POLAR_CONNECT_SETTLE_MS)
                    // Check if it connected
                    if (polarBleManager.h10State.value is BleConnectionState.Connected) break
                }
            }

            if (!sessionActive.get()) {
                for (deviceId in verityHistory) {
                    val state = polarBleManager.verityState.value
                    if (state is BleConnectionState.Connected || state is BleConnectionState.Connecting) break
                    Log.d(TAG, "Issuing Polar connect for Verity: $deviceId")
                    polarBleManager.connectDevice(deviceId, isH10 = false)
                    delay(POLAR_CONNECT_SETTLE_MS)
                    if (polarBleManager.verityState.value is BleConnectionState.Connected) break
                }
            }

            // ── 4. Attempt oximeter connection ────────────────────────────────
            // Try a direct connect for each known oximeter.  If none succeeds,
            // start the background scan so we detect it the moment it turns on.

            var oximeterConnected = oximeterBleManager.connectionState.value is OximeterConnectionState.Connected

            if (!oximeterConnected && !sessionActive.get() && oximeterHistory.isNotEmpty()) {
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

            // ── 5. Start background scan if oximeter not connected ────────────
            if (!oximeterConnected && oximeterHistory.isNotEmpty() && !sessionActive.get()) {
                Log.d(TAG, "Oximeter not connected — starting background scan")
                oximeterBleManager.startBackgroundScan(oximeterHistory)
            } else {
                oximeterBleManager.stopBackgroundScan()
            }

            // ── 6. Park until something changes ──────────────────────────────
            // We wait here until:
            //   - A device disconnects (reconnectNow = true)
            //   - A session starts/ends
            //   - The background scan finds a device (handled via callback)
            //   - Periodic recheck interval expires
            Log.d(TAG, "Parking — will recheck in ${RECHECK_INTERVAL_MS}ms or on event")
            reconnectNow = false
            val deadline = System.currentTimeMillis() + RECHECK_INTERVAL_MS
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
                            is OximeterConnectionState.Error       -> true
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
         * Periodic recheck interval.  Even with background scan, we recheck
         * periodically in case the Polar SDK's internal reconnection stalled
         * or the background scan needs restarting.
         */
        private const val RECHECK_INTERVAL_MS = 30_000L
    }
}
