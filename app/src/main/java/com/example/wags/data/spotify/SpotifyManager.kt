package com.example.wags.data.spotify

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.view.KeyEvent
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Spotify integration:
 *  1. Sends a play command to Spotify when a session starts with MUSIC selected.
 *  2. Detects the currently-playing song via MediaSession API (works with all
 *     modern Spotify versions — does not rely on deprecated broadcast intents).
 *  3. Tracks which songs played during a session.
 *
 * Song detection uses [MediaSessionManager.getActiveSessions] with the
 * [MediaNotificationListener] component token, then attaches a
 * [MediaController.Callback] to Spotify's session to receive metadata updates.
 *
 * Requires the user to grant Notification Access to this app in
 * Settings → Apps → Special app access → Notification access.
 */
@Singleton
class SpotifyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
    }

    // Currently tracked song during an active session
    private val _currentSong = MutableStateFlow<TrackInfo?>(null)
    val currentSong: StateFlow<TrackInfo?> = _currentSong.asStateFlow()

    // Accumulated songs for the current session
    private val _sessionSongs = MutableStateFlow<List<TrackInfo>>(emptyList())
    val sessionSongs: StateFlow<List<TrackInfo>> = _sessionSongs.asStateFlow()

    private var isTracking = false
    private var sessionStartMs: Long = 0L

    // Active MediaController for Spotify (set by MediaNotificationListener)
    private var spotifyController: MediaController? = null

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val nowMs = System.currentTimeMillis()

            val newTrack = TrackInfo(
                title = title,
                artist = artist,
                startedAtMs = nowMs
            )

            // Close out the previous track's end time
            val prev = _currentSong.value
            if (prev != null && isTracking) {
                val closed = prev.copy(endedAtMs = nowMs)
                _sessionSongs.value = _sessionSongs.value.dropLast(1) + closed
            }

            _currentSong.value = newTrack
            if (isTracking) {
                _sessionSongs.value = _sessionSongs.value + newTrack
            }
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
                _currentSong.value = TrackInfo(
                    title = title,
                    artist = artist,
                    startedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * Start tracking songs for a new session.
     * Resets the song list and captures whatever is currently playing.
     */
    fun startTracking() {
        if (isTracking) return
        isTracking = true
        sessionStartMs = System.currentTimeMillis()
        _sessionSongs.value = emptyList()

        // Try to connect to Spotify's MediaSession if not already connected
        refreshSpotifyController()

        // Capture whatever is currently playing at session start
        _currentSong.value?.let { current ->
            _sessionSongs.value = listOf(current.copy(startedAtMs = sessionStartMs))
        }
    }

    /**
     * Stop tracking songs. Closes the end time on the last song.
     *
     * @return the list of [TrackInfo] recorded during the session.
     */
    fun stopTracking(): List<TrackInfo> {
        if (!isTracking) return emptyList()
        isTracking = false

        val endMs = System.currentTimeMillis()
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
            val spotifyIntent = android.content.Intent("com.spotify.music.PLAY").apply {
                setPackage(SPOTIFY_PACKAGE)
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
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

    /**
     * Attempt to find Spotify's MediaController via MediaSessionManager.
     * Requires notification access to be granted.
     */
    private fun refreshSpotifyController() {
        try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return
            val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            onActiveSessionsChanged(controllers)
        } catch (_: SecurityException) {
            // Notification access not granted — song detection unavailable
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
