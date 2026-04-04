package com.example.wags.ui.apnea

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.data.spotify.TrackInfo
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

// ─────────────────────────────────────────────────────────────────────────────
// Physiological sanity bounds
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Any HR or SpO2 value outside these ranges is a sensor glitch and must be
 * discarded before it reaches the database or any aggregate calculation.
 *
 * HR  : 20–250 bpm
 *   Lower bound: extreme diving bradycardia can reach ~20 bpm; anything below
 *   is a BLE dropout artefact (device reports 0 when signal is lost).
 *
 * SpO2: 1–100 %
 *   Lower bound is intentionally 1, NOT 50.  Elite freedivers have documented
 *   SpO2 readings in the 25–40 % range during competitive dives, so any
 *   physiologically plausible non-zero value must be preserved.
 *   Zero is the only value that is unambiguously a "no signal" artefact.
 */
internal object PhysiologicalBounds {
    const val HR_MIN   = 20
    const val HR_MAX   = 250
    const val SPO2_MIN = 1      // reject 0 only — real extreme dives can go very low
    const val SPO2_MAX = 100

    fun isValidHr(bpm: Int): Boolean    = bpm in HR_MIN..HR_MAX
    fun isValidHr(bpm: Float): Boolean  = bpm >= HR_MIN && bpm <= HR_MAX
    fun isValidSpO2(pct: Int): Boolean  = pct in SPO2_MIN..SPO2_MAX
    fun isValidSpO2(pct: Float): Boolean = pct >= SPO2_MIN && pct <= SPO2_MAX
}

// ─────────────────────────────────────────────────────────────────────────────
// Song completion status
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tracks whether a song was completed (hold duration >= song duration) during free holds.
 * @property completedEver true if the song was completed during any past free hold.
 * @property completedWithCurrentSettings true if completed with the current 5-setting combination.
 */
