package com.example.wags.data.spotify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
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
    @ApplicationContext private val context: Context,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager
) {

    companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val KDE_CONNECT_PACKAGE = "org.kde.kdeconnect_tp"
        /** Max time (ms) to poll for Spotify metadata after sending a play command. */
        private const val METADATA_POLL_TIMEOUT_MS = 2_000L
        /** Interval (ms) between metadata poll attempts. */
        private const val METADATA_POLL_INTERVAL_MS = 250L
        /** Max attempts to retry startPlayback when Spotify was just launched. */
        private const val PRELOAD_MAX_ATTEMPTS = 6
        /** Delay (ms) between startPlayback retry attempts. */
        private const val PRELOAD_RETRY_DELAY_MS = 1_500L
        /** Delay (ms) after startPlayback succeeds before pausing (lets Spotify buffer). */
        private const val PRELOAD_PAUSE_DELAY_MS = 1_200L
        /** Delay (ms) after launching Spotify before bringing our app back to foreground. */
        private const val BRING_APP_BACK_DELAY_MS = 500L
        /** Delay (ms) after waking Spotify's player (via media key) before retrying Web API. */
        private const val WAKE_PLAYER_DELAY_MS = 2_000L

        // Spotify legacy broadcast actions (no permissions required)
        private const val SPOTIFY_METADATA_CHANGED = "com.spotify.music.metadatachanged"
        private const val SPOTIFY_PLAYBACK_CHANGED = "com.spotify.music.playbackstatechanged"

        // SharedPreferences for persisting the song picker cache across app restarts
        private const val PREFS_NAME = "spotify_song_cache"
        private const val KEY_SONG_CACHE = "song_picker_cache"
        /** Maximum number of songs to persist in the cache. */
        private const val MAX_CACHED_SONGS = 100
    }

    /** Internal scope for fire-and-forget work (metadata polling). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Currently tracked song during an active session
    private val _currentSong = MutableStateFlow<TrackInfo?>(null)
    val currentSong: StateFlow<TrackInfo?> = _currentSong.asStateFlow()

    // Accumulated songs for the current session
    private val _sessionSongs = MutableStateFlow<List<TrackInfo>>(emptyList())
    val sessionSongs: StateFlow<List<TrackInfo>> = _sessionSongs.asStateFlow()

    // ── Song picker cache ─────────────────────────────────────────────────────
    // Enriched song list cached in memory AND in SharedPreferences so the picker
    // dialog can open instantly on subsequent visits — even after an app restart.
    // Invalidated when a session ends (new songs may have been played).
    private val cachePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _songPickerCache = MutableStateFlow<List<SpotifyTrackDetail>?>(
        loadSongPickerCacheFromPrefs()
    )
    val songPickerCache: StateFlow<List<SpotifyTrackDetail>?> = _songPickerCache.asStateFlow()

    /** Update the song picker cache in memory and persist it to SharedPreferences. */
    fun updateSongPickerCache(songs: List<SpotifyTrackDetail>) {
        _songPickerCache.value = songs
        persistSongPickerCache(songs)
    }

    /** Serialize the song list to JSON and store it in SharedPreferences. */
    private fun persistSongPickerCache(songs: List<SpotifyTrackDetail>) {
        try {
            val arr = JSONArray()
            songs.take(MAX_CACHED_SONGS).forEach { track ->
                arr.put(JSONObject().apply {
                    put("uri", track.spotifyUri)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("durationMs", track.durationMs)
                    if (track.albumArt != null) put("albumArt", track.albumArt)
                })
            }
            cachePrefs.edit().putString(KEY_SONG_CACHE, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w("SpotifyMgr", "Failed to persist song picker cache", e)
        }
    }

    /** Deserialize the song list from SharedPreferences. Returns null if nothing is stored. */
    private fun loadSongPickerCacheFromPrefs(): List<SpotifyTrackDetail>? {
        val raw = cachePrefs.getString(KEY_SONG_CACHE, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<SpotifyTrackDetail>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    SpotifyTrackDetail(
                        spotifyUri = obj.getString("uri"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        durationMs = obj.getLong("durationMs"),
                        albumArt = obj.optString("albumArt").takeIf { it.isNotBlank() }
                    )
                )
            }
            result.ifEmpty { null }
        } catch (e: Exception) {
            Log.w("SpotifyMgr", "Failed to load song picker cache from prefs", e)
            null
        }
    }

    private var isTracking = false
    private var sessionStartMs: Long = 0L
    private var metadataPollJob: Job? = null
    private var broadcastReceiverRegistered = false

    // Active MediaController for Spotify (set by MediaNotificationListener)
    private var spotifyController: MediaController? = null

    // Active MediaController for KDE Connect / remote media (set by MediaNotificationListener)
    private var remoteMediaController: MediaController? = null

    // Exposed as StateFlow so ViewModels can reactively observe remote media availability
    private val _remoteMediaAvailable = MutableStateFlow(false)
    val remoteMediaAvailable: StateFlow<Boolean> = _remoteMediaAvailable.asStateFlow()

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
                    // Log all extras to diagnose what Spotify sends
                    val keys = intent.extras?.keySet()?.joinToString() ?: "none"
                    Log.d("SpotifyMgr", "metadatachanged extras keys: $keys")
                    // Spotify broadcasts the track ID as "id" (e.g. "spotify:track:xxxxx")
                    val trackId = intent.getStringExtra("id")
                    val spotifyUri = if (trackId != null && !trackId.startsWith("spotify:")) {
                        "spotify:track:$trackId"
                    } else {
                        trackId
                    }
                    Log.d("SpotifyMgr", "metadatachanged: title=$title, artist=$artist, trackId=$trackId, spotifyUri=$spotifyUri")
                    val nowMs = System.currentTimeMillis()
                    handleNewTrack(title, artist, nowMs, spotifyUri)
                }
                SPOTIFY_PLAYBACK_CHANGED -> {
                    // Playback state changed (play/pause/skip).
                    // When playing=false and we are actively tracking, the current
                    // song has ended — send NEXT so Spotify advances to the next
                    // track in its queue, keeping music going for the whole session.
                    if (isTracking) {
                        val playing = intent.getBooleanExtra("playing", true)
                        if (!playing) {
                            Log.d("SpotifyMgr", "playbackstatechanged: playing=false during tracking — sending NEXT")
                            sendNextTrackCommand()
                        }
                    }
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
            handleNewTrack(title, artist, nowMs, spotifyUri = null)
        }
    }

    /**
     * Common handler for new track metadata from either the broadcast receiver
     * or the MediaSession callback.
     *
     * When no [spotifyUri] is provided (broadcast didn't include "id"), and the
     * user is authenticated, a background search via the Spotify Web API resolves
     * the URI so it can be persisted for future song-picker use.
     */
    private fun handleNewTrack(title: String, artist: String, nowMs: Long, spotifyUri: String? = null) {
        val newTrack = TrackInfo(
            title = title,
            artist = artist,
            spotifyUri = spotifyUri,
            startedAtMs = nowMs
        )

        // Avoid duplicate entries if both broadcast and MediaSession fire
        val prev = _currentSong.value
        if (prev != null && prev.title == title && prev.artist == artist) {
            // Same track — but if the previous entry lacked a URI and we now have one, update it
            if (spotifyUri != null && prev.spotifyUri == null) {
                val updated = prev.copy(spotifyUri = spotifyUri)
                _currentSong.value = updated
                if (isTracking && _sessionSongs.value.isNotEmpty()) {
                    _sessionSongs.value = _sessionSongs.value.dropLast(1) + updated
                }
            }
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

        // If no URI was provided and user is authenticated, resolve via Search API
        if (spotifyUri == null && spotifyAuthManager.isConnected.value) {
            scope.launch {
                val resolved = spotifyApiClient.searchTrack(title, artist)
                Log.d("SpotifyMgr", "searchTrack resolved: $resolved for '$title' by '$artist'")
                if (resolved != null) {
                    backfillUri(title, artist, resolved)
                }
            }
        }
    }

    /**
     * Backfill a resolved Spotify URI into [_currentSong] and [_sessionSongs]
     * for the track matching [title]+[artist].
     */
    private fun backfillUri(title: String, artist: String, uri: String) {
        val current = _currentSong.value
        if (current != null && current.title == title && current.artist == artist && current.spotifyUri == null) {
            _currentSong.value = current.copy(spotifyUri = uri)
        }
        _sessionSongs.value = _sessionSongs.value.map { track ->
            if (track.title == title && track.artist == artist && track.spotifyUri == null) {
                track.copy(spotifyUri = uri)
            } else track
        }
    }

    /**
     * Called by [MediaNotificationListener] when active sessions change.
     * Finds Spotify's controller and registers our metadata callback.
     */
    fun onActiveSessionsChanged(controllers: List<MediaController>) {
        // Unregister from old controllers
        spotifyController?.unregisterCallback(mediaCallback)
        spotifyController = null
        remoteMediaController = null

        // Log all active sessions for debugging
        Log.d("SpotifyMgr", "onActiveSessionsChanged: ${controllers.size} active sessions")
        for (ctrl in controllers) {
            val meta = ctrl.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            Log.d("SpotifyMgr", "  session: pkg=${ctrl.packageName}, title=$title, artist=$artist")
        }

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

        // Track a remote media session for movie auto-control.
        // Strategy: pick the first active session that is NOT our own app and NOT Spotify.
        // This covers KDE Connect (any package name variant) and any other remote media app.
        // Note: we do NOT require metadata != null — KDE Connect may expose transport
        // controls without publishing track metadata.
        val remote = controllers.firstOrNull {
            it.packageName != SPOTIFY_PACKAGE &&
            it.packageName != context.packageName
        }
        if (remote != null) {
            remoteMediaController = remote
            _remoteMediaAvailable.value = true
            val meta = remote.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            Log.d("SpotifyMgr", "Remote media session discovered: pkg=${remote.packageName}, title=$title, playbackState=${remote.playbackState}")
        } else {
            _remoteMediaAvailable.value = false
            Log.d("SpotifyMgr", "No remote media session found")
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

        // Capture whatever is currently playing at session start.
        // IMPORTANT: Also update _currentSong so that handleNewTrack() uses the
        // corrected startedAtMs when closing this song (otherwise it would use
        // the stale time from when the song was pre-loaded in selectSong()).
        val captured = _currentSong.value
        if (captured != null) {
            val corrected = captured.copy(startedAtMs = sessionStartMs)
            _currentSong.value = corrected
            _sessionSongs.value = listOf(corrected)
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
                        val correctedSong = song.copy(startedAtMs = sessionStartMs)
                        _currentSong.value = correctedSong
                        _sessionSongs.value = listOf(correctedSong)
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

        // Invalidate in-memory song picker cache — new songs may have been played.
        // The persisted SharedPreferences cache is intentionally kept so the picker
        // still opens quickly on the next visit; it will be refreshed by loadPreviousSongs().
        _songPickerCache.value = null

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
     * Send a pause command only (no rewind) via AudioManager.
     *
     * Sends KEYCODE_MEDIA_PAUSE via AudioManager. Falls back to AudioManager
     * if no targeted remote controller is available.
     * Used for movie auto-control where rewinding to the start would be undesirable.
     */
    fun sendPauseCommand() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        )
    }

    // ── Remote media control (KDE Connect / movie auto-control) ──────────────

    /**
     * Returns true if a remote media session (e.g. KDE Connect) is currently active.
     * Used by the UI to indicate whether movie auto-control can reach a target.
     */
    fun hasRemoteMediaSession(): Boolean = _remoteMediaAvailable.value

    /**
     * Send a play command directly to the remote media controller (KDE Connect).
     *
     * Uses [MediaController.TransportControls.play] which targets the specific
     * session, unlike [AudioManager.dispatchMediaKeyEvent] which routes to
     * whichever session Android considers "active".
     *
     * Refreshes the controller list before sending to catch any newly-appeared sessions.
     * Falls back to [sendPlayCommand] (AudioManager) if no remote controller is available.
     */
    fun sendRemotePlayCommand() {
        // Refresh sessions in case a new one appeared since last check
        refreshRemoteController()
        val controller = remoteMediaController
        if (controller != null) {
            Log.d("SpotifyMgr", "sendRemotePlayCommand: targeting ${controller.packageName}, playbackState=${controller.playbackState}")
            try {
                controller.transportControls.play()
                Log.d("SpotifyMgr", "sendRemotePlayCommand: play() sent successfully")
            } catch (e: Exception) {
                Log.e("SpotifyMgr", "sendRemotePlayCommand: play() failed", e)
            }
        } else {
            Log.d("SpotifyMgr", "sendRemotePlayCommand: no remote controller, falling back to AudioManager")
            sendPlayCommand()
        }
    }

    /**
     * Send a pause command directly to the remote media controller (KDE Connect).
     *
     * Uses [MediaController.TransportControls.pause] which targets the specific
     * session. Does NOT rewind — the movie resumes from where it was paused.
     *
     * Refreshes the controller list before sending to catch any newly-appeared sessions.
     * Falls back to [sendPauseCommand] (AudioManager) if no remote controller is available.
     */
    fun sendRemotePauseCommand() {
        // Refresh sessions in case a new one appeared since last check
        refreshRemoteController()
        val controller = remoteMediaController
        if (controller != null) {
            Log.d("SpotifyMgr", "sendRemotePauseCommand: targeting ${controller.packageName}, playbackState=${controller.playbackState}")
            try {
                controller.transportControls.pause()
                Log.d("SpotifyMgr", "sendRemotePauseCommand: pause() sent successfully")
            } catch (e: Exception) {
                Log.e("SpotifyMgr", "sendRemotePauseCommand: pause() failed", e)
            }
        } else {
            Log.d("SpotifyMgr", "sendRemotePauseCommand: no remote controller, falling back to AudioManager")
            sendPauseCommand()
        }
    }

    /**
     * Refresh the remote media controller by querying MediaSessionManager directly.
     * Called before sending play/pause to catch sessions that appeared after the
     * last onActiveSessionsChanged callback.
     */
    private fun refreshRemoteController() {
        try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return
            val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            // Update remote controller if found
            val remote = controllers.firstOrNull {
                it.packageName != SPOTIFY_PACKAGE &&
                it.packageName != context.packageName
            }
            if (remote != null) {
                remoteMediaController = remote
                _remoteMediaAvailable.value = true
            }
        } catch (_: SecurityException) {
            // Notification access not granted
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
     * Send a NEXT track command to Spotify via AudioManager media key dispatch.
     * Called when the current song ends during an active session so playback
     * continues seamlessly with the next track in Spotify's queue.
     */
    fun sendNextTrackCommand() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        )
        Log.d("SpotifyMgr", "sendNextTrackCommand: dispatched MEDIA_NEXT")
    }

    /**
     * Returns true if Spotify is installed on the device.
     */
    fun isSpotifyInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null

    /**
     * Ensure Spotify is running before attempting playback.
     *
     * Checks whether Spotify has an active MediaSession (meaning it's alive
     * and ready to accept commands). If not, launches Spotify via its package
     * launch intent and waits up to [timeoutMs] for it to become active.
     *
     * After launching Spotify, immediately brings our app back to the
     * foreground so the user stays in our app.
     *
     * This is a suspend function — call it from a coroutine before
     * [SpotifyApiClient.startPlayback] to guarantee Spotify is ready.
     */
    suspend fun ensureSpotifyActive(timeoutMs: Long = 4_000L) {
        // Quick check: if we already have a MediaController for Spotify, it's active
        if (spotifyController != null) return

        // Try refreshing — maybe it's running but we haven't picked it up yet
        refreshSpotifyController()
        if (spotifyController != null) return

        // Spotify is not active — launch it
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                Log.d("SpotifyMgr", "Launched Spotify to activate it")
            } catch (e: Exception) {
                Log.w("SpotifyMgr", "Failed to launch Spotify", e)
                return
            }
        } else {
            Log.w("SpotifyMgr", "Spotify not installed — cannot ensure active")
            return
        }

        // Give Spotify a moment to display, then bring our app back to foreground
        delay(BRING_APP_BACK_DELAY_MS)
        bringAppToForeground()

        // Poll until Spotify's MediaSession appears or timeout
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(500L)
            refreshSpotifyController()
            if (spotifyController != null) {
                Log.d("SpotifyMgr", "Spotify became active after launch")
                return
            }
        }
        Log.w("SpotifyMgr", "Spotify did not become active within ${timeoutMs}ms — proceeding anyway")
    }

    /**
     * Pre-load a track into Spotify playback, then pause it so it's ready
     * to resume instantly when the user starts a session.
     *
     * Handles:
     *  - Ensuring Spotify is active (and bringing our app back to foreground)
     *  - Waking Spotify's player via media key so it registers as a Web API device
     *  - Retrying [SpotifyApiClient.startPlayback] when it fails due to
     *    Spotify not having registered its device yet (common after fresh launch)
     *  - Pausing and rewinding after successful load
     *
     * @return true if the track was successfully loaded and paused.
     */
    suspend fun preloadTrack(trackUri: String): Boolean {
        val wasActive = spotifyController != null
        ensureSpotifyActive()

        // If Spotify was just launched, its Web API device won't be registered yet.
        // Send a media key "play" to wake up Spotify's player — this triggers
        // Spotify to register itself as an active device on its servers.
        if (!wasActive) {
            Log.d("SpotifyMgr", "preloadTrack: Spotify was just launched, waking player via media key")
            sendPlayCommand()
            delay(WAKE_PLAYER_DELAY_MS)
            // Pause whatever Spotify started playing — we'll replace it with the
            // chosen track via the Web API below.
            sendPauseAndRewindCommand()
        }

        // Retry startPlayback — Spotify may need a moment to register its
        // device after being launched, especially on first play.
        var success = false
        for (attempt in 1..PRELOAD_MAX_ATTEMPTS) {
            success = spotifyApiClient.startPlayback(trackUri)
            if (success) break
            Log.d("SpotifyMgr", "preloadTrack: startPlayback attempt $attempt failed, retrying in ${PRELOAD_RETRY_DELAY_MS}ms...")
            delay(PRELOAD_RETRY_DELAY_MS.toLong())
        }

        if (success) {
            delay(PRELOAD_PAUSE_DELAY_MS)
            sendPauseAndRewindCommand()
            // Queue a follow-up song so playback continues when the selected track ends.
            // Try Spotify Recommendations first; fall back to a random song from the cache.
            scope.launch(Dispatchers.IO) {
                queueFollowUpSong(trackUri)
            }
        } else {
            Log.w("SpotifyMgr", "preloadTrack: failed after $PRELOAD_MAX_ATTEMPTS attempts for $trackUri")
        }

        return success
    }

    /**
     * Queue a follow-up song after [selectedTrackUri] so that playback continues
     * automatically when the selected track ends.
     *
     * Strategy:
     *  1. Ask the Spotify Recommendations API for a related track (seed = selected track).
     *  2. If that fails, pick a random track from the in-memory song picker cache,
     *     excluding the selected track itself.
     *
     * The queued track is added via `POST /v1/me/player/queue` — it will play
     * automatically after the selected track finishes.
     */
    private suspend fun queueFollowUpSong(selectedTrackUri: String) {
        // 1. Try Spotify Recommendations
        val recommendedUri = spotifyApiClient.getRecommendation(selectedTrackUri)
        if (recommendedUri != null) {
            val queued = spotifyApiClient.addToQueue(recommendedUri)
            Log.d("SpotifyMgr", "queueFollowUpSong: recommendation queued=$queued uri=$recommendedUri")
            if (queued) return
        }

        // 2. Fallback: random song from the picker cache (excluding the selected track)
        val cache = _songPickerCache.value
        if (cache.isNullOrEmpty()) {
            Log.d("SpotifyMgr", "queueFollowUpSong: no cache available, skipping fallback")
            return
        }
        val candidates = cache.filter { it.spotifyUri != selectedTrackUri }
        if (candidates.isEmpty()) {
            Log.d("SpotifyMgr", "queueFollowUpSong: only one song in cache, skipping fallback")
            return
        }
        val fallbackUri = candidates.random().spotifyUri
        val queued = spotifyApiClient.addToQueue(fallbackUri)
        Log.d("SpotifyMgr", "queueFollowUpSong: fallback queued=$queued uri=$fallbackUri")
    }

    /**
     * Bring our app back to the foreground after another app (e.g. Spotify)
     * was launched and stole focus. Uses the package launch intent with
     * FLAG_ACTIVITY_REORDER_TO_FRONT to avoid recreating the activity.
     */
    private fun bringAppToForeground() {
        val appIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (appIntent != null) {
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                context.startActivity(appIntent)
                Log.d("SpotifyMgr", "Brought app back to foreground")
            } catch (e: Exception) {
                Log.w("SpotifyMgr", "Failed to bring app to foreground", e)
            }
        }
    }

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
