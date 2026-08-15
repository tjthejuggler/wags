package com.example.wags.data.meditation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wags.data.ble.UnifiedDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Foreground service that manages meditation sessions independently of the UI.
 *
 * ## Crash-Safety Design
 *
 * The service creates a database row for the session **immediately** when it
 * starts (via [MeditationSessionRecorder.startSession]) and flushes telemetry
 * to the database every 15 seconds (via the periodic flush job).  This means
 * that even if Android kills the process while the screen is off, all data
 * collected up to that point survives in the database.
 *
 * Sessions left with `completed = false` are detected and recovered on the
 * next service restart or app launch.
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
        const val EXTRA_POSTURE = "posture"
        const val EXTRA_TIMER_MS = "timer_ms"

        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
        private const val TELEMETRY_FLUSH_INTERVAL_MS = 15_000L // 15 seconds
    }

    @Inject
    lateinit var sessionRecorder: MeditationSessionRecorder

    @Inject
    lateinit var deviceManager: UnifiedDeviceManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()

    /**
     * SupervisorJob + exception handler: a failure in ANY child coroutine (e.g. a
     * DB error during a telemetry flush) must NEVER cancel its siblings — most
     * importantly the wake-lock renewal job — and must NEVER crash the process
     * (which would kill the in-flight session save and lose the session).
     * With the previous plain Job(), one failed child cancelled the whole scope,
     * the wake lock then expired after its 10-minute timeout, and the session
     * died exactly 10 minutes after the screen was turned off.
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("MeditationService", "Uncaught error in service coroutine", throwable)
        }
    )
    private var sessionJob: Job? = null
    private var hrDataCollectionJob: Job? = null
    private var telemetryFlushJob: Job? = null
    private var sessionStartMs: Long = 0
    private var timerDurationSeconds: Long? = null
    private var activeMonitorId: String? = null
    private var isSessionActive = false
    private var wakeLockAcquired = false

    inner class LocalBinder : Binder() {
        fun getService(): MeditationService = this@MeditationService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        requestBatteryOptimizationExemption()
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
                val posture = intent.getStringExtra(EXTRA_POSTURE) ?: "LAYING"
                val timerMs = intent.getLongExtra(EXTRA_TIMER_MS, 0L)
                    .takeIf { it > 0 }
                startSession(
                    audioFileName, audioDirUri, monitorId,
                    if (durationSeconds > 0) durationSeconds else null,
                    posture, timerMs
                )
            }
            ACTION_STOP -> {
                val shouldSave = intent.getBooleanExtra(EXTRA_SHOULD_SAVE, true)
                stopSession(shouldSave)
            }
            null -> {
                // Service restarted by the system (START_STICKY) after being killed.
                // Recover any orphaned sessions from the previous lifecycle.
                Log.d("MeditationService", "Service restarted by system — recovering orphaned sessions")
                // A sticky restart after startForegroundService() must re-enter the
                // foreground promptly, otherwise the system throws
                // ForegroundServiceDidNotStartInTime and the app crashes.
                startForegroundWithMediaPlayback(buildNotification("Finishing up meditation session…"))
                serviceScope.launch {
                    try {
                        sessionRecorder.recoverOrphanedSessions()
                    } catch (e: Exception) {
                        Log.e("MeditationService", "Orphan recovery failed on restart", e)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MeditationService", "MeditationService onDestroy — isSessionActive=$isSessionActive")

        if (isSessionActive) {
            // Emergency save: flush remaining telemetry and mark session as completed.
            // This is the last-resort safety net.  We use runBlocking because onDestroy
            // gives us no coroutine context — the suspend emergencySave() must complete
            // before we release our resources.
            Log.d("MeditationService", "Emergency save: saving session before destruction")
            runBlocking {
                sessionRecorder.emergencySave()
            }
        }

        sessionJob?.cancel()
        hrDataCollectionJob?.cancel()
        telemetryFlushJob?.cancel()
        stopAudioPlayback()
        releaseMediaSession()
        releaseWakeLock()
        activeMonitorId?.let { deviceManager.stopAllStreams(it) }
        serviceScope.cancel()
        Log.d("MeditationService", "MeditationService destroyed")
    }

    /**
     * Android 15+ can stop a mediaPlayback foreground service via a timeout.
     * If that ever happens mid-session, emergency-save everything collected so
     * far instead of silently losing the session, then stop cleanly.
     */
    override fun onTimeout(fgsType: Int) {
        Log.w("MeditationService", "Foreground service timeout (type=$fgsType) — emergency saving session")
        if (isSessionActive) {
            isSessionActive = false
            sessionJob?.cancel()
            hrDataCollectionJob?.cancel()
            telemetryFlushJob?.cancel()
            stopAudioPlayback()
            releaseMediaSession()
            releaseWakeLock()
            activeMonitorId?.let {
                try { deviceManager.stopAllStreams(it) } catch (_: Exception) {}
            }
            runBlocking {
                try {
                    sessionRecorder.emergencySave()
                } catch (e: Exception) {
                    Log.e("MeditationService", "Emergency save on timeout failed", e)
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTimeout(fgsType)
    }

    private fun startSession(
        audioFileName: String?,
        audioDirUri: String?,
        monitorId: String?,
        timerDurationSeconds: Long?,
        posture: String = "LAYING",
        timerDurationMs: Long? = null
    ) {
        if (isSessionActive) {
            Log.w("MeditationService", "Session already active, ignoring start request")
            return
        }

        // Recover any orphaned sessions from a previous crash BEFORE creating the
        // new session row.  This used to run concurrently with the row creation,
        // and the recovery query deleteIncompleteShorterThan() could delete the
        // brand-new row (durationMs = 0, completed = 0) — orphaning every
        // telemetry insert for the rest of the session (FOREIGN KEY constraint
        // failure) and crashing the app when the user pressed Done.
        runBlocking {
            sessionRecorder.recoverOrphanedSessions() // never throws
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

        // Start wake lock renewal job to prevent timeout
        startWakeLockRenewalJob()

        // Start foreground service with notification.  Explicitly pass the
        // mediaPlayback type: on API 34+ the two-argument overload would use ALL
        // manifest-declared types (mediaPlayback|connectedDevice), and
        // connectedDevice has runtime prerequisites (e.g. BLUETOOTH_CONNECT) that
        // can fail when no monitor is connected.
        startForegroundWithMediaPlayback(buildNotification("Meditation in progress..."))

        // Hold an ACTIVE MediaSession for the whole session.  On Android 14+ the
        // system ties the legitimacy of a mediaPlayback foreground service to an
        // active media session; without one the service can be stopped by the
        // system while the screen is off, killing the session and the audio.
        activateMediaSession()

        // Start session recording — creates DB row immediately with completed=false
        sessionRecorder.startSession(
            startMs = sessionStartMs,
            audioFileName = audioFileName,
            posture = posture,
            monitorId = monitorId,
            timerDurationMs = timerDurationMs
        )

        // Start periodic telemetry flush — writes to DB every 15 seconds
        startTelemetryFlushJob()

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
            // The service may have been (re)created just to receive this stop
            // command — don't leave it lingering as a started service.
            stopSelf()
            return
        }

        isSessionActive = false
        sessionJob?.cancel()
        hrDataCollectionJob?.cancel()
        telemetryFlushJob?.cancel()

        val durationMs = System.currentTimeMillis() - sessionStartMs
        stopAudioPlayback()
        releaseMediaSession()
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

        if (shouldSave) {
            // Service handles the full save (timer auto-stop, notification stop, etc.)
            serviceScope.launch {
                sessionRecorder.stopSession(durationMs) { savedId ->
                    Log.d("MeditationService", "Session saved with ID: $savedId")
                    val notification = buildNotification("Meditation session saved")
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification)

                    serviceScope.launch {
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else {
            // ViewModel will handle the save — just flush remaining telemetry
            // so the DB row has the latest data.  The ViewModel will update the
            // existing row with full analytics and mark it completed.
            serviceScope.launch {
                try {
                    sessionRecorder.flushTelemetry()
                    Log.d("MeditationService", "Telemetry flushed; ViewModel handles final save")
                } catch (e: Exception) {
                    // Never let a flush failure crash the process — the ViewModel
                    // is mid-save of the user's session at this very moment.
                    Log.e("MeditationService", "Final telemetry flush failed", e)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
                stopSession(shouldSave = true)
            }
        }
    }

    /**
     * Periodically flushes telemetry to the database so that data survives
     * even if the process is killed mid-session.
     */
    private fun startTelemetryFlushJob() {
        telemetryFlushJob = serviceScope.launch {
            while (isActive && isSessionActive) {
                delay(TELEMETRY_FLUSH_INTERVAL_MS)
                try {
                    sessionRecorder.flushTelemetry()
                } catch (e: Exception) {
                    Log.e("MeditationService", "Telemetry flush failed", e)
                }
            }
        }
    }

    private fun startWakeLockRenewalJob() {
        serviceScope.launch {
            while (isActive && isSessionActive) {
                delay(5 * 60 * 1000L) // Every 5 minutes
                if (wakeLockAcquired && isSessionActive) {
                    try {
                        wakeLock?.let {
                            if (it.isHeld) {
                                it.release()
                            }
                        }
                        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                        Log.d("MeditationService", "WakeLock renewed")
                    } catch (e: Exception) {
                        Log.e("MeditationService", "Failed to renew WakeLock", e)
                        releaseWakeLock()
                        acquireWakeLock()
                    }
                }
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
                // Keep the CPU awake while audio is playing, independent of our
                // own (periodically renewed) wake lock.  Without this, playback
                // stalls when the device suspends with the screen off.
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
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

    /**
     * Enters the foreground state with the explicit mediaPlayback service type
     * (a subset of the manifest-declared types, which is allowed).
     */
    private fun startForegroundWithMediaPlayback(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Creates (or reactivates) the MediaSession and marks it as PLAYING.
     *
     * An active MediaSession is what tells the system that this mediaPlayback
     * foreground service is genuinely playing media.  Without it, Android 14+
     * (and Samsung One UI's stricter enforcement) can stop the foreground
     * service while the screen is off — which is the "session dies ~10 minutes
     * after screen off" bug.
     */
    private fun activateMediaSession() {
        try {
            if (mediaSession == null) {
                mediaSession = MediaSession(this, "WagsMeditation").apply {
                    setCallback(object : MediaSession.Callback() {
                        // Media button / system "stop" ends the session (saved).
                        override fun onStop() {
                            stopSession(shouldSave = true)
                        }
                        // Deliberate no-ops: a stray media button press must not
                        // interrupt a running meditation session.
                        override fun onPlay() {}
                        override fun onPause() {}
                    })
                }
            }
            mediaSession?.isActive = true
            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY
                            or PlaybackState.ACTION_PAUSE
                            or PlaybackState.ACTION_STOP
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
            Log.d("MeditationService", "MediaSession activated (STATE_PLAYING)")
        } catch (e: Exception) {
            Log.e("MeditationService", "Failed to activate MediaSession", e)
        }
    }

    private fun releaseMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            Log.e("MeditationService", "Failed to release MediaSession", e)
        }
        mediaSession = null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wags:MeditationWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeLockAcquired = true
            Log.d("MeditationService", "WakeLock acquired with ${WAKE_LOCK_TIMEOUT_MS / 1000}s timeout")
        } catch (e: Exception) {
            Log.e("MeditationService", "Failed to acquire WakeLock", e)
            wakeLockAcquired = false
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                    Log.d("MeditationService", "WakeLock released")
                } catch (e: Exception) {
                    Log.e("MeditationService", "Error releasing WakeLock", e)
                }
            }
        }
        wakeLock = null
        wakeLockAcquired = false
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

                    val liveRmssd = if (rrSnapshot.size >= 2) {
                        val diffs = rrSnapshot.zipWithNext { a, b -> (b - a).toDouble() }
                        val squaredDiffs = diffs.map { it * it }
                        if (squaredDiffs.isNotEmpty()) {
                            Math.sqrt(squaredDiffs.average())
                        } else 0.0
                    } else 0.0

                    sessionRecorder.addTelemetrySample(
                        timestampMs = System.currentTimeMillis(),
                        hrBpm = polarHr?.let { Math.round(it) },
                        rollingRmssdMs = liveRmssd
                    )
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

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    Log.d("MeditationService", "Requested battery optimization exemption")
                } catch (e: Exception) {
                    Log.e("MeditationService", "Failed to request battery optimization exemption", e)
                }
            } else {
                Log.d("MeditationService", "Already exempt from battery optimizations")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WAGS Meditation Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps meditation session alive during playback"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
