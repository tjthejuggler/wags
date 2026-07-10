package com.example.wags.data.meditation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wags.data.ble.UnifiedDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that manages meditation sessions independently of the UI.
 * Ensures sessions are recorded even when the app is closed or screen is off.
 */
@AndroidEntryPoint
class MeditationService : Service() {

    companion object {
        const val CHANNEL_ID = "wags_meditation_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.example.wags.MEDITATION_START"
        const val ACTION_STOP = "com.example.wags.MEDITATION_STOP"
        const val EXTRA_AUDIO_FILE_NAME = "audio_file_name"
        const val EXTRA_AUDIO_DIR_URI = "audio_dir_uri"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_MONITOR_ID = "monitor_id"
        const val EXTRA_SHOULD_SAVE = "should_save"
    }

    @Inject
    lateinit var sessionRecorder: MeditationSessionRecorder

    @Inject
    lateinit var deviceManager: UnifiedDeviceManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var sessionJob: Job? = null
    private var hrDataCollectionJob: Job? = null
    private var sessionStartMs: Long = 0
    private var timerDurationSeconds: Long? = null
    private var activeMonitorId: String? = null
    private var isSessionActive = false

    inner class LocalBinder : Binder() {
        fun getService(): MeditationService = this@MeditationService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d("MeditationService", "MeditationService created")
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val audioFileName = intent.getStringExtra(EXTRA_AUDIO_FILE_NAME)
                val audioDirUri = intent.getStringExtra(EXTRA_AUDIO_DIR_URI)
                val durationSeconds = intent.getLongExtra(EXTRA_DURATION_SECONDS, 0L)
                val monitorId = intent.getStringExtra(EXTRA_MONITOR_ID)
                startSession(audioFileName, audioDirUri, monitorId, if (durationSeconds > 0) durationSeconds else null)
            }
            ACTION_STOP -> {
                val shouldSave = intent.getBooleanExtra(EXTRA_SHOULD_SAVE, true)
                stopSession(shouldSave)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionJob?.cancel()
        hrDataCollectionJob?.cancel()
        stopAudioPlayback()
        releaseWakeLock()
        activeMonitorId?.let { deviceManager.stopAllStreams(it) }
        serviceScope.cancel()
        Log.d("MeditationService", "MeditationService destroyed")
    }

    private fun startSession(
        audioFileName: String?,
        audioDirUri: String?,
        monitorId: String?,
        timerDurationSeconds: Long?
    ) {
        if (isSessionActive) {
            Log.w("MeditationService", "Session already active, ignoring start request")
            return
        }

        isSessionActive = true
        sessionStartMs = System.currentTimeMillis()
        this.timerDurationSeconds = timerDurationSeconds
        this.activeMonitorId = monitorId

        // Start RR stream if monitor is connected
        monitorId?.let {
            try {
                deviceManager.startRrStream(it)
                sessionRecorder.setMonitorId(it)
                Log.d("MeditationService", "Started RR stream for monitor: $it")
            } catch (e: Exception) {
                Log.e("MeditationService", "Failed to start RR stream for monitor: $it", e)
            }
        }

        // Start audio playback if configured
        if (audioFileName != null && audioDirUri != null) {
            startAudioPlayback(audioFileName, audioDirUri)
        }

        // Acquire wake lock to keep CPU running
        acquireWakeLock()

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, buildNotification("Meditation in progress..."))

        // Start session recording
        sessionRecorder.startSession(sessionStartMs, audioFileName)

        // Start HR data collection if monitor is connected
        if (monitorId != null) {
            startHrDataCollection()
        }

        // Start timer job if duration is set
        if (timerDurationSeconds != null) {
            startTimerJob(timerDurationSeconds)
        }

