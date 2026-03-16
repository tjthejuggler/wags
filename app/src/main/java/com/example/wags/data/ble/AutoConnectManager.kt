package com.example.wags.data.ble

import android.util.Log
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "AutoConnect"

/**
 * Orchestrates automatic BLE connection whenever the app is running but no session
 * is active.
 *
 * Strategy:
 * 1. Build the list of all previously-used devices from saved preferences
 *    (H10, Verity Sense, Oximeter — whichever have been connected before).
 * 2. For every candidate that is NOT already connected, attempt to connect it.
 *    All candidates are tried every round — so if the H10 AND the Verity are both
 *    powered on, both will be connected in the same pass.
 * 3. After the round, if any candidate is still disconnected, wait [retryDelay]
 *    (exponential backoff, capped at [MAX_RETRY_DELAY_MS]) then try again — forever.
 * 4. The loop is suspended while [sessionActive] is true so it never interferes
 *    with an in-progress readiness test, meditation, apnea session, etc.
 * 5. Call [onDeviceDisconnected] from BLE managers whenever a device drops so the
 *    loop re-arms immediately instead of waiting for the next scheduled retry.
 *
 * Call [start] once after Bluetooth permissions are granted.
 * Call [setSessionActive] to pause / resume the loop around active sessions.
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

    /** Set to true to wake the retry-delay early when a disconnect is detected. */
    @Volatile private var reconnectNow = false

    private var loopJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the persistent auto-connect loop.
     * Safe to call multiple times; a running loop is cancelled and restarted.
     * Also wires disconnect callbacks on the BLE managers so the loop wakes
     * immediately when a device drops rather than waiting for the next retry.
     */
    fun start() {
        // Wire disconnect callbacks (avoids circular DI — set lazily here instead)
        polarBleManager.onDisconnected = { onDeviceDisconnected() }
        oximeterBleManager.onDisconnected = { onDeviceDisconnected() }

        loopJob?.cancel()
        loopJob = scope.launch { reconnectLoop() }
    }

    /**
     * Pause or resume the auto-connect loop around active sessions.
     * When [active] transitions false → true the loop is suspended.
     * When [active] transitions true → false the loop immediately retries.
     */
    fun setSessionActive(active: Boolean) {
        val wasActive = sessionActive.getAndSet(active)
        if (wasActive && !active) {
            // Session just ended — kick the loop immediately
            Log.d(TAG, "Session ended — triggering immediate reconnect check")
            reconnectNow = true
        }
    }

    /**
     * Called by BLE managers when a device disconnects unexpectedly.
     * Wakes the retry-delay early so we reconnect as fast as possible.
     */
    fun onDeviceDisconnected() {
        if (!sessionActive.get()) {
            Log.d(TAG, "Device disconnected — waking reconnect loop")
            reconnectNow = true
        }
    }

    /** Permanently stop the loop (e.g. app teardown). */
    fun cancel() {
        loopJob?.cancel()
    }

    // ── Internal loop ─────────────────────────────────────────────────────────

    private suspend fun reconnectLoop() {
        var retryDelay = INITIAL_RETRY_DELAY_MS
        Log.d(TAG, "Reconnect loop started")

        while (coroutineContext.isActive) {
            // ── 1. Wait while a session is running ────────────────────────────
            if (sessionActive.get()) {
                Log.d(TAG, "Session active — loop suspended")
                while (sessionActive.get() && coroutineContext.isActive) delay(SESSION_POLL_MS)
                Log.d(TAG, "Session ended — resuming loop")
                retryDelay = INITIAL_RETRY_DELAY_MS
            }

            // ── 2. Build the full candidate list ──────────────────────────────
            val candidates = buildCandidateList()
            if (candidates.isEmpty()) {
                Log.d(TAG, "No saved devices — waiting ${NO_DEVICE_WAIT_MS}ms before recheck")
                delay(NO_DEVICE_WAIT_MS)
                continue
            }

            // ── 3. Try every candidate that isn't already connected ───────────
            // We do NOT stop after the first success — we want ALL saved devices
            // connected if they are powered on and in range.
            Log.d(TAG, "Scanning ${candidates.size} candidate(s) …")
            var anyFailed = false
            for (candidate in candidates) {
                if (sessionActive.get()) break          // session started mid-round
                if (isCandidateConnected(candidate)) {
                    Log.d(TAG, "  ✓ already connected: ${candidate.label}")
                    continue
                }
                Log.d(TAG, "  → trying: ${candidate.label} (${candidate.deviceId})")
                val ok = tryConnect(candidate)
                if (ok) {
                    Log.d(TAG, "  ✓ connected: ${candidate.label}")
                    retryDelay = INITIAL_RETRY_DELAY_MS
                } else {
                    Log.d(TAG, "  ✗ failed: ${candidate.label}")
                    anyFailed = true
                }
            }

            // ── 4. If everything is connected, park until a disconnect ─────────
            if (!anyFailed && !sessionActive.get()) {
                Log.d(TAG, "All saved devices connected — parking until disconnect")
                while (!anyDeviceDisconnectedFrom(candidates) &&
                    !sessionActive.get() &&
                    coroutineContext.isActive
                ) {
                    delay(CONNECTED_POLL_MS)
                }
                retryDelay = INITIAL_RETRY_DELAY_MS
                continue
            }

            // ── 5. Some candidates failed — back-off and retry ────────────────
            if (anyFailed) {
                Log.d(TAG, "Some devices unreachable — retrying in ${retryDelay}ms")
                reconnectNow = false
                val deadline = System.currentTimeMillis() + retryDelay
                while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
                    if (reconnectNow || sessionActive.get()) break
                    delay(WAKE_CHECK_INTERVAL_MS)
                }
                reconnectNow = false
                retryDelay = (retryDelay * BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True if at least one device from the candidate list is no longer connected. */
    private fun anyDeviceDisconnectedFrom(candidates: List<DeviceCandidate>): Boolean =
        candidates.any { !isCandidateConnected(it) }

    /** True if this specific candidate is currently in the Connected state. */
    private fun isCandidateConnected(candidate: DeviceCandidate): Boolean =
        when (candidate.deviceType) {
            "h10" ->
                polarBleManager.h10State.value.let {
                    it is BleConnectionState.Connected && it.deviceId == candidate.deviceId
                }
            "verity" ->
                polarBleManager.verityState.value.let {
                    it is BleConnectionState.Connected && it.deviceId == candidate.deviceId
                }
            "oximeter" ->
                oximeterBleManager.connectionState.value.let {
                    it is OximeterConnectionState.Connected && it.deviceAddress == candidate.deviceId
                }
            else -> false
        }

    /**
     * Builds the list of all previously-used devices from saved preferences.
     * Only includes devices that have a saved ID/address (i.e. have been connected before).
     */
    private fun buildCandidateList(): List<DeviceCandidate> {
        val list = mutableListOf<DeviceCandidate>()

        // Log raw prefs so we can diagnose persistence issues
        Log.d(TAG, "Prefs — H10='${prefs.savedH10Id}' Verity='${prefs.savedVerityId}' " +
            "Oximeter='${prefs.savedOximeterAddress}'")

        if (prefs.savedH10Id.isNotBlank())
            list += DeviceCandidate(prefs.savedH10Id, "h10", "H10 (${prefs.savedH10Id})")
        if (prefs.savedVerityId.isNotBlank())
            list += DeviceCandidate(prefs.savedVerityId, "verity", "Verity (${prefs.savedVerityId})")
        if (prefs.savedOximeterAddress.isNotBlank())
            list += DeviceCandidate(prefs.savedOximeterAddress, "oximeter", "Oximeter (${prefs.savedOximeterAddress})")

        Log.d(TAG, "Candidate list (${list.size}): ${list.map { it.label }}")
        return list
    }

    private suspend fun tryConnect(candidate: DeviceCandidate): Boolean =
        when (candidate.deviceType) {
            "h10"      -> tryConnectPolar(candidate.deviceId, isH10 = true)
            "verity"   -> tryConnectPolar(candidate.deviceId, isH10 = false)
            "oximeter" -> tryConnectOximeter(candidate.deviceId)
            else       -> false
        }

    private suspend fun tryConnectPolar(deviceId: String, isH10: Boolean): Boolean {
        polarBleManager.connectDevice(deviceId, isH10)
        val stateFlow = if (isH10) polarBleManager.h10State else polarBleManager.verityState
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            stateFlow.first { it is BleConnectionState.Connected || it is BleConnectionState.Error }
            stateFlow.value is BleConnectionState.Connected
        } ?: false
    }

    private suspend fun tryConnectOximeter(address: String): Boolean {
        // connectWithScan() does an 8 s targeted scan first so the BLE stack
        // "sees" the device before the GATT connect — required for non-bonded devices.
        oximeterBleManager.connectWithScan(address)
        return withTimeoutOrNull(OXIMETER_CONNECT_TIMEOUT_MS) {
            oximeterBleManager.connectionState.first {
                it is OximeterConnectionState.Connected || it is OximeterConnectionState.Error
            }
            oximeterBleManager.connectionState.value is OximeterConnectionState.Connected
        } ?: false
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** How long to wait for a Polar device to connect before trying the next. */
        private const val CONNECT_TIMEOUT_MS = 12_000L

        /**
         * Oximeter timeout is longer because [OximeterBleManager.connectWithScan] runs
         * an 8 s scan window before the GATT connect attempt.
         */
        private const val OXIMETER_CONNECT_TIMEOUT_MS = 25_000L

        /** First retry delay after all candidates fail. */
        private const val INITIAL_RETRY_DELAY_MS = 15_000L

        /** Multiply delay by this factor after each failed round. */
        private const val BACKOFF_MULTIPLIER = 1.5

        /** Maximum delay between retry rounds (~2 min). */
        private const val MAX_RETRY_DELAY_MS = 120_000L

        /** How often to check for early-wake signals during the retry delay. */
        private const val WAKE_CHECK_INTERVAL_MS = 500L

        /** Poll interval while a session is active. */
        private const val SESSION_POLL_MS = 1_000L

        /** Poll interval while all devices are connected (watching for disconnect). */
        private const val CONNECTED_POLL_MS = 2_000L

        /** How long to wait before rechecking when no devices are saved. */
        private const val NO_DEVICE_WAIT_MS = 30_000L
    }
}

// ── Internal model ────────────────────────────────────────────────────────────

private data class DeviceCandidate(
    val deviceId: String,
    val deviceType: String,   // "h10" | "verity" | "oximeter"
    val label: String
)
