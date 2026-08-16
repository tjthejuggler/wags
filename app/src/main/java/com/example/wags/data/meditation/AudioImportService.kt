package com.example.wags.data.meditation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.wags.MainActivity
import com.example.wags.data.repository.MeditationRepository
import com.example.wags.data.repository.YoutubeAudioImporter
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Which library a shared YouTube audio belongs to. */
enum class AudioImportCategory(val label: String) {
    MEDITATION("Meditation / NSDR"),
    APNEA("Guided Apnea")
}

// ── UI state ──────────────────────────────────────────────────────────────────

sealed interface AudioImportUiState {
    /** No import running — the category chooser is shown (when a URL is pending). */
    data object Idle : AudioImportUiState

    /** Resolving video / stream metadata. */
    data object Resolving : AudioImportUiState

    /** Downloading the audio stream. */
    data class Downloading(
        val title: String,
        val channel: String?,
        val durationSeconds: Long,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : AudioImportUiState {
        /** 0..1 fraction, or null when the total size is unknown. */
        val progress: Float?
            get() = if (totalBytes > 0)
                (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
            else null
    }

    /** Import finished; audio is available in the picker / library. */
    data class Success(
        val title: String,
        val fileName: String,
        val category: AudioImportCategory
    ) : AudioImportUiState

    /** Import failed with a user-presentable message. */
    data class Failed(val message: String) : AudioImportUiState
}

// ── Service ───────────────────────────────────────────────────────────────────

/**
 * Foreground service that runs the share-to-Wags YouTube audio import.
 *
 * Owning the import in a service (instead of a ViewModel) means the download
 * keeps running when the user closes the app — an ongoing notification shows
 * progress, and a completion / failure notification is posted at the end.
 *
 * The current [AudioImportUiState] is exposed via [state] so the import screen
 * can show live progress while the app is open.
 */
@AndroidEntryPoint
class AudioImportService : Service() {

    companion object {
        private const val TAG = "AudioImportService"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_CATEGORY = "extra_category"
        private const val CHANNEL_ID = "wags_audio_import"
        private const val NOTIFICATION_ID = 4602
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        private val _state = MutableStateFlow<AudioImportUiState>(AudioImportUiState.Idle)

        /** Live import state; survives the UI, updated only by the service. */
        val state = _state.asStateFlow()

        /** True while an import is resolving or downloading. */
        val isRunning: Boolean
            get() = _state.value is AudioImportUiState.Resolving ||
                _state.value is AudioImportUiState.Downloading

        /** Starts the import of [url] into [category] as a foreground service. */
        fun start(context: Context, url: String, category: AudioImportCategory) {
            val intent = Intent(context, AudioImportService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_CATEGORY, category.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    @Inject
    lateinit var repository: MeditationRepository

    @Inject
    lateinit var guidedAudioManager: GuidedAudioManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val category = intent?.getStringExtra(EXTRA_CATEGORY)
            ?.let { runCatching { AudioImportCategory.valueOf(it) }.getOrNull() }

        if (url.isNullOrBlank() || category == null || isRunning) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Starting download…", indeterminate = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireWakeLock()

        serviceScope.launch {
            var completion: Pair<String, String>? = null
            try {
                _state.value = AudioImportUiState.Resolving
                when (category) {
                    AudioImportCategory.MEDITATION -> {
                        val entity = repository.importYoutubeAudio(url) { onEvent(it) }
                        _state.value = AudioImportUiState.Success(
                            title = entity.displayName,
                            fileName = entity.fileName,
                            category = category
                        )
                        completion = "Saved ✓" to
                            "${entity.displayName} — added to your meditation library"
                    }

                    AudioImportCategory.APNEA -> {
                        val downloaded = repository.downloadGuidedApneaAudio(url) { onEvent(it) }
                        registerGuidedAudio(downloaded, url)
                        _state.value = AudioImportUiState.Success(
                            title = downloaded.title,
                            fileName = downloaded.fileName,
                            category = category
                        )
                        completion = "Saved ✓" to
                            "${downloaded.title} — added to your guided apnea library"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "YouTube audio import failed", e)
                _state.value = AudioImportUiState.Failed(e.message ?: "Import failed.")
                completion = "Import failed" to (e.message ?: "Import failed.")
            } finally {
                releaseWakeLock()
                ServiceCompat.stopForeground(this@AudioImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                completion?.let { (title, text) -> showCompletionNotification(title, text) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(fgsType: Int) {
        // dataSync FGS runtime limit reached — cancel; the importer cleans up
        // partial files on cancellation.
        serviceScope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Import events → state + notification ───────────────────────────────────

    private fun onEvent(event: YoutubeAudioImporter.ImportEvent) {
        when (event) {
            YoutubeAudioImporter.ImportEvent.Resolving ->
                _state.value = AudioImportUiState.Resolving

            is YoutubeAudioImporter.ImportEvent.Resolved ->
                _state.value = AudioImportUiState.Downloading(
                    title = event.title,
                    channel = event.channel,
                    durationSeconds = event.durationSeconds,
                    bytesDownloaded = 0,
                    totalBytes = 0
                )

            is YoutubeAudioImporter.ImportEvent.Progress -> {
                val current = _state.value as? AudioImportUiState.Downloading ?: return
                _state.value = current.copy(
                    bytesDownloaded = event.bytesDownloaded,
                    totalBytes = event.totalBytes
                )
                updateProgressNotification(current.title, event.bytesDownloaded, event.totalBytes)
            }
        }
    }

    /**
     * Registers a downloaded guided-apnea file in the guided audio library.
     * Re-importing the same video replaces the old entry (and deletes the old
     * file) instead of duplicating library entries.
     */
    private suspend fun registerGuidedAudio(
        downloaded: YoutubeAudioImporter.DownloadedAudio,
        sourceUrl: String
    ) {
        val uriString = downloaded.docUri.toString()
        val existing = guidedAudioManager.findBySourceUrl(sourceUrl)
        if (existing != null) {
            if (existing.uri != uriString) {
                runCatching {
                    DocumentsContract.deleteDocument(contentResolver, Uri.parse(existing.uri))
                }
            }
            guidedAudioManager.updateAudio(
                existing.copy(fileName = downloaded.fileName, uri = uriString)
            )
        } else {
            guidedAudioManager.addAudio(downloaded.fileName, uriString, sourceUrl)
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio imports",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress of YouTube audio imports"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(
        text: String,
        progressPercent: Int? = null,
        indeterminate: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Wags audio import")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
        when {
            indeterminate -> builder.setProgress(0, 0, true)
            progressPercent != null -> builder.setProgress(100, progressPercent, false)
        }
        return builder.build()
    }

    private fun updateProgressNotification(title: String, bytes: Long, total: Long) {
        val percent = if (total > 0) (bytes * 100 / total).toInt() else -1
        val text = if (total > 0) {
            "$title — $percent%"
        } else {
            "$title — ${bytes / 1024} KB"
        }
        val notification = buildNotification(
            text,
            progressPercent = percent.takeIf { it >= 0 },
            indeterminate = percent < 0
        )
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun showCompletionNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    // ── Wake lock ──────────────────────────────────────────────────────────────

    /**
     * Holds a partial wake lock for the download so it survives screen-off /
     * Doze while the user is away from the app.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wags:audio_import").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