        Log.d("MeditationService", "Meditation session started")
    }

    private fun stopSession(shouldSave: Boolean = true) {
        if (!isSessionActive) {
            Log.w("MeditationService", "No active session to stop")
            return
        }

        isSessionActive = false
        sessionJob?.cancel()
        hrDataCollectionJob?.cancel()

        val durationMs = System.currentTimeMillis() - sessionStartMs
        stopAudioPlayback()
        releaseWakeLock()

        // Stop RR stream if monitor is connected
        activeMonitorId?.let {
            try {
                deviceManager.stopAllStreams(it)
                Log.d("MeditationService", "Stopped RR stream for monitor: $it")
            } catch (e: Exception) {
                Log.e("MeditationService", "Failed to stop RR stream for monitor: $it", e)
            }
        }

        // Stop RR stream if monitor is connected
        activeMonitorId?.let {
            try {
                deviceManager.stopAllStreams(it)
                Log.d("MeditationService", "Stopped RR stream for monitor: $it")
            } catch (e: Exception) {
                Log.e("MeditationService", "Failed to stop RR stream for monitor: $it", e)
            }
        }

        // Stop and process session only if shouldSave is true
        // (false means the ViewModel is handling the save)
        if (shouldSave) {
            serviceScope.launch {
                sessionRecorder.stopSession(durationMs) { savedId ->
                    Log.d("MeditationService", "Session saved with ID: $savedId")
                    // Update notification to show completion
                    val notification = buildNotification("Meditation session saved")
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification)
                    
                    // Stop foreground service after a short delay
                    serviceScope.launch {
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else {
            Log.d("MeditationService", "Session stopped without saving (ViewModel handles save)")
            // Stop foreground service immediately
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        Log.d("MeditationService", "Meditation session stopped, duration: ${durationMs}ms")
    }

    private fun startTimerJob(durationSeconds: Long) {
        sessionJob = serviceScope.launch {
            var remainingSeconds = durationSeconds
            while (isActive && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
                
                // Update notification with remaining time
                val notification = buildNotification("Meditation in progress... ${remainingSeconds}s remaining")
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
            
            // Timer completed - auto-stop session
            if (isActive && remainingSeconds == 0L) {
                stopSession()
            }
        }
    }

    private fun startAudioPlayback(fileName: String, dirUriString: String) {
        stopAudioPlayback()
        try {
            val dirUri = Uri.parse(dirUriString)
            val treeDocId = DocumentsContract.getTreeDocumentId(dirUri)
            val childDocId = "$treeDocId/$fileName"
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, childDocId)
            
            val mp = MediaPlayer().apply {
                setDataSource(applicationContext, fileUri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = mp
            Log.d("MeditationService", "Audio playback started: $fileName")
        } catch (e: Exception) {
            Log.e("MeditationService", "Failed to start audio playback", e)
            mediaPlayer = null
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wags:MeditationWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startHrDataCollection() {
        hrDataCollectionJob = serviceScope.launch {
            while (isActive && isSessionActive) {
                delay(1_000L)
                
                try {
                    val rrSnapshot = deviceManager.rrBuffer.readLast(64)
                    val polarHr = if (rrSnapshot.isNotEmpty()) {
                        (60_000.0 / rrSnapshot.last()).toFloat()
                    } else null
                    
                    // Calculate live RMSSD (simplified version)
                    val liveRmssd = if (rrSnapshot.size >= 2) {
                        val diffs = rrSnapshot.zipWithNext { a, b -> (b - a).toDouble() }
                        val squaredDiffs = diffs.map { it * it }
                        if (squaredDiffs.isNotEmpty()) {
                            Math.sqrt(squaredDiffs.average())
                        } else 0.0
                    } else 0.0
                    
                    // Add telemetry sample to session recorder
                    sessionRecorder.addTelemetrySample(
                        timestampMs = System.currentTimeMillis(),
                        hrBpm = polarHr?.let { Math.round(it) },
                        rollingRmssdMs = liveRmssd
                    )
                    
                    Log.d("MeditationService", "HR: ${polarHr?.let { Math.round(it) } ?: "N/A"} bpm, RMSSD: ${liveRmssd.toInt()} ms")
                } catch (e: Exception) {
                    Log.e("MeditationService", "Error collecting HR data", e)
                }
            }
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MeditationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WAGS — Meditation")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WAGS Meditation Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { 
            description = "Keeps meditation session alive during playback" 
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}