package com.example.wags.data.spotify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Spotify integration:
 *  1. Sends a play command to Spotify when a session starts with MUSIC selected.
 *  2. Detects the currently-playing song via two complementary strategies:
 *     a. **Spotify broadcast intents** (`com.spotify.music.metadatachanged`) —
 *        works without any special permissions on all Spotify versions.
 *     b. **MediaSession API** via [MediaNotificationListener] — requires
 *        Notification Access but provides richer metadata.
 *  3. Tracks which songs played during a session.
 *
 * The broadcast receiver is always registered while tracking is active.
 * The MediaSession path is attempted as a bonus but gracefully degrades
 * when Notification Access is not granted.
 */
@Singleton
class SpotifyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        /** Max time (ms) to poll for Spotify metadata after sending a play command. */
        private const val METADATA_POLL_TIMEOUT_MS = 2_000L
        /** Interval (ms) between metadata poll attempts. */
        private const val METADATA_POLL_INTERVAL_MS = 250L

        // Spotify legacy broadcast actions (no permissions required)
        private const val SPOTIFY_METADATA_CHANGED = "com.spotify.music.metadatachanged"
        private const val SPOTIFY_PLAYBACK_CHANGED = "com.spotify.music.playbackstatechanged"
    }

    /** Internal scope for fire-and-forget work (metadata polling). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Currently tracked song during an active session
    private val _currentSong = MutableStateFlow<TrackInfo?>(null)
    val currentSong: StateFlow<TrackInfo?> = _currentSong.asStateFlow()

    // Accumulated songs for the current session
    private val _sessionSongs = MutableStateFlow<List<TrackInfo>>(emptyList())
    val sessionSongs: StateFlow<List<TrackInfo>> = _sessionSongs.asStateFlow()

    private var isTracking = false
    private var sessionStartMs: Long = 0L
    private var metadataPollJob: Job? = null
    private var broadcastReceiverRegistered = false

    // Active MediaController for Spotify (set by MediaNotificationListener)
    private var spotifyController: MediaController? = null

    // ── Spotify broadcast receiver (no permissions required) ─────────────────

    /**
     * Receives Spotify's legacy broadcast intents for metadata and playback
     * state changes. These broadcasts are sent by Spotify without requiring
     * any special permissions from the receiving app.
     */
    private val spotifyBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                SPOTIFY_METADATA_CHANGED -> {
                    val title = intent.getStringExtra("track") ?: return
                    val artist = intent.getStringExtra("artist") ?: ""
                    val nowMs = System.currentTimeMillis()
                    handleNewTrack(title, artist, nowMs)
                }
                SPOTIFY_PLAYBACK_CHANGED -> {
                    // Playback state changed (play/pause/skip) — if playing
                    // started and we don't have a song yet, the metadata
                    // broadcast usually follows immediately.
                }
            }
        }
    }

    // ── MediaSession callback (requires Notification Access) ─────────────────

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val nowMs = System.currentTimeMillis()
            handleNewTrack(title, artist, nowMs)
        }
    }

    /**
     * Common handler for new track metadata from either the broadcast receiver
     * or the MediaSession callback.
     */
    private fun handleNewTrack(title: String, artist: String, nowMs: Long) {
        val newTrack = TrackInfo(
            title = title,
            artist = artist,
            startedAtMs = nowMs
        )

        // Avoid duplicate entries if both broadcast and MediaSession fire
        val prev = _currentSong.value
        if (prev != null && prev.title == title && prev.artist == artist) {
            return // same track, skip duplicate
        }

        // Close out the previous track's end time
        if (prev != null && isTracking) {
            val closed = prev.copy(endedAtMs = nowMs)
            _sessionSongs.value = _sessionSongs.value.dropLast(1) + closed
        }

        _currentSong.value = newTrack
        if (isTracking) {
            _sessionSongs.value = _sessionSongs.value + newTrack
        }
    }

    /**
     * Called by [MediaNotificationListener] when active sessions change.
     * Finds Spotify's controller and registers our metadata callback.
     */
    fun onActiveSessionsChanged(controllers: List<MediaController>) {
        // Unregister from old controller
        spotifyController?.unregisterCallback(mediaCallback)
        spotifyController = null

        val spotify = controllers.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        if (spotify != null) {
            spotifyController = spotify
            spotify.registerCallback(mediaCallback)
            // Immediately read current metadata
            spotify.metadata?.let { meta ->
                val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return@let
                val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val nowMs = System.currentTimeMillis()
                // Only update if we don't already have this track (avoid overwriting
                // a track that was already captured via broadcast)
                val current = _currentSong.value
                if (current == null || current.title != title || current.artist != artist) {
                    _currentSong.value = TrackInfo(
                        title = title,
                        artist = artist,
                        startedAtMs = nowMs
                    )
                }
            }
        }
    }

    /**
     * Start tracking songs for a new session.
     * Resets the song list, registers the Spotify broadcast receiver, and
     * captures whatever is currently playing.
     *
     * If no song metadata is available immediately (common when Spotify was
     * just told to play), a background poll retries [refreshSpotifyController]
     * every [METADATA_POLL_INTERVAL_MS] for up to [METADATA_POLL_TIMEOUT_MS]
     * until a song is detected.
     */
    fun startTracking() {
        if (isTracking) return
        isTracking = true
        sessionStartMs = System.currentTimeMillis()
        _sessionSongs.value = emptyList()
        metadataPollJob?.cancel()

        // Register the broadcast receiver for Spotify metadata (no permissions needed)
        registerSpotifyReceiver()

        // Also try the MediaSession path (may fail without Notification Access)
        refreshSpotifyController()

        // Capture whatever is currently playing at session start
        val captured = _currentSong.value
        if (captured != null) {
            _sessionSongs.value = listOf(captured.copy(startedAtMs = sessionStartMs))
        } else {
            // Spotify may not have updated yet after the play command —
            // poll briefly until metadata appears via either path.
            metadataPollJob = scope.launch {
                val deadline = System.currentTimeMillis() + METADATA_POLL_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(METADATA_POLL_INTERVAL_MS)
                    if (!isTracking) return@launch
                    // Try MediaSession path each poll (broadcast is event-driven)
                    refreshSpotifyController()
                    val song = _currentSong.value
                    if (song != null && _sessionSongs.value.isEmpty()) {
                        _sessionSongs.value = listOf(song.copy(startedAtMs = sessionStartMs))
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Stop tracking songs. Closes the end time on the last song.
     *
     * If no songs were captured during the session (e.g. Spotify resumed the
     * same track with no metadata-change event), falls back to [_currentSong].
     *
     * @return the list of [TrackInfo] recorded during the session.
     */
    fun stopTracking(): List<TrackInfo> {
        if (!isTracking) return emptyList()
        isTracking = false
        metadataPollJob?.cancel()
        metadataPollJob = null

        // Unregister the broadcast receiver
        unregisterSpotifyReceiver()

        // Last-chance: re-read Spotify's MediaSession
        refreshSpotifyController()

        val endMs = System.currentTimeMillis()

        // Fallback: if no songs were captured but we know what Spotify is
        // playing, use that as the single session song.
        if (_sessionSongs.value.isEmpty() && _currentSong.value != null) {
            val song = _currentSong.value!!.copy(
                startedAtMs = sessionStartMs,
                endedAtMs = endMs
            )
            _sessionSongs.value = listOf(song)
        }

        val finalList = _sessionSongs.value.let { list ->
            if (list.isNotEmpty() && list.last().endedAtMs == null) {
                list.dropLast(1) + list.last().copy(endedAtMs = endMs)
            } else {
                list
            }
        }
        _sessionSongs.value = finalList
        return finalList
    }

    /**
     * Send a play command to Spotify.
     *
     * Strategy (two-step, most reliable on Android 8+):
     * 1. Send Spotify's own `com.spotify.music.PLAY` broadcast directly to its
     *    package — this works even if Spotify is in the background and requires
     *    no special permissions.
     * 2. Follow up with [AudioManager.dispatchMediaKeyEvent] KEYCODE_MEDIA_PLAY
     *    as a belt-and-suspenders fallback for devices where the direct broadcast
     *    is blocked.
     */
    fun sendPlayCommand() {
        // Step 1: direct Spotify play broadcast (no permissions needed)
        try {
            val spotifyIntent = Intent("com.spotify.music.PLAY").apply {
                setPackage(SPOTIFY_PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(spotifyIntent)
        } catch (_: Exception) { /* ignore if Spotify not installed */ }

        // Step 2: AudioManager media key dispatch (Android 8+ API)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val eventTime = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            )
        }
    }

    /**
     * Pause Spotify and rewind to the beginning of the current song.
     *
     * Sends KEYCODE_MEDIA_PAUSE via AudioManager, then KEYCODE_MEDIA_PREVIOUS
     * to seek back to the start of the track. Called when a free hold ends
     * (stop or cancel) so the user can replay the song from the beginning.
     */
    fun sendPauseAndRewindCommand() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val eventTime = SystemClock.uptimeMillis()

        // Pause
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        )

        // Rewind to beginning (PREVIOUS while paused seeks to start of current track)
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
        )
    }

    /**
     * Returns true if Spotify is installed on the device.
     */
    fun isSpotifyInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun registerSpotifyReceiver() {
        if (broadcastReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(SPOTIFY_METADATA_CHANGED)
            addAction(SPOTIFY_PLAYBACK_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(spotifyBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(spotifyBroadcastReceiver, filter)
        }
        broadcastReceiverRegistered = true
    }

    private fun unregisterSpotifyReceiver() {
        if (!broadcastReceiverRegistered) return
        try {
            context.unregisterReceiver(spotifyBroadcastReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
        broadcastReceiverRegistered = false
    }

    /**
     * Attempt to find Spotify's MediaController via MediaSessionManager.
     * Requires notification access to be granted — silently no-ops if not.
     */
    private fun refreshSpotifyController() {
        try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return
            val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            onActiveSessionsChanged(controllers)
        } catch (_: SecurityException) {
            // Notification access not granted — MediaSession path unavailable,
            // but the broadcast receiver path still works.
        }
    }
}

/**
 * Lightweight track info captured during a session.
 * Converted to [com.example.wags.domain.model.SpotifySong] domain model before persisting.
 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long? = null
)
