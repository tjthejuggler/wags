package com.example.wags.data.garmin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the connection to a Garmin watch running the WAGS Connect IQ app.
 *
 * Architecture:
 *   1. Auto-initializes on app start if a paired device was previously saved
 *   2. Discovers paired Garmin devices via Garmin Connect Mobile (GCM)
 *   3. When connected, sends SYNC_REQUEST to the watch to pull unsynced holds
 *   4. Watch responds with FREE_HOLD_RESULT messages
 *   5. Phone sends ACK_HOLD to confirm receipt, watch marks hold as synced
 *   6. Shows toast notifications when holds are synced
 *
 * Communication flow:
 *   Phone → sendMessage(SYNC_REQUEST) → Watch
 *   Watch → transmit(FREE_HOLD_RESULT) → Phone (via registerForAppEvents)
 *   Phone → sendMessage(ACK_HOLD) → Watch
 */
@Singleton
class GarminManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "GarminManager"
        const val WAGS_CIQ_APP_ID = "a3421bee-d798-4dc2-b397-12345abcde00"

        // SharedPreferences keys for remembering paired device
        private const val PREFS_NAME = "garmin_prefs"
        private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
        private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"

        // Sync polling interval
        private const val SYNC_DELAY_MS = 3000L
    }

    // ── State ───────────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<GarminConnectionState>(
        GarminConnectionState.Uninitialized
    )
    val connectionState: StateFlow<GarminConnectionState> = _connectionState.asStateFlow()

    /** Emits decoded Free Hold payloads as they arrive from the watch. */
    private val _freeHoldPayloads = MutableSharedFlow<GarminFreeHoldPayload>(extraBufferCapacity = 5)
    val freeHoldPayloads: SharedFlow<GarminFreeHoldPayload> = _freeHoldPayloads.asSharedFlow()

    /** Emits toast messages to show to the user. */
    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private var connectIQ: ConnectIQ? = null
    private var connectedDevice: IQDevice? = null
    private var wagsApp: IQApp? = null
    private var isSyncing = false

    // Batched telemetry accumulator (for large sample sets sent in chunks)
    private val telemetryBatches = mutableMapOf<Int, List<Int>>()

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Paired device persistence ────────────────────────────────────────────

    /** Check if we have a previously paired device saved. */
    fun hasPairedDevice(): Boolean =
        prefs.getLong(KEY_PAIRED_DEVICE_ID, -1L) != -1L

    /** Get the saved paired device name. */
    fun getPairedDeviceName(): String =
        prefs.getString(KEY_PAIRED_DEVICE_NAME, "") ?: ""

    /** Save the paired device for auto-reconnect. */
    private fun savePairedDevice(device: IQDevice) {
        prefs.edit()
            .putLong(KEY_PAIRED_DEVICE_ID, device.deviceIdentifier)
            .putString(KEY_PAIRED_DEVICE_NAME, device.friendlyName ?: "Garmin Watch")
            .apply()
        Log.i(TAG, "Saved paired device: ${device.friendlyName} (${device.deviceIdentifier})")
    }

    /** Clear the saved paired device. */
    fun clearPairedDevice() {
        prefs.edit()
            .remove(KEY_PAIRED_DEVICE_ID)
            .remove(KEY_PAIRED_DEVICE_NAME)
            .apply()
        Log.i(TAG, "Cleared paired device")
    }

    // ── Initialization ──────────────────────────────────────────────────────

    /**
     * Initialize the Connect IQ SDK and connect to the watch.
     * Call this from Application.onCreate() for auto-connect,
     * or from the settings screen for manual pairing.
     */
    fun initialize(connectType: IQConnectType = IQConnectType.WIRELESS) {
        val currentState = _connectionState.value
        Log.w(TAG, "initialize() called. Current state: $currentState")

        if (currentState is GarminConnectionState.Connected) {
            Log.w(TAG, "Already connected, triggering sync")
            requestSync()
            return
        }

        // If in any non-Uninitialized state, shut down first
        if (currentState !is GarminConnectionState.Uninitialized &&
            currentState !is GarminConnectionState.Error
        ) {
            Log.w(TAG, "Shutting down before re-init from state: $currentState")
            try { connectIQ?.shutdown(context) } catch (_: Exception) {}
            connectIQ = null
        }

        _connectionState.value = GarminConnectionState.Initializing
        Log.w(TAG, "State → Initializing")

        try {
            val ciq = ConnectIQ.getInstance(context, connectType)
            connectIQ = ciq
            Log.w(TAG, "ConnectIQ.getInstance() returned: $ciq")

            ciq.initialize(context, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    Log.w(TAG, ">>> onSdkReady()")
                    _connectionState.value = GarminConnectionState.SdkReady
                    discoverDevices()
                }

                override fun onInitializeError(status: IQSdkErrorStatus?) {
                    val msg = "Connect IQ init error: ${status?.name ?: "unknown"}"
                    Log.e(TAG, msg)
                    _connectionState.value = GarminConnectionState.Error(msg)
                    // Retry after delay
                    scope.launch {
                        delay(10_000)
                        if (_connectionState.value is GarminConnectionState.Error) {
                            Log.w(TAG, "Retrying initialization after error...")
                            _connectionState.value = GarminConnectionState.Uninitialized
                            initialize(connectType)
                        }
                    }
                }

                override fun onSdkShutDown() {
                    Log.w(TAG, ">>> onSdkShutDown()")
                    _connectionState.value = GarminConnectionState.Uninitialized
                    connectedDevice = null
                    wagsApp = null
                }
            })
            Log.w(TAG, "ciq.initialize() called, waiting for callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Connect IQ", e)
            _connectionState.value = GarminConnectionState.Error(
                "Failed to initialize: ${e.message}"
            )
        }
    }

    fun shutdown() {
        try { connectIQ?.shutdown(context) } catch (_: Exception) {}
        connectIQ = null
        connectedDevice = null
        wagsApp = null
        _connectionState.value = GarminConnectionState.Uninitialized
    }

    // ── Device Discovery ────────────────────────────────────────────────────

    private fun discoverDevices() {
        val ciq = connectIQ ?: return

        try {
            val knownDevices: List<IQDevice> = ciq.knownDevices ?: emptyList()
            Log.d(TAG, "Known devices: ${knownDevices.size}")

            if (knownDevices.isEmpty()) {
                _connectionState.value = GarminConnectionState.Error(
                    "No Garmin devices found.\n" +
                    "• Is Garmin Connect Mobile installed?\n" +
                    "• Is your watch paired in Garmin Connect?"
                )
                return
            }

            for (device in knownDevices) {
                Log.d(TAG, "Device: ${device.friendlyName} (${device.deviceIdentifier})")

                ciq.registerForDeviceEvents(device, object : IQDeviceEventListener {
                    override fun onDeviceStatusChanged(
                        device: IQDevice?,
                        status: IQDevice.IQDeviceStatus?
                    ) {
                        Log.d(TAG, "Device ${device?.friendlyName} status: $status")
                        if (device != null && status == IQDevice.IQDeviceStatus.CONNECTED) {
                            onDeviceConnected(device)
                        } else if (device != null &&
                            connectedDevice?.deviceIdentifier == device.deviceIdentifier
                        ) {
                            connectedDevice = null
                            wagsApp = null
                            _connectionState.value = GarminConnectionState.SdkReady
                        }
                    }
                })

                val status = ciq.getDeviceStatus(device)
                Log.d(TAG, "Device '${device.friendlyName}' status: $status")

                if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                    onDeviceConnected(device)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering devices", e)
            _connectionState.value = GarminConnectionState.Error(
                "Error discovering devices: ${e.message}"
            )
        }
    }

    private fun onDeviceConnected(device: IQDevice) {
        connectedDevice = device
        val name = device.friendlyName ?: "Garmin Watch"
        Log.i(TAG, "Device connected: $name (${device.deviceIdentifier})")

        // Save for auto-reconnect
        savePairedDevice(device)

        // Create IQApp reference
        val app = IQApp(WAGS_CIQ_APP_ID)
        wagsApp = app

        _connectionState.value = GarminConnectionState.Connected(
            deviceName = name,
            deviceId = device.deviceIdentifier
        )

        // Register for incoming messages from the watch
        registerForMessages(device, app)

        // Trigger a sync after a short delay to let everything settle
        scope.launch {
            delay(SYNC_DELAY_MS)
            requestSync()
        }
    }

    // ── Message Registration ────────────────────────────────────────────────

    private fun registerForMessages(device: IQDevice, app: IQApp) {
        val ciq = connectIQ ?: return

        try {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    device: IQDevice?,
                    app: IQApp?,
                    message: MutableList<Any>?,
                    status: ConnectIQ.IQMessageStatus?
                ) {
                    Log.w(TAG, ">>> onMessageReceived! status=$status, size=${message?.size}")

                    if (status != ConnectIQ.IQMessageStatus.SUCCESS || message == null) {
                        Log.w(TAG, "Message failed: $status")
                        return
                    }

                    for (msg in message) {
                        handleIncomingMessage(msg)
                    }
                }
            })
            Log.w(TAG, "Registered for app events on ${device.friendlyName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering for app events", e)
        }
    }

    // ── Sync Protocol ───────────────────────────────────────────────────────

    /**
     * Request the watch to send all unsynced holds.
     * Called automatically when connected and periodically.
     */
    fun requestSync() {
        if (isSyncing) {
            Log.d(TAG, "Sync already in progress, skipping")
            return
        }

        val device = connectedDevice ?: run {
            Log.d(TAG, "No connected device, can't sync")
            return
        }
        val app = wagsApp ?: return
        val ciq = connectIQ ?: return

        isSyncing = true
        Log.w(TAG, ">>> Sending SYNC_REQUEST to watch")

        try {
            val syncRequest = mapOf("cmd" to "SYNC_REQUEST") as Any
            ciq.sendMessage(
                device,
                app,
                syncRequest,
                object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        device: IQDevice?,
                        app: IQApp?,
                        status: ConnectIQ.IQMessageStatus?
                    ) {
                        Log.w(TAG, "SYNC_REQUEST send status: $status")
                        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                            isSyncing = false
                        }
                        // Timeout the sync flag after a delay
                        scope.launch {
                            delay(30_000)
                            isSyncing = false
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SYNC_REQUEST", e)
            isSyncing = false
        }
    }

    /**
     * Send ACK_HOLD to the watch to confirm receipt of a hold.
     */
    private fun sendAckHold(holdId: Int) {
        val device = connectedDevice ?: return
        val app = wagsApp ?: return
        val ciq = connectIQ ?: return

        Log.w(TAG, "Sending ACK_HOLD for holdId=$holdId")

        try {
            val ack = mapOf("cmd" to "ACK_HOLD", "holdId" to holdId) as Any
            ciq.sendMessage(
                device,
                app,
                ack,
                object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        device: IQDevice?,
                        app: IQApp?,
                        status: ConnectIQ.IQMessageStatus?
                    ) {
                        Log.w(TAG, "ACK_HOLD send status: $status")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ACK_HOLD", e)
        }
    }

    // ── Message Handling ────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleIncomingMessage(message: Any) {
        Log.w(TAG, "handleIncomingMessage: ${message::class.java.simpleName}")

        if (message !is Map<*, *>) {
            Log.w(TAG, "Unexpected message type: ${message::class.java}, value=$message")
            return
        }

        val type = message["type"] as? String
        if (type == null) {
            Log.w(TAG, "No 'type' key. Keys: ${message.keys}")
            return
        }

        Log.w(TAG, "Message type: $type")

        when (type) {
            "PONG" -> {
                val unsyncedCount = (message["unsyncedCount"] as? Number)?.toInt() ?: 0
                Log.w(TAG, "PONG received: $unsyncedCount unsynced holds")
                if (unsyncedCount > 0) {
                    requestSync()
                }
            }

            "SYNC_COMPLETE" -> {
                val count = (message["count"] as? Number)?.toInt() ?: 0
                Log.w(TAG, "SYNC_COMPLETE: $count holds")
                isSyncing = false
            }

            "TELEMETRY_BATCH" -> {
                val batchIndex = (message["batchIndex"] as? Number)?.toInt() ?: return
                val samples = message["samples"] as? List<*> ?: return
                val packedSamples = samples.mapNotNull { (it as? Number)?.toInt() }
                telemetryBatches[batchIndex] = packedSamples
                Log.w(TAG, "Telemetry batch $batchIndex: ${packedSamples.size} samples")
            }

            "FREE_HOLD_RESULT" -> {
                Log.w(TAG, ">>> FREE_HOLD_RESULT received!")
                val holdId = (message["id"] as? Number)?.toInt()
                val payload = decodeFreeHoldResult(message)

                if (payload != null) {
                    Log.w(TAG, "Decoded: duration=${payload.durationMs}ms, " +
                            "samples=${payload.telemetrySamples.size}")

                    scope.launch {
                        _freeHoldPayloads.emit(payload)
                        Log.w(TAG, "Payload emitted to flow")

                        // Show toast
                        val durationSec = payload.durationMs / 1000
                        val min = durationSec / 60
                        val sec = durationSec % 60
                        val timeStr = if (min > 0) "${min}m ${sec}s" else "${sec}s"
                        _toastMessages.emit("Garmin hold synced: $timeStr")
                    }

                    // Acknowledge receipt
                    if (holdId != null) {
                        sendAckHold(holdId)
                    }
                } else {
                    Log.e(TAG, "Failed to decode FREE_HOLD_RESULT")
                }

                telemetryBatches.clear()
                isSyncing = false
            }

            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeFreeHoldResult(message: Map<*, *>): GarminFreeHoldPayload? {
        try {
            val durationMs = (message["durationMs"] as? Number)?.toLong() ?: return null
            val lungVolume = message["lungVolume"] as? String ?: "FULL"
            val prepType = message["prepType"] as? String ?: "NO_PREP"
            val timeOfDay = message["timeOfDay"] as? String ?: "DAY"
            val firstContractionMs = (message["firstContractionMs"] as? Number)?.toLong()
            val startEpochMs = (message["startEpochMs"] as? Number)?.toLong()
                ?: (System.currentTimeMillis() - durationMs)
            val endEpochMs = (message["endEpochMs"] as? Number)?.toLong()
                ?: System.currentTimeMillis()

            val rawContractions = message["contractions"] as? List<*> ?: emptyList<Any>()
            val contractionTimesMs = rawContractions.mapNotNull { (it as? Number)?.toLong() }

            // Assemble packed samples
            val packedSamples: List<Int>
            val batchCount = (message["batchCount"] as? Number)?.toInt()

            if (batchCount != null && batchCount > 0) {
                val assembled = mutableListOf<Int>()
                for (i in 0 until batchCount) {
                    val batch = telemetryBatches[i]
                    if (batch != null) {
                        assembled.addAll(batch)
                    } else {
                        Log.w(TAG, "Missing telemetry batch $i")
                    }
                }
                packedSamples = assembled
            } else {
                val rawSamples = message["packedSamples"] as? List<*> ?: emptyList<Any>()
                packedSamples = rawSamples.mapNotNull { (it as? Number)?.toInt() }
            }

            val telemetrySamples = unpackSamples(packedSamples)

            return GarminFreeHoldPayload(
                durationMs = durationMs,
                lungVolume = lungVolume,
                prepType = prepType,
                timeOfDay = timeOfDay,
                firstContractionMs = firstContractionMs,
                contractionTimesMs = contractionTimesMs,
                telemetrySamples = telemetrySamples,
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding FREE_HOLD_RESULT", e)
            return null
        }
    }

    private fun unpackSamples(packedSamples: List<Int>): List<TelemetrySample> {
        return packedSamples.map { packed ->
            val hr = (packed shr 8) and 0xFF
            val spo2 = packed and 0xFF
            TelemetrySample(
                heartRateBpm = if (hr in 1..254) hr else null,
                spO2 = if (spo2 in 1..254) spo2 else null
            )
        }
    }

    // ── Send message to watch ───────────────────────────────────────────────

    fun sendToWatch(payload: Map<String, Any>) {
        val ciq = connectIQ ?: return
        val device = connectedDevice ?: return
        val app = wagsApp ?: return

        try {
            ciq.sendMessage(
                device,
                app,
                payload as Any,
                object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        device: IQDevice?,
                        app: IQApp?,
                        status: ConnectIQ.IQMessageStatus?
                    ) {
                        Log.d(TAG, "Send to watch status: $status")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to watch", e)
        }
    }
}