data class SongCompletionStatus(
    val completedEver: Boolean = false,
    val completedWithCurrentSettings: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

data class FreeHoldActiveUiState(
    val freeHoldActive: Boolean = false,
    val showTimer: Boolean = true,
    val freeHoldFirstContractionMs: Long? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Non-null when the just-completed hold set a new PB — contains the broadest beaten category. */
    val newPersonalBest: PersonalBestResult? = null,
    /** True while the async personal-best check is still running after a hold ends. */
    val pbCheckPending: Boolean = false,
    /** The song currently playing in Spotify (null when SILENCE or Spotify not active). */
    val nowPlayingSong: TrackInfo? = null,
    // ── Song picker ──────────────────────────────────────────────────────────
    /** True when the user's Spotify account is connected (PKCE tokens present). */
    val spotifyConnected: Boolean = false,
    /** True when audio setting is MUSIC — controls whether the song picker button is shown. */
    val isMusicMode: Boolean = false,
    /** Songs previously played during breath holds (loaded from DB + enriched via API). */
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    /** True while previous songs are being loaded. */
    val loadingSongs: Boolean = false,
    /**
     * Song completion status keyed by title+artist (lowercase, trimmed).
     * Value: [SongCompletionStatus] indicating whether the song was completed ever / with current settings.
     */
    val songCompletionStatus: Map<String, SongCompletionStatus> = emptyMap(),
    /** The song the user selected from the picker (will be loaded into Spotify on Start). */
    val selectedSong: SpotifyTrackDetail? = null,
    /** True while a selected song is being loaded into Spotify playback. */
    val loadingSelectedSong: Boolean = false,
    // ── Guided hyperventilation ──────────────────────────────────────────────
    /** True when the prep type is HYPER — controls whether the guided hyper section is shown. */
    val isHyperPrep: Boolean = false,
    /** True when the user has checked the "Guided Hyperventilation" checkbox. */
    val guidedHyperEnabled: Boolean = false,
    /** Relaxed exhale phase duration in seconds. */
    val guidedRelaxedExhaleSec: Int = 0,
    /** Purge exhale phase duration in seconds. */
    val guidedPurgeExhaleSec: Int = 0,
    /** Transition phase duration in seconds. */
    val guidedTransitionSec: Int = 0,
    /** True while the guided hyper countdown dialog is showing. */
    val showGuidedCountdown: Boolean = false,
    /** True after the guided countdown has completed — button reverts to plain START. */
    val guidedCountdownComplete: Boolean = false
)

@HiltViewModel
class FreeHoldActiveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val apneaRepository: ApneaRepository,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    // Settings passed in via nav arguments — these are the source of truth for what gets saved
    val lungVolume: String = savedStateHandle.get<String>("lungVolume") ?: "FULL"
    val prepType: String   = savedStateHandle.get<String>("prepType")   ?: "NO_PREP"
    val timeOfDay: String  = savedStateHandle.get<String>("timeOfDay")  ?: "DAY"
    val posture: String    = savedStateHandle.get<String>("posture")    ?: "LAYING"
    val audio: String      = savedStateHandle.get<String>("audio")      ?: AudioSetting.SILENCE.name

    private val isMusicMode = audio == AudioSetting.MUSIC.name
    private val isHyperPrep = prepType == PrepType.HYPER.name

    private val _uiState = MutableStateFlow(
        FreeHoldActiveUiState(
            showTimer = savedStateHandle.get<Boolean>("showTimer") ?: true,
            isMusicMode = isMusicMode,
            isHyperPrep = isHyperPrep,
            guidedHyperEnabled = if (isHyperPrep) prefs.getBoolean("guided_hyper_enabled", false) else false,
            guidedRelaxedExhaleSec = prefs.getInt("guided_relaxed_exhale_sec", 0),
            guidedPurgeExhaleSec = prefs.getInt("guided_purge_exhale_sec", 0),
            guidedTransitionSec = prefs.getInt("guided_transition_sec", 0)
        )
    )

    val uiState: StateFlow<FreeHoldActiveUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        spotifyManager.currentSong,
        spotifyAuthManager.isConnected
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val state = args[0] as FreeHoldActiveUiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val song = args[3] as TrackInfo?
        val connected = args[4] as Boolean
        state.copy(
            liveHr = hr,
            liveSpO2 = spo2,
            nowPlayingSong = if (isMusicMode) song else null,
            spotifyConnected = connected
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FreeHoldActiveUiState(
            showTimer = savedStateHandle.get<Boolean>("showTimer") ?: true,
            isMusicMode = isMusicMode,
            isHyperPrep = isHyperPrep
        )
    )

    init {
        // Check for a pending repeat song (set by "Repeat This Hold" on the detail screen).
        // If present, auto-select it so the song is pre-loaded and ready when the user taps Start.
        if (isMusicMode) {
            val pendingUri = prefs.getString("pending_repeat_song_uri", null)
            val pendingTitle = prefs.getString("pending_repeat_song_title", null)
            val pendingArtist = prefs.getString("pending_repeat_song_artist", null)
            if (!pendingUri.isNullOrBlank() && !pendingTitle.isNullOrBlank() && !pendingArtist.isNullOrBlank()) {
                // Clear the pending keys immediately so they don't fire again
                prefs.edit()
                    .remove("pending_repeat_song_uri")
                    .remove("pending_repeat_song_title")
                    .remove("pending_repeat_song_artist")
                    .apply()
                // Build a SpotifyTrackDetail and auto-select it (pre-loads into Spotify)
                val repeatTrack = SpotifyTrackDetail(
                    spotifyUri = pendingUri,
                    title = pendingTitle,
                    artist = pendingArtist,
                    durationMs = 0L,
                    albumArt = null
                )
                selectSong(repeatTrack)
            }
        } else {
            // Not music mode — clear any stale pending keys
            prefs.edit()
                .remove("pending_repeat_song_uri")
                .remove("pending_repeat_song_title")
                .remove("pending_repeat_song_artist")
                .apply()
        }
    }

    // ── Guided hyperventilation ──────────────────────────────────────────────

    fun setShowTimer(show: Boolean) {
        _uiState.update { it.copy(showTimer = show) }
        prefs.edit().putBoolean("setting_show_timer", show).apply()
    }

    fun setGuidedHyperEnabled(enabled: Boolean) {
        _uiState.update { it.copy(guidedHyperEnabled = enabled, guidedCountdownComplete = false) }
        prefs.edit().putBoolean("guided_hyper_enabled", enabled).apply()
    }

    fun setGuidedRelaxedExhaleSec(sec: Int) {
        _uiState.update { it.copy(guidedRelaxedExhaleSec = sec) }
        prefs.edit().putInt("guided_relaxed_exhale_sec", sec).apply()
    }

    fun setGuidedPurgeExhaleSec(sec: Int) {
        _uiState.update { it.copy(guidedPurgeExhaleSec = sec) }
        prefs.edit().putInt("guided_purge_exhale_sec", sec).apply()
    }

    fun setGuidedTransitionSec(sec: Int) {
        _uiState.update { it.copy(guidedTransitionSec = sec) }
        prefs.edit().putInt("guided_transition_sec", sec).apply()
    }

    fun showGuidedCountdown() {
        _uiState.update { it.copy(showGuidedCountdown = true) }
    }

    fun onGuidedCountdownComplete() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
    }

    /**
     * Called when the user cancels the guided hyperventilation countdown via the back button.
     * Skips the remaining countdown, marks it complete (so the next tap goes straight to hold),
     * and immediately starts the hold — the guided hyper specifics are still recorded.
     */
    fun onGuidedCountdownCancelled() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
        startFreeHold()
    }

    private var freeHoldStartTime = 0L
    private val oximeterSamples = mutableListOf<Pair<Long, OximeterReading>>()
    private var oximeterCollectionJob: Job? = null
    /**
     * Captured at hold-start: true when the oximeter is the primary device
     * (no Polar connected). When false, any oximeter readings that arrive
     * from a background-connected oximeter are discarded at save time so
     * the record's SpO₂ fields stay null / N/A.
     */
    private var oximeterIsPrimary = false
    /**
     * Captured at hold-start so the label is guaranteed to reflect the device
     * that was actually connected when the hold began — even if it disconnects
     * before the hold ends.
     */
    private var holdStartDeviceLabel: String? = null

    fun startFreeHold() {
        // Use the actual connected Polar device ID for the RR stream — the old
        // placeholder caused startRrStream to silently fail, which could leave
        // the rrBuffer empty if the auto-started HR stream hadn't populated it yet.
        val polarDeviceId = hrDataSource.connectedPolarDeviceId()
        if (polarDeviceId != null) {
            deviceManager.startRrStream(polarDeviceId)
        }
        freeHoldStartTime = System.currentTimeMillis()
        oximeterIsPrimary = hrDataSource.isOximeterPrimaryDevice()
        holdStartDeviceLabel = hrDataSource.activeHrDeviceLabel()
        _uiState.update {
            it.copy(
                freeHoldActive = true,
                freeHoldFirstContractionMs = null
            )
        }
        oximeterSamples.clear()
        oximeterCollectionJob?.cancel()
        if (oximeterIsPrimary) {
            oximeterCollectionJob = viewModelScope.launch {
                deviceManager.oximeterReadings.collect { reading ->
                    oximeterSamples.add(System.currentTimeMillis() to reading)
                }
            }
        }
        // If MUSIC is selected, start Spotify and begin song tracking.
        // When a song was pre-selected via the picker, it was already loaded
        // and paused during selectSong(), so a simple play/resume is enough.
        // This avoids the ~500ms API latency that previously caused the song
        // to sometimes not be ready when the hold started.
        if (audio == AudioSetting.MUSIC.name) {
            val selected = _uiState.value.selectedSong
            val hasValidUri = selected != null
                && selected.spotifyUri.isNotBlank()
                && spotifyAuthManager.isConnected.value
            Log.d("FreeHold", "startFreeHold: hasValidUri=$hasValidUri, uri=${selected?.spotifyUri}, connected=${spotifyAuthManager.isConnected.value}")
            spotifyManager.startTracking()
            // The song was pre-loaded in selectSong() — just resume playback.
            // sendPlayCommand() resumes the paused track instantly.
            spotifyManager.sendPlayCommand()
        }
    }

    fun cancelFreeHold() {
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
        // Pause Spotify and rewind to start of song if MUSIC was selected
        if (audio == AudioSetting.MUSIC.name) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }
        _uiState.update { it.copy(freeHoldActive = false, freeHoldFirstContractionMs = null) }
    }

    fun recordFreeHoldFirstContraction() {
        if (_uiState.value.freeHoldFirstContractionMs != null) return
        val elapsed = System.currentTimeMillis() - freeHoldStartTime
        _uiState.update { it.copy(freeHoldFirstContractionMs = elapsed) }
        audioHapticEngine.vibrateContractionLogged()
    }

    fun stopFreeHold() {
        val duration = System.currentTimeMillis() - freeHoldStartTime
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        val firstContractionMs = _uiState.value.freeHoldFirstContractionMs
        // Capture device label at hold-end as well — use whichever is non-null,
        // preferring the hold-start label (guaranteed to reflect the device that
        // was connected when the hold began).  The hold-end label acts as a
        // fallback for the edge case where the device connected *after* the hold
        // started (e.g. late auto-connect).
        val deviceLabel = holdStartDeviceLabel ?: hrDataSource.activeHrDeviceLabel()
        // Stop Spotify tracking, collect songs, then pause + rewind to start of song
        val tracksPlayed: List<TrackInfo> = if (audio == AudioSetting.MUSIC.name) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        _uiState.update {
            it.copy(
                freeHoldActive = false,
                freeHoldFirstContractionMs = null,
                pbCheckPending = true          // prevent navigation until PB check completes
            )
        }
        audioHapticEngine.vibrateHoldEnd()
        habitRepo.sendHabitIncrement(Slot.FREE_HOLD)
        viewModelScope.launch {
            // Check broader PB categories BEFORE saving so queries compare against prior records only.
            val pbResult = apneaRepository.checkBroaderPersonalBest(
                durationMs = duration,
                lungVolume = lungVolume,
                prepType   = prepType,
                timeOfDay  = timeOfDay,
                posture    = posture,
                audio      = audio
            )
            saveFreeHoldRecord(duration, firstContractionMs, deviceLabel, tracksPlayed)
            if (pbResult != null) {
                _uiState.update { it.copy(newPersonalBest = pbResult, pbCheckPending = false) }
                habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD)
            } else {
                _uiState.update { it.copy(pbCheckPending = false) }
            }
        }
    }

    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    // ── Song picker ──────────────────────────────────────────────────────────

    /**
     * Load distinct songs previously played during any apnea session.
     * Merges DB records (free holds) with SharedPreferences history (all session types).
     * For each song with a spotifyUri, fetches track details (duration, art) from the API.
     */
    fun loadPreviousSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSongs = true) }
            // Load from DB (free holds with saved records)
            val dbSongs = apneaRepository.getDistinctSongs()
            // Load from prefs (all session types, persisted across restarts)
            val prefsSongs = loadSongHistoryFromPrefs()
            // Merge: deduplicate by URI then title+artist
            val merged = mergeSongs(dbSongs, prefsSongs)
            val details = merged.map { song ->
                var uri = song.spotifyUri
                // If no URI saved, try resolving via Spotify Search API
                if (uri == null && spotifyAuthManager.isConnected.value) {
                    uri = spotifyApiClient.searchTrack(song.title, song.artist)
                    Log.d("FreeHold", "searchTrack backfill: '$uri' for '${song.title}' by '${song.artist}'")
                }
                if (uri != null) {
                    // Try API first for duration/art; fall back to basic info from DB
                    spotifyApiClient.getTrackDetail(uri) ?: SpotifyTrackDetail(
                        spotifyUri = uri,
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                } else {
                    // No URI and no auth — show with basic info
                    SpotifyTrackDetail(
                        spotifyUri = "",
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                }
            }
            // Final dedup by title+artist after API enrichment (same track may
            // have been stored with different URIs or one with/without URI)
            val dedupedSongs = deduplicateTracks(details)

            // Compute completion status for each song
            val completionMap = mutableMapOf<String, SongCompletionStatus>()
            for (track in dedupedSongs) {
                if (track.durationMs <= 0L) continue // can't check without duration
                val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                val ever = apneaRepository.wasSongCompletedEver(
                    track.title, track.artist, track.durationMs
                )
                val withSettings = apneaRepository.wasSongCompletedWithSettings(
                    track.title, track.artist, track.durationMs,
                    lungVolume, prepType, timeOfDay, posture, audio
                )
                completionMap[key] = SongCompletionStatus(
                    completedEver = ever,
                    completedWithCurrentSettings = withSettings
                )
            }

            _uiState.update {
                it.copy(
                    previousSongs = dedupedSongs,
                    songCompletionStatus = completionMap,
                    loadingSongs = false
                )
            }
        }
    }

    private fun loadSongHistoryFromPrefs(): List<SpotifySong> {
        val raw = prefs.getString("song_history", null) ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) return@mapNotNull null
            SpotifySong(
                title = parts[0],
                artist = parts[1],
                albumArt = null,
                spotifyUri = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                startedAtMs = 0L,
                endedAtMs = 0L
            )
        }
    }

    private fun mergeSongs(dbSongs: List<SpotifySong>, prefsSongs: List<SpotifySong>): List<SpotifySong> {
        val seenByUri = mutableSetOf<String>()
        val seenByTitleArtist = mutableSetOf<String>()
        val result = mutableListOf<SpotifySong>()
        for (song in dbSongs + prefsSongs) {
            val titleArtistKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
            // Skip if we already have a song with the same title+artist
            if (!seenByTitleArtist.add(titleArtistKey)) continue
            // Also track by URI to avoid URI-based duplicates
            if (!song.spotifyUri.isNullOrBlank()) {
                if (!seenByUri.add(song.spotifyUri!!)) continue
            }
            result.add(song)
        }
        return result
    }

    /**
     * Persists played songs to SharedPreferences so they survive app restarts
     * and are available for all session types. Stores up to 50 unique songs.
     */
    private fun persistSongHistory(songs: List<SpotifySong>) {
        if (songs.isEmpty()) return
        val existing = loadSongHistoryFromPrefs().toMutableList()
        for (song in songs) {
            val titleArtistKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
            val alreadyPresent = existing.any { s ->
                "${s.title.lowercase().trim()}|${s.artist.lowercase().trim()}" == titleArtistKey
            }
            if (!alreadyPresent) existing.add(0, song)
        }
        val trimmed = existing.take(50)
        val json = trimmed.joinToString(separator = "\n") { s ->
            listOf(s.title, s.artist, s.spotifyUri ?: "").joinToString("|")
        }
        prefs.edit().putString("song_history", json).apply()
    }

    /**
     * Called when the user taps a song card in the picker.
     * Pre-loads the song into Spotify playback immediately (then pauses) so it
     * is ready to resume instantly when the user taps Start.
     */
    fun selectSong(track: SpotifyTrackDetail) {
        _uiState.update { it.copy(selectedSong = track, loadingSelectedSong = true) }
        if (track.spotifyUri.isNotBlank() && spotifyAuthManager.isConnected.value) {
            viewModelScope.launch {
                // Ensure Spotify is running before attempting playback
                spotifyManager.ensureSpotifyActive()
                val success = spotifyApiClient.startPlayback(track.spotifyUri)
                Log.d("FreeHold", "selectSong pre-load: success=$success for ${track.spotifyUri}")
                if (success) {
                    // Give Spotify a moment to start, then pause — the track is now
                    // buffered and will resume instantly on the next play command.
                    kotlinx.coroutines.delay(1_200L)
                    spotifyManager.sendPauseAndRewindCommand()
                }
                _uiState.update { it.copy(loadingSelectedSong = false) }
            }
        } else {
            _uiState.update { it.copy(loadingSelectedSong = false) }
        }
    }

    fun clearSelectedSong() {
        _uiState.update { it.copy(selectedSong = null) }
    }

    fun clearSongHistory() {
        viewModelScope.launch {
            apneaRepository.clearSongHistory()
            _uiState.update { it.copy(previousSongs = emptyList(), selectedSong = null) }
        }
    }

    private fun saveFreeHoldRecord(
        durationMs: Long,
        firstContractionMs: Long? = null,
        deviceLabel: String? = null,
        tracksPlayed: List<TrackInfo> = emptyList()
    ) {
        // Only use oximeter data when the oximeter was the primary device at hold-start.
        // When a Polar device is the primary HR source, any background-connected oximeter
        // readings are incidental resting values (typically 99 %) and must be discarded.
        val oxSnapshot = if (oximeterIsPrimary) oximeterSamples.toList() else emptyList()
        oximeterSamples.clear()

        viewModelScope.launch {
            // Only read Polar RR buffer when Polar is the primary device.
            // When the oximeter is primary, the rrBuffer may contain stale data
            // from a previous Polar session which would interleave with fresh
            // oximeter readings and produce a sawtooth pattern in the chart.
            val rrSnapshot = if (!oximeterIsPrimary) deviceManager.rrBuffer.readLast(512) else emptyList()

            // ── RR-derived HR — discard physiologically impossible beats ──────
            val rrHrValues = rrSnapshot
                .map { 60_000.0 / it }
                .filter { PhysiologicalBounds.isValidHr(it.toFloat()) }
            val minHrFromRr = rrHrValues.minOrNull()?.toFloat() ?: 0f
            val maxHrFromRr = rrHrValues.maxOrNull()?.toFloat() ?: 0f

            // ── Oximeter samples — discard glitch readings (0 bpm, 0 SpO2, etc.) ──
            val validOxHr   = oxSnapshot
                .map { it.second.heartRateBpm.toFloat() }
                .filter { PhysiologicalBounds.isValidHr(it) }
            val validOxSpO2 = oxSnapshot
                .map { it.second.spO2.toFloat() }
                .filter { PhysiologicalBounds.isValidSpO2(it) }

            val maxHrFromOx = validOxHr.maxOrNull() ?: 0f
            val lowestSpO2  = validOxSpO2.minOrNull()?.toInt()

            // Prefer Polar for HR aggregates; fall back to oximeter
            val minHr = if (minHrFromRr > 0f) minHrFromRr else validOxHr.minOrNull() ?: 0f
            val maxHr = if (maxHrFromRr > 0f) maxHrFromRr else maxHrFromOx

            val now = System.currentTimeMillis()

            // Capture guided hyper state at save time
            val guidedState = _uiState.value
            val wasGuided = guidedState.guidedHyperEnabled && isHyperPrep

            // Use the settings that were baked in at navigation time — guaranteed correct
            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp              = now,
                    durationMs             = durationMs,
                    lungVolume             = lungVolume,
                    prepType               = prepType,
                    timeOfDay              = timeOfDay,
                    posture                = posture,
                    audio                  = audio,
                    minHrBpm               = minHr,
                    maxHrBpm               = maxHr,
                    lowestSpO2             = lowestSpO2,
                    tableType              = null,
                    firstContractionMs     = firstContractionMs,
                    hrDeviceId             = deviceLabel,
                    guidedHyper            = wasGuided,
                    guidedRelaxedExhaleSec = if (wasGuided) guidedState.guidedRelaxedExhaleSec else null,
                    guidedPurgeExhaleSec   = if (wasGuided) guidedState.guidedPurgeExhaleSec else null,
                    guidedTransitionSec    = if (wasGuided) guidedState.guidedTransitionSec else null
                )
            )

            if (recordId <= 0) return@launch

            val samples = mutableListOf<FreeHoldTelemetryEntity>()

            // ── Polar RR → per-beat HR telemetry (only valid beats) ──────────
            if (rrSnapshot.isNotEmpty()) {
                var cumulativeMs = 0L
                for (rrMs in rrSnapshot) {
                    cumulativeMs += rrMs.toLong()
                    if (cumulativeMs > durationMs) break
                    val bpm = (60_000.0 / rrMs).toInt()
                    if (!PhysiologicalBounds.isValidHr(bpm)) continue   // skip glitch beat
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId     = recordId,
                            timestampMs  = freeHoldStartTime + cumulativeMs,
                            heartRateBpm = bpm,
                            spO2         = null
                        )
                    )
                }
            }

            // ── Oximeter → HR + SpO2 telemetry (only valid readings) ─────────
            for ((timestampMs, reading) in oxSnapshot) {
                if (timestampMs < freeHoldStartTime) continue
                if (timestampMs > freeHoldStartTime + durationMs) continue
                val validHr   = reading.heartRateBpm.takeIf { PhysiologicalBounds.isValidHr(it) }
                val validSpO2 = reading.spO2.takeIf { PhysiologicalBounds.isValidSpO2(it) }
                // Only save the row if at least one field is valid
                if (validHr != null || validSpO2 != null) {
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId     = recordId,
                            timestampMs  = timestampMs,
                            heartRateBpm = validHr,
                            spO2         = validSpO2
                        )
                    )
                }
            }

            if (samples.isNotEmpty()) {
                apneaRepository.saveTelemetry(samples)
            }

            // ── Save Spotify song log ─────────────────────────────────────────
            if (tracksPlayed.isNotEmpty()) {
                val songs = tracksPlayed.map { track ->
                    SpotifySong(
                        title       = track.title,
                        artist      = track.artist,
                        spotifyUri  = track.spotifyUri,
                        startedAtMs = track.startedAtMs,
                        endedAtMs   = track.endedAtMs
                    )
                }
                apneaRepository.saveSongLog(recordId, songs)
                // Also persist to SharedPreferences so song history survives restarts
                // and is available for all session types (table, advanced, etc.)
                persistSongHistory(songs)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen breath-hold active screen.
 *
 * Settings (lungVolume, prepType, timeOfDay, showTimer) are passed as nav
 * arguments so the correct values are always saved — regardless of which
 * ViewModel instance is alive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeHoldActiveScreen(
    navController: NavController,
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    posture: String,
    showTimer: Boolean,
    viewModel: FreeHoldActiveViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SessionBackHandler(enabled = state.freeHoldActive) { navController.popBackStack() }
    // Keep screen on while hold is active OR while reviewing PB results / waiting to navigate
    KeepScreenOn(enabled = state.freeHoldActive || state.pbCheckPending || state.newPersonalBest != null)

    // True once the user taps Stop — we wait for the async PB check before navigating.
    var stopRequested by remember { mutableStateOf(false) }

    // Navigate back when:
    //   • the user tapped Stop (stopRequested), AND
    //   • the async PB check has finished (pbCheckPending is false), AND
    //   • no PB dialog is showing (newPersonalBestMs is null — either no PB or dismissed).
    LaunchedEffect(stopRequested, state.freeHoldActive, state.newPersonalBest, state.pbCheckPending) {
        if (stopRequested && !state.freeHoldActive && !state.pbCheckPending && state.newPersonalBest == null) {
            navController.popBackStack()
        }
    }

    // Show the PB celebration dialog; dismiss clears the state which triggers the
    // LaunchedEffect above to pop back.
    state.newPersonalBest?.let { pbResult ->
        NewPersonalBestDialog(
            newPbMs = pbResult.durationMs,
            categoryDescription = pbResult.description,
            category = pbResult.category,
            onDismiss = { viewModel.dismissNewPersonalBest() }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Breath Hold", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.freeHoldActive) viewModel.cancelFreeHold()
                        navController.popBackStack()
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }

        if (showSongPicker) {
            SongPickerDialog(
                songs = state.previousSongs,
                isLoading = state.loadingSongs,
                selectedSong = state.selectedSong,
                loadingSelectedSong = state.loadingSelectedSong,
                songCompletionStatus = state.songCompletionStatus,
                onSongSelected = { track ->
                    viewModel.selectSong(track)
                },
                onDismiss = { showSongPicker = false }
            )
        }

        // Guided hyperventilation countdown dialog
        if (state.showGuidedCountdown) {
            GuidedHyperCountdownDialog(
                phases = GuidedHyperPhases(
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec
                ),
                onComplete = { viewModel.onGuidedCountdownComplete() },
                onCancel = { viewModel.onGuidedCountdownCancelled() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Settings summary — always visible so the user knows which settings are active
            ApneaSettingsSummaryBanner(
                lungVolume = viewModel.lungVolume,
                prepType   = viewModel.prepType,
                timeOfDay  = viewModel.timeOfDay,
                posture    = viewModel.posture,
                audio      = viewModel.audio
            )

            // Hyperventilating advice — shown only when prep type is HYPER
            if (viewModel.prepType == PrepType.HYPER.name) {
                AdviceBanner(section = AdviceSection.APNEA_HYPER)
            }

            // Now-playing banner — shown only when MUSIC is selected and a song is detected
            if (state.freeHoldActive && state.isMusicMode && state.nowPlayingSong != null) {
                NowPlayingBanner(track = state.nowPlayingSong!!)
            }

            // Selected song banner — shown before hold starts when a song was picked
            if (!state.freeHoldActive && state.selectedSong != null) {
                SelectedSongBanner(track = state.selectedSong!!) {
                    viewModel.clearSelectedSong()
                }
            }

            // Song picker / connect prompt — shown when MUSIC mode + hold not active
            if (!state.freeHoldActive && state.isMusicMode) {
                if (state.spotifyConnected) {
                    SongPickerButton(
                        onClick = {
                            viewModel.loadPreviousSongs()
                            showSongPicker = true
                        }
                    )
                } else {
                    SpotifyConnectPrompt(
                        onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }
                    )
                }
            }

            // Guided hyperventilation section — shown when prep is HYPER and hold not active
            var showGuidedHyperEditSheet by remember { mutableStateOf(false) }
            if (state.isHyperPrep && !state.freeHoldActive) {
                GuidedHyperSection(
                    enabled = state.guidedHyperEnabled,
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec,
                    onEnabledChange = { viewModel.setGuidedHyperEnabled(it) },
                    onEditClick = { showGuidedHyperEditSheet = true }
                )
            }

            if (showGuidedHyperEditSheet) {
                GuidedHyperEditSheet(
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec,
                    onRelaxedExhaleChange = { viewModel.setGuidedRelaxedExhaleSec(it) },
                    onPurgeExhaleChange = { viewModel.setGuidedPurgeExhaleSec(it) },
                    onTransitionChange = { viewModel.setGuidedTransitionSec(it) },
                    onDismiss = { showGuidedHyperEditSheet = false }
                )
            }

            // Determine whether the start button should trigger guided countdown
            val useGuidedStart = state.isHyperPrep
                && state.guidedHyperEnabled
                && !state.guidedCountdownComplete

            FreeHoldActiveContent(
                freeHoldActive = state.freeHoldActive,
                showTimer = state.showTimer,
                firstContractionMs = state.freeHoldFirstContractionMs,
                guidedHyperActive = state.isHyperPrep && state.guidedHyperEnabled,
                guidedCountdownComplete = state.guidedCountdownComplete,
                modifier = Modifier.fillMaxSize(),
                onShowTimerChange = { viewModel.setShowTimer(it) },
                onStart = {
                    if (useGuidedStart) {
                        viewModel.showGuidedCountdown()
                    } else {
                        viewModel.startFreeHold()
                    }
                },
                onFirstContraction = { viewModel.recordFreeHoldFirstContraction() },
                onStop = {
                    stopRequested = true
                    viewModel.stopFreeHold()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeHoldActiveContent(
    freeHoldActive: Boolean,
    showTimer: Boolean,
    firstContractionMs: Long?,
    guidedHyperActive: Boolean = false,
    guidedCountdownComplete: Boolean = false,
    modifier: Modifier = Modifier,
    onShowTimerChange: (Boolean) -> Unit = {},
    onStart: () -> Unit,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val holdStartWallClock = remember { mutableLongStateOf(0L) }

    LaunchedEffect(freeHoldActive) {
        if (freeHoldActive) {
            holdStartWallClock.longValue = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - holdStartWallClock.longValue
                delay(50L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Timer display ─────────────────────────────────────────────────────
        if (freeHoldActive && showTimer) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = formatElapsedMs(elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (freeHoldActive) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "HOLD",
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Main button area ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                !freeHoldActive -> {
                    // Determine button label based on guided hyper state
                    val showHyperStart = guidedHyperActive && !guidedCountdownComplete

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onStart,
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .fillMaxHeight(0.75f),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonSuccess,
                                contentColor = TextPrimary
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "START",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                if (showHyperStart) {
                                    Text(
                                        text = "HYPER",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "Show timer",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Checkbox(checked = showTimer, onCheckedChange = onShowTimerChange)
                        }
                    }
                }

                freeHoldActive && firstContractionMs == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onFirstContraction,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ReadinessOrange,
                                contentColor = TextPrimary
                            )
                        ) {
                            Text(
                                text = "First\nContraction",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonDanger,
                                contentColor = TextPrimary
                            )
                        ) {
                            Text(
                                text = "Stop",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonDanger,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsedMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0)
        "${minutes}m ${seconds}s"
    else
        "${seconds}.${centis.toString().padStart(2, '0')}s"
}

// ─────────────────────────────────────────────────────────────────────────────
// Guided Hyperventilation Section
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Checkbox + edit icon for guided hyperventilation.
 * Shown above the start button when prep type is HYPER.
 * The edit icon opens a bottom sheet to configure the phase durations.
 */
@Composable
private fun GuidedHyperSection(
    enabled: Boolean,
    relaxedExhaleSec: Int,
    purgeExhaleSec: Int,
    transitionSec: Int,
    onEnabledChange: (Boolean) -> Unit,
    onEditClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEnabledChange(!enabled) }
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.size(32.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = BackgroundDark
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Guided Hyperventilation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            // Edit icon — always visible so user can configure even when disabled
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit guided hyperventilation settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Summary of configured durations when enabled
        if (enabled) {
            val parts = buildList {
                if (relaxedExhaleSec > 0) add("Relaxed ${relaxedExhaleSec}s")
                if (purgeExhaleSec > 0) add("Purge ${purgeExhaleSec}s")
                if (transitionSec > 0) add("Transition ${transitionSec}s")
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 36.dp, bottom = 2.dp)
                )
            }
        }
    }
}

/**
 * Bottom sheet for editing the three guided hyperventilation phase durations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedHyperEditSheet(
    relaxedExhaleSec: Int,
    purgeExhaleSec: Int,
    transitionSec: Int,
    onRelaxedExhaleChange: (Int) -> Unit,
    onPurgeExhaleChange: (Int) -> Unit,
    onTransitionChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Guided Hyperventilation",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                "Set the duration (in seconds) for each phase. Set to 0 to skip a phase.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            GuidedHyperPhaseRow(
                label = "Relaxed Exhale",
                value = relaxedExhaleSec,
                onValueChange = onRelaxedExhaleChange
            )
            GuidedHyperPhaseRow(
                label = "Purge Exhale",
                value = purgeExhaleSec,
                onValueChange = onPurgeExhaleChange
            )
            GuidedHyperPhaseRow(
                label = "Transition",
                value = transitionSec,
                onValueChange = onTransitionChange
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text("Done")
            }
        }
    }
}

/**
 * A single row in the edit sheet: label on the left, numeric input on the right.
 */
@Composable
private fun GuidedHyperPhaseRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(40.dp)
                .border(1.dp, SurfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = if (value == 0) "" else value.toString(),
                onValueChange = { text ->
                    val parsed = text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                    onValueChange(parsed)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center,
                    color = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                cursorBrush = SolidColor(TextPrimary)
            )
        }
    }
}

