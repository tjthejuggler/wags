package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.GuidedAudioEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.db.entity.TelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.DrillContext
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.trophyCount
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.MinBreathHoldResult
import com.example.wags.domain.usecase.apnea.MinBreathPhase
import com.example.wags.domain.usecase.apnea.MinBreathState
import com.example.wags.domain.usecase.apnea.MinBreathStateMachine
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.usecase.apnea.forecast.CategoryForecast
import com.example.wags.domain.usecase.apnea.forecast.ForecastConfidence
import com.example.wags.domain.usecase.apnea.forecast.ForecastSettings
import com.example.wags.domain.usecase.apnea.forecast.ForecastStatus
import com.example.wags.domain.usecase.apnea.forecast.RecordForecast
import com.example.wags.domain.usecase.apnea.forecast.RecordForecastCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named

// ── UI state ────────────────────────────────────────────────────────────────

data class MinBreathUiState(
    val sessionState: MinBreathState = MinBreathState(),
    val sessionDurationSec: Int = 300,
    val isSessionActive: Boolean = false,
    val completedRecordId: Long? = null,
    // Live vitals
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    // Past sessions
    val pastDurations: List<DurationHistory> = emptyList(),
    // 5 standard apnea settings
    val lungVolume: String = "FULL",
    val prepType: String = "NO_PREP",
    val timeOfDay: String = "DAY",
    val posture: String = "LAYING",
    val audio: String = "SILENCE",
    // Filter state ("" = all, specific value = filter to that value)
    val filterLungVolume: String = "",
    val filterPrepType: String = "",
    val filterTimeOfDay: String = "",
    val filterPosture: String = "",
    val filterAudio: String = "",
    // ── Voice / vibration toggles ─────────────────────────────────────────────
    val voiceEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    // Spotify
    val spotifyConnected: Boolean = false,
    val isMusicMode: Boolean = false,
    val isMovieMode: Boolean = false,
    val movieAutoControl: Boolean = false,
    val remoteMediaAvailable: Boolean = false,
    val isGuidedMode: Boolean = false,
    val guidedAudios: List<GuidedAudioEntity> = emptyList(),
    val guidedSelectedId: Long = -1L,
    val guidedSelectedName: String = "",
    val guidedCompletionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSong: SpotifyTrackDetail? = null,
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
    val guidedCountdownComplete: Boolean = false,
    /** True when the user wants the guided MP3 to start playing during the hyper countdown. */
    val startMp3WithHyper: Boolean = false,
    // ── Personal best celebration ──────────────────────────────────────────────
    val newPersonalBest: PersonalBestResult? = null,
    // ── Record-breaking forecast ──────────────────────────────────────────────
    /** Forecast for the current settings combination. Null when insufficient data. */
    val recordForecast: RecordForecast? = null
)

data class DurationHistory(
    val durationSec: Int,
    val bestHoldPct: Double,
    val sessionCount: Int,
    val bestRecordId: Long = -1L
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class MinBreathViewModel @Inject constructor(
    private val stateMachine: MinBreathStateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val hrDataSource: HrDataSource,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(MinBreathUiState())

    val uiState: StateFlow<MinBreathUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        stateMachine.state,
        spotifyAuthManager.isConnected,
        spotifyManager.remoteMediaAvailable
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val ui = args[0] as MinBreathUiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val session = args[3] as MinBreathState
        val connected = args[4] as Boolean
        val remoteAvail = args[5] as Boolean
        ui.copy(
            sessionState = session, liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected,
            remoteMediaAvailable = remoteAvail
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MinBreathUiState()
    )

    // Telemetry collection
    private data class TelemetrySample(val timestampMs: Long, val hr: Int?, val spO2: Int?)
    private val telemetrySamples = mutableListOf<TelemetrySample>()
    private var telemetryJob: Job? = null
    private var sessionStartMs: Long = 0L

    // Per-breath-period duration tracking (indexed by hold number that preceded the breath)
    private val breathDurations = mutableMapOf<Int, Long>()

    // Spotify tracks played during the session (captured at stop time)
    private var trackedSongs: List<SpotifySong> = emptyList()

    /** Bumped when settings change — triggers forecast recompute. */
    private val _forecastRefreshTrigger = MutableStateFlow(0)

    /** Call after any settings change to recompute the forecast. */
    private fun refreshForecast() { _forecastRefreshTrigger.value++ }

    init {
        // Restore persisted session duration
        val savedDuration = prefs.getInt("min_breath_session_duration_sec", 300)

        // Read persisted apnea settings
        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
        val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"
        val savedAudio      = prefs.getString("setting_audio", "SILENCE") ?: "SILENCE"

        val savedMovieAutoControl = prefs.getBoolean("setting_movie_auto_control", false)
        val isHyperPrep = savedPrepType == PrepType.HYPER.name

        _uiState.update {
            it.copy(
                sessionDurationSec = savedDuration,
                lungVolume  = savedLungVolume,
                prepType    = savedPrepType,
                timeOfDay   = TimeOfDay.fromCurrentTime().name,
                posture     = savedPosture,
                audio       = savedAudio,
                isMusicMode = savedAudio == AudioSetting.MUSIC.name,
                isMovieMode = savedAudio == AudioSetting.MOVIE.name,
                movieAutoControl = savedMovieAutoControl && savedAudio == AudioSetting.MOVIE.name,
                isGuidedMode = savedAudio == AudioSetting.GUIDED.name,
                guidedSelectedId = guidedAudioManager.selectedId,
                isHyperPrep = isHyperPrep,
                guidedHyperEnabled = if (isHyperPrep) prefs.getBoolean("guided_hyper_enabled", false) else false,
                guidedRelaxedExhaleSec = prefs.getInt("guided_relaxed_exhale_sec", 0),
                guidedPurgeExhaleSec = prefs.getInt("guided_purge_exhale_sec", 0),
                guidedTransitionSec = prefs.getInt("guided_transition_sec", 0),
                voiceEnabled = audioHapticEngine.voiceEnabled,
                vibrationEnabled = audioHapticEngine.vibrationEnabled
            )
        }

        // Collect guided audio library from DB
        viewModelScope.launch {
            guidedAudioManager.allAudios.collect { audios ->
                _uiState.update { it.copy(guidedAudios = audios) }
            }
        }
        // Load the selected guided audio name from DB
        viewModelScope.launch {
            val name = guidedAudioManager.getSelectedName()
            _uiState.update { it.copy(
                guidedSelectedId = guidedAudioManager.selectedId,
                guidedSelectedName = name
            ) }
        }

        // Observe state machine — only fire audio for COMPLETE
        viewModelScope.launch {
            var previousPhase = MinBreathPhase.IDLE
            stateMachine.state.collect { state ->
                if (state.phase == MinBreathPhase.COMPLETE && previousPhase != MinBreathPhase.COMPLETE) {
                    audioHapticEngine.announceSessionComplete()
                    // Auto-save when session completes naturally (timer ran out)
                    if (_uiState.value.isSessionActive) {
                        // Pause movie if auto-control enabled
                        if (_uiState.value.movieAutoControl) {
                            spotifyManager.sendRemotePauseCommand()
                        }
                        // Stop Spotify if MUSIC was selected — capture tracked songs
                        trackedSongs = if (_uiState.value.isMusicMode) {
                            val tracks = spotifyManager.stopTracking()
                            spotifyManager.sendPauseAndRewindCommand()
                            tracks.map { t ->
                                SpotifySong(t.title, t.artist, null, t.spotifyUri, t.startedAtMs, t.endedAtMs)
                            }
                        } else emptyList()
                        // Stop guided audio if GUIDED was selected
                        if (_uiState.value.isGuidedMode) {
                            guidedAudioManager.stopPlayback()
                        }
                        telemetryJob?.cancel()
                        // Persist song history to SharedPreferences
                        if (trackedSongs.isNotEmpty()) {
                            persistSongHistory(trackedSongs)
                        }
                        val recordId = saveSession(state)
                        _uiState.update { it.copy(
                            isSessionActive = false,
                            completedRecordId = recordId
                        ) }
                    }
                }
                previousPhase = state.phase
            }
        }

        // ── Record-breaking forecast: recompute when settings change ──────────
        viewModelScope.launch {
            _forecastRefreshTrigger.collectLatest {
                delay(150) // debounce
                try {
                    val s = _uiState.value
                    // Pass ALL Min Breath records (not pre-filtered by session duration).
                    // The session duration is supplied as drillParam so the whole history
                    // feeds one regression; pre-filtering starved the fit and pinned every
                    // probability at 100%. Per-parameter PB lookups still only count
                    // records at the selected duration (handled inside compute()).
                    val records = apneaRepository.getAllMinBreathOnce()

                    val settings = ForecastSettings(
                        lungVolume = s.lungVolume,
                        prepType = s.prepType,
                        timeOfDay = s.timeOfDay,
                        posture = s.posture,
                        audio = s.audio
                    )

                    val forecast = RecordForecastCalculator.compute(
                        records = records,
                        settings = settings,
                        nowEpochMs = System.currentTimeMillis(),
                        recordLabel = "sessions",
                        ceilingMs = s.sessionDurationSec * 1000L,  // max hold = entire session
                        drillParam = s.sessionDurationSec
                    )
                    if (forecast.status == ForecastStatus.Ready) {
                        _uiState.update { it.copy(recordForecast = forecast) }
                    } else {
                        _uiState.update { it.copy(recordForecast = null) }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // ── Settings setters ────────────────────────────────────────────────────

    fun setSessionDurationSec(sec: Int) {
        _uiState.update { it.copy(sessionDurationSec = sec) }
        prefs.edit().putInt("min_breath_session_duration_sec", sec).apply()
        refreshForecast()
    }

    fun setLungVolume(v: String) {
        prefs.edit().putString("setting_lung_volume", v).apply()
        _uiState.update { it.copy(lungVolume = v) }
        refreshForecast()
    }

    fun setPrepType(v: String) {
        prefs.edit().putString("setting_prep_type", v).apply()
        val isHyper = v == PrepType.HYPER.name
        _uiState.update {
            it.copy(
                prepType = v,
                isHyperPrep = isHyper,
                guidedHyperEnabled = if (isHyper) it.guidedHyperEnabled else false
            )
        }
        refreshForecast()
    }

    fun setTimeOfDay(v: String) {
        prefs.edit().putString("setting_time_of_day", v).apply()
        _uiState.update { it.copy(timeOfDay = v) }
        refreshForecast()
    }

    fun setPosture(v: String) {
        prefs.edit().putString("setting_posture", v).apply()
        _uiState.update { it.copy(posture = v) }
        refreshForecast()
    }

    fun setAudio(v: String) {
        prefs.edit().putString("setting_audio", v).apply()
        val isGuided = v == AudioSetting.GUIDED.name
        val isMovie = v == AudioSetting.MOVIE.name
        _uiState.update {
            it.copy(
                audio = v,
                isMusicMode = v == AudioSetting.MUSIC.name,
                isMovieMode = isMovie,
                movieAutoControl = if (isMovie) it.movieAutoControl else false,
                isGuidedMode = isGuided
            )
        }
        if (isGuided) {
            viewModelScope.launch {
                val name = guidedAudioManager.getSelectedName()
                _uiState.update { it.copy(
                    guidedSelectedId = guidedAudioManager.selectedId,
                    guidedSelectedName = name
                ) }
            }
        } else {
            _uiState.update { it.copy(guidedSelectedName = "") }
        }
        refreshForecast()
    }

    // ── Voice / vibration toggles ────────────────────────────────────────────

    fun setVoiceEnabled(enabled: Boolean) {
        audioHapticEngine.voiceEnabled = enabled
        _uiState.update { it.copy(voiceEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        audioHapticEngine.vibrationEnabled = enabled
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    // ── Filter methods ────────────────────────────────────────────────────────

    fun setFilterLungVolume(v: String) { _uiState.update { it.copy(filterLungVolume = v) }; loadPastSessions() }
    fun setFilterPrepType(v: String)   { _uiState.update { it.copy(filterPrepType = v) }; loadPastSessions() }
    fun setFilterTimeOfDay(v: String) { _uiState.update { it.copy(filterTimeOfDay = v) }; loadPastSessions() }
    fun setFilterPosture(v: String)   { _uiState.update { it.copy(filterPosture = v) }; loadPastSessions() }
    fun setFilterAudio(v: String)     { _uiState.update { it.copy(filterAudio = v) }; loadPastSessions() }

    fun resetFilters() {
        val s = _uiState.value
        _uiState.update {
            it.copy(
                filterLungVolume = s.lungVolume,
                filterPrepType   = s.prepType,
                filterTimeOfDay  = s.timeOfDay,
                filterPosture    = s.posture,
                filterAudio      = s.audio,
                guidedCountdownComplete = false
            )
        }
        loadPastSessions()
    }

    /** Clear all filters to show sessions across every setting combination. */
    fun clearAllFilters() {
        _uiState.update {
            it.copy(
                filterLungVolume = "",
                filterPrepType   = "",
                filterTimeOfDay  = "",
                filterPosture    = "",
                filterAudio      = ""
            )
        }
        loadPastSessions()
    }

    // ── Guided audio library methods ─────────────────────────────────────────

    fun selectGuidedAudio(audio: GuidedAudioEntity) {
        guidedAudioManager.selectAudio(audio.audioId)
        _uiState.update { it.copy(
            guidedSelectedId = audio.audioId,
            guidedSelectedName = audio.fileName
        ) }
    }

    fun addGuidedAudio(uri: String, fileName: String, sourceUrl: String) {
        viewModelScope.launch {
            val id = guidedAudioManager.addAudio(fileName, uri, sourceUrl)
            guidedAudioManager.selectAudio(id)
            _uiState.update { it.copy(
                guidedSelectedId = id,
                guidedSelectedName = fileName
            ) }
        }
    }

    fun deleteGuidedAudio(audio: GuidedAudioEntity) {
        viewModelScope.launch {
            guidedAudioManager.deleteAudio(audio.audioId)
            if (_uiState.value.guidedSelectedId == audio.audioId) {
                _uiState.update { it.copy(guidedSelectedId = -1L, guidedSelectedName = "") }
            }
        }
    }

    fun loadGuidedCompletionStatuses() {
        viewModelScope.launch {
            val audios = _uiState.value.guidedAudios
            val map = mutableMapOf<Long, GuidedCompletionStatus>()
            for (audio in audios) {
                val ever = apneaRepository.wasGuidedAudioUsedEver(audio.fileName)
                val ui = _uiState.value
                val withSettings = apneaRepository.wasGuidedAudioUsedWithSettings(
                    audio.fileName, ui.lungVolume, ui.prepType, ui.timeOfDay, ui.posture, ui.audio
                )
                map[audio.audioId] = GuidedCompletionStatus(
                    completedEver = ever,
                    completedWithCurrentSettings = withSettings
                )
            }
            _uiState.update { it.copy(guidedCompletionStatuses = map) }
        }
    }

    // ── Guided hyperventilation ──────────────────────────────────────────────

    fun setGuidedHyperEnabled(enabled: Boolean) {
        _uiState.update { it.copy(guidedHyperEnabled = enabled, guidedCountdownComplete = false) }
        prefs.edit().putBoolean("guided_hyper_enabled", enabled).apply()
    }

    fun setGuidedRelaxedExhaleSec(sec: Int) {
        _uiState.update { it.copy(guidedRelaxedExhaleSec = sec) }
        prefs.edit().putInt("guided_relaxed_exhale_sec", sec).apply()
        val id = _uiState.value.guidedSelectedId
        if (_uiState.value.isGuidedMode && id > 0) guidedAudioManager.saveRelaxedExhale(id, sec)
    }

    fun setGuidedPurgeExhaleSec(sec: Int) {
        _uiState.update { it.copy(guidedPurgeExhaleSec = sec) }
        prefs.edit().putInt("guided_purge_exhale_sec", sec).apply()
        val id = _uiState.value.guidedSelectedId
        if (_uiState.value.isGuidedMode && id > 0) guidedAudioManager.savePurgeExhale(id, sec)
    }

    fun setGuidedTransitionSec(sec: Int) {
        _uiState.update { it.copy(guidedTransitionSec = sec) }
        prefs.edit().putInt("guided_transition_sec", sec).apply()
        val id = _uiState.value.guidedSelectedId
        if (_uiState.value.isGuidedMode && id > 0) guidedAudioManager.saveTransitionSec(id, sec)
    }

    fun setStartMp3WithHyper(enabled: Boolean) {
        _uiState.update { it.copy(startMp3WithHyper = enabled) }
        val id = _uiState.value.guidedSelectedId
        if (id > 0) guidedAudioManager.saveStartMp3WithHyper(id, enabled)
    }

    fun showGuidedCountdown() {
        _uiState.update { it.copy(showGuidedCountdown = true) }
        // If "Start MP3 with Hyper" is checked and we're in guided mode, start audio now
        val state = _uiState.value
        if (state.isGuidedMode && state.startMp3WithHyper) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }
    }

    fun onGuidedCountdownComplete() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
        // After guided hyper completes, the user must tap Start on the setup screen
        // to navigate to the active screen (matching Free Hold behaviour).
    }

    fun onGuidedCountdownCancelled() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
        // Stop guided audio if it was started with hyper
        if (_uiState.value.isGuidedMode && _uiState.value.startMp3WithHyper) {
            guidedAudioManager.stopPlayback()
        }
    }

    // ── Song picker ─────────────────────────────────────────────────────────

    fun loadPreviousSongs() {
        // If we have a cached song list, show it instantly
        val cached = spotifyManager.songPickerCache.value
        if (cached != null) {
            _uiState.update { it.copy(previousSongs = cached, loadingSongs = false) }
        } else {
            _uiState.update { it.copy(loadingSongs = true) }
        }

        viewModelScope.launch {
            val dbSongs = apneaRepository.getDistinctSongs()
            val prefsSongs = loadSongHistoryFromPrefs()
            val merged = mergeSongs(dbSongs, prefsSongs)
            val details = merged.map { song ->
                var uri = song.spotifyUri
                if (uri == null && spotifyAuthManager.isConnected.value) {
                    uri = spotifyApiClient.searchTrack(song.title, song.artist)
                }
                if (uri != null) {
                    spotifyApiClient.getTrackDetail(uri) ?: SpotifyTrackDetail(
                        spotifyUri = uri, title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt
                    )
                } else {
                    SpotifyTrackDetail(
                        spotifyUri = "", title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt
                    )
                }
            }
            val deduped = deduplicateTracks(details)
            spotifyManager.updateSongPickerCache(deduped)
            _uiState.update { it.copy(previousSongs = deduped, loadingSongs = false) }
        }
    }

    fun selectSong(track: SpotifyTrackDetail) {
        _uiState.update { it.copy(selectedSong = track, loadingSelectedSong = true) }
        if (track.spotifyUri.isNotBlank() && spotifyAuthManager.isConnected.value) {
            viewModelScope.launch {
                spotifyManager.preloadTrack(track.spotifyUri)
                _uiState.update { it.copy(loadingSelectedSong = false) }
            }
        } else {
            _uiState.update { it.copy(loadingSelectedSong = false) }
        }
    }

    fun clearSelectedSong() {
        _uiState.update { it.copy(selectedSong = null) }
    }

    private fun loadSongHistoryFromPrefs(): List<SpotifySong> {
        val raw = prefs.getString("song_history", null) ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) return@mapNotNull null
            SpotifySong(
                title = parts[0], artist = parts[1], albumArt = null,
                spotifyUri = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                startedAtMs = 0L, endedAtMs = 0L
            )
        }
    }

    private fun mergeSongs(dbSongs: List<SpotifySong>, prefsSongs: List<SpotifySong>): List<SpotifySong> {
        val seenByUri = mutableSetOf<String>()
        val seenByTitleArtist = mutableSetOf<String>()
        val result = mutableListOf<SpotifySong>()
        for (song in dbSongs + prefsSongs) {
            val titleArtistKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
            if (!seenByTitleArtist.add(titleArtistKey)) continue
            if (!song.spotifyUri.isNullOrBlank()) {
                if (!seenByUri.add(song.spotifyUri!!)) continue
            }
            result.add(song)
        }
        return result
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun loadPastSessions() {
        viewModelScope.launch {
            try {
                val allRecords = apneaRepository.getAllRecordsOnce()
                val s = _uiState.value
                val filtered = allRecords
                    .filter { it.tableType == "MIN_BREATH" }
                    .let { records ->
                        var result = records
                        if (s.filterLungVolume.isNotEmpty()) result = result.filter { it.lungVolume == s.filterLungVolume }
                        if (s.filterPrepType.isNotEmpty()) result = result.filter { it.prepType == s.filterPrepType }
                        if (s.filterTimeOfDay.isNotEmpty()) result = result.filter { it.timeOfDay == s.filterTimeOfDay }
                        if (s.filterPosture.isNotEmpty()) result = result.filter { it.posture == s.filterPosture }
                        if (s.filterAudio.isNotEmpty()) result = result.filter { it.audio == s.filterAudio }
                        result
                    }
                val history = buildDurationHistory(filtered)
                _uiState.update { it.copy(pastDurations = history) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load past sessions", e)
            }
        }
    }

    fun setMovieAutoControl(enabled: Boolean) {
        prefs.edit().putBoolean("setting_movie_auto_control", enabled).apply()
        _uiState.update { it.copy(movieAutoControl = enabled) }
    }

    fun startSession() {
        val durationMs = _uiState.value.sessionDurationSec * 1000L
        sessionStartMs = System.currentTimeMillis()
        breathDurations.clear()
        _uiState.update { it.copy(isSessionActive = true, completedRecordId = null) }

        // Start Spotify if MUSIC is selected.
        // Song was pre-loaded in selectSong() — just resume playback.
        if (_uiState.value.isMusicMode) {
            spotifyManager.startTracking()
            spotifyManager.sendPlayCommand()
        }

        // Start movie playback if MOVIE mode with auto-control enabled
        if (_uiState.value.movieAutoControl) {
            spotifyManager.sendRemotePlayCommand()
        }

        // Start guided audio if GUIDED is selected
        if (_uiState.value.isGuidedMode) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }

        // Start telemetry collection
        telemetrySamples.clear()
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (true) {
                val hr = hrDataSource.liveHr.value
                val spo2 = hrDataSource.liveSpO2.value
                if (hr != null || spo2 != null) {
                    telemetrySamples.add(TelemetrySample(System.currentTimeMillis(), hr, spo2))
                }
                delay(1000L)
            }
        }

        stateMachine.start(durationMs, viewModelScope)
    }

    /**
     * Stops the session and saves the record.
     * Called when the user explicitly stops the session via the Stop button
     * or when the session completes naturally (timer runs out).
     */
    fun stopSession() {
        if (!_uiState.value.isSessionActive) return // Already stopped/saved

        telemetryJob?.cancel()
        telemetryJob = null

        // Pause movie if auto-control enabled
        if (_uiState.value.movieAutoControl) {
            spotifyManager.sendRemotePauseCommand()
        }

        // Stop Spotify if MUSIC was selected — capture tracked songs
        trackedSongs = if (_uiState.value.isMusicMode) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks.map { t ->
                SpotifySong(t.title, t.artist, null, t.spotifyUri, t.startedAtMs, t.endedAtMs)
            }
        } else emptyList()

        // Stop guided audio if GUIDED was selected
        if (_uiState.value.isGuidedMode) {
            guidedAudioManager.stopPlayback()
        }

        // Mark inactive BEFORE stopping the state machine to prevent the
        // init-block observer from also saving when it sees COMPLETE.
        _uiState.update { it.copy(isSessionActive = false) }
        stateMachine.stop()
        val finalState = stateMachine.state.value

        // Persist song history to SharedPreferences
        if (trackedSongs.isNotEmpty()) {
            persistSongHistory(trackedSongs)
        }

        viewModelScope.launch {
            try {
                val recordId = saveSession(finalState)
                _uiState.update { it.copy(completedRecordId = recordId) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session", e)
            }
        }
    }

    /**
     * Cancels an in-progress session without saving any record.
     * Called when the user taps the back arrow while the session is running.
     */
    fun cancelSession() {
        if (!_uiState.value.isSessionActive) return // Already stopped

        telemetryJob?.cancel()
        telemetryJob = null

        // Pause movie if auto-control enabled
        if (_uiState.value.movieAutoControl) {
            spotifyManager.sendRemotePauseCommand()
        }

        // Stop Spotify if MUSIC was selected (no tracking save since we're cancelling)
        if (_uiState.value.isMusicMode) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }

        // Stop guided audio if GUIDED was selected
        if (_uiState.value.isGuidedMode) {
            guidedAudioManager.stopPlayback()
        }

        // Mark inactive BEFORE stopping the state machine to prevent the
        // init-block observer from saving when it sees COMPLETE.
        _uiState.update { it.copy(isSessionActive = false) }
        stateMachine.stop()
        // Do NOT save the session or fire tail increments
    }

    fun markContraction() {
        stateMachine.markContraction()
    }

    fun switchToBreathing() {
        // Pause movie if auto-control enabled (hold → breathing)
        if (_uiState.value.movieAutoControl) {
            spotifyManager.sendRemotePauseCommand()
        }
        stateMachine.switchToBreathing()
    }

    fun switchToHolding() {
        // Capture breath duration before state machine transitions
        val currentState = stateMachine.state.value
        if (currentState.phase == MinBreathPhase.BREATHING) {
            val holdNumber = currentState.currentHoldNumber
            // holdNumber is the hold that just ended; breath follows it
            breathDurations[holdNumber] = currentState.currentPhaseElapsedMs
        }
        // Resume movie if auto-control enabled (breathing → hold)
        if (_uiState.value.movieAutoControl) {
            spotifyManager.sendRemotePlayCommand()
        }
        stateMachine.switchToHolding()
    }

    /** Clears completedRecordId after the UI has navigated to the detail screen. */
    fun onSessionNavigated() {
        _uiState.update { it.copy(completedRecordId = null) }
    }

    /** Dismiss the PB celebration dialog. */
    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    /**
     * Restarts the same Min Breath session from scratch without navigating away.
     * Cancels any running session, resets result state, then calls [startSession] again.
     * Called by [MinBreathPipContent] when the user taps "Again" inside PiP.
     */
    fun restartSameSession() {
        cancelSession()
        _uiState.update { it.copy(completedRecordId = null, newPersonalBest = null) }
        startSession()
    }

    // ── Session saving ──────────────────────────────────────────────────────

    private suspend fun saveSession(finalState: MinBreathState): Long {
        val now = System.currentTimeMillis()
        val totalDurationMs = now - sessionStartMs
        val sessionDurationSec = _uiState.value.sessionDurationSec
        val deviceLabel = hrDataSource.activeHrDeviceLabel()
        val telemetrySnapshot = telemetrySamples.toList()
        telemetrySamples.clear()

        // Compute aggregates from telemetry
        val maxHr = telemetrySnapshot.mapNotNull { it.hr }.maxOrNull()
        val minHr = telemetrySnapshot.mapNotNull { it.hr }.minOrNull()
        val lowestSpO2 = telemetrySnapshot.mapNotNull { it.spO2 }.minOrNull()

        // Build tableParamsJson
        val paramsJson = buildParamsJson(sessionDurationSec, finalState)

        val holdResults = finalState.holdResults

        // 1. Save ApneaSessionEntity
        val sessionEntity = ApneaSessionEntity(
            timestamp = now,
            tableType = "MIN_BREATH",
            tableVariant = "TIMED",
            tableParamsJson = paramsJson,
            pbAtSessionMs = 0L,
            totalSessionDurationMs = totalDurationMs,
            contractionTimestampsJson = "[]",
            maxHrBpm = maxHr,
            lowestSpO2 = lowestSpO2,
            roundsCompleted = holdResults.size,
            totalRounds = holdResults.size,
            hrDeviceId = deviceLabel
        )
        val sessionId = sessionRepository.saveSession(sessionEntity)

        // 2. Save ApneaRecordEntity (total hold time as durationMs for Min Breath)
        val totalHoldTimeMs = finalState.totalHoldTimeMs

        val currentState = _uiState.value

        // If MUSIC was selected but no song actually played, record as SILENCE.
        val effectiveAudio = if (currentState.audio == AudioSetting.GUIDED.name) {
            currentState.audio
        } else if (currentState.audio == AudioSetting.MUSIC.name && trackedSongs.isEmpty()) {
            AudioSetting.SILENCE.name
        } else {
            currentState.audio
        }

        // Check broader PB BEFORE saving so queries compare against prior records only
        val drill = DrillContext.minBreath(sessionDurationSec)
        val pbResult = if (totalHoldTimeMs > 0L) {
            apneaRepository.checkBroaderPersonalBest(
                drill, totalHoldTimeMs,
                currentState.lungVolume, currentState.prepType, currentState.timeOfDay,
                currentState.posture, effectiveAudio
            )
        } else null

        // Capture guided hyper state at save time
        val wasGuided = currentState.guidedHyperEnabled && currentState.isHyperPrep

        val recordId = apneaRepository.saveRecord(
            ApneaRecordEntity(
                timestamp = now,
                durationMs = totalHoldTimeMs,
                lungVolume = currentState.lungVolume,
                prepType = currentState.prepType,
                minHrBpm = minHr?.toFloat() ?: 0f,
                maxHrBpm = maxHr?.toFloat() ?: 0f,
                tableType = "MIN_BREATH",
                lowestSpO2 = lowestSpO2,
                timeOfDay = currentState.timeOfDay,
                hrDeviceId = deviceLabel,
                posture = currentState.posture,
                audio = effectiveAudio,
                drillParamValue = sessionDurationSec,
                guidedAudioName = if (currentState.audio == AudioSetting.GUIDED.name) _uiState.value.guidedSelectedName else null,
                guidedHyper = wasGuided,
                guidedRelaxedExhaleSec = if (wasGuided) currentState.guidedRelaxedExhaleSec else null,
                guidedPurgeExhaleSec = if (wasGuided) currentState.guidedPurgeExhaleSec else null,
                guidedTransitionSec = if (wasGuided) currentState.guidedTransitionSec else null
            )
        )

        // Show PB celebration + fire Tail habit if applicable
        if (pbResult != null) {
            _uiState.update { it.copy(newPersonalBest = pbResult) }
            try { habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD) } catch (_: Exception) {}
        }

        // Fire Tail habit for every completed Min Breath session
        try { habitRepo.sendHabitIncrement(Slot.MIN_BREATH) } catch (_: Exception) {}

        // Fire music habit if applicable (once per TimeOfDay per day)
        try { habitRepo.sendMusicHabitIncrementIfNeeded(effectiveAudio, currentState.timeOfDay) } catch (_: Exception) {}

        // 2b. Save song log (Spotify tracks played during session)
        if (recordId > 0 && trackedSongs.isNotEmpty()) {
            apneaRepository.saveSongLog(recordId, trackedSongs)
            trackedSongs = emptyList()
        }

        // 3. Save FreeHoldTelemetryEntity rows (linked to recordId)
        if (recordId > 0 && telemetrySnapshot.isNotEmpty()) {
            val freeHoldSamples = telemetrySnapshot.map { sample ->
                FreeHoldTelemetryEntity(
                    recordId = recordId,
                    timestampMs = sample.timestampMs,
                    heartRateBpm = sample.hr,
                    spO2 = sample.spO2
                )
            }
            apneaRepository.saveTelemetry(freeHoldSamples)
        }

        // 4. Save TelemetryEntity rows (linked to sessionId)
        if (sessionId > 0 && telemetrySnapshot.isNotEmpty()) {
            val sessionTelemetry = telemetrySnapshot.map { sample ->
                TelemetryEntity(
                    sessionId = sessionId,
                    timestampMs = sample.timestampMs,
                    spO2 = sample.spO2,
                    heartRateBpm = sample.hr,
                    source = if (hrDataSource.isOximeterPrimaryDevice()) "OXIMETER" else "POLAR"
                )
            }
            sessionRepository.saveTelemetry(sessionTelemetry)
        }

        return recordId
    }

    // ── Song history persistence ─────────────────────────────────────────────

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

    // ── JSON helpers ────────────────────────────────────────────────────────

    private fun buildParamsJson(sessionDurationSec: Int, state: MinBreathState): String {
        val totalHoldTimeMs = state.totalHoldTimeMs
        val totalBreathTimeMs = state.totalBreathTimeMs
        val totalActiveMs = totalHoldTimeMs + totalBreathTimeMs
        val holdPct = if (totalActiveMs > 0) {
            (totalHoldTimeMs.toDouble() / totalActiveMs * 100.0)
        } else 0.0

        val root = JSONObject()
        root.put("sessionDurationSec", sessionDurationSec)
        root.put("totalHoldTimeMs", totalHoldTimeMs)
        root.put("totalBreathTimeMs", totalBreathTimeMs)
        root.put("holdPct", String.format("%.1f", holdPct).toDouble())

        val holdsArray = JSONArray()
        for (r in state.holdResults) {
            val obj = JSONObject()
            obj.put("hold", r.holdNumber)
            obj.put("durationMs", r.holdDurationMs)
            if (r.firstContractionMs != null) {
                obj.put("contractionMs", r.firstContractionMs)
            } else {
                obj.put("contractionMs", JSONObject.NULL)
            }
            // Add per-hold breath duration (captured when user switched back to holding)
            val breathMs = breathDurations[r.holdNumber]
            if (breathMs != null) {
                obj.put("breathDurationMs", breathMs)
            } else {
                obj.put("breathDurationMs", JSONObject.NULL)
            }
            holdsArray.put(obj)
        }
        root.put("holds", holdsArray)
        return root.toString()
    }

    private fun buildDurationHistory(records: List<ApneaRecordEntity>): List<DurationHistory> {
        return records
            .filter { it.drillParamValue != null && it.durationMs > 0 }
            .groupBy { it.drillParamValue!! }
            .map { (durSec, group) ->
                val bestRecord = group.maxByOrNull { it.durationMs }
                val sessionDurationMs = durSec * 1000L
                val bestHoldPct = if (sessionDurationMs > 0)
                    ((bestRecord?.durationMs?.toDouble() ?: 0.0) / sessionDurationMs * 100.0).coerceAtMost(100.0)
                else 0.0
                DurationHistory(
                    durationSec = durSec,
                    bestHoldPct = bestHoldPct,
                    sessionCount = group.size,
                    bestRecordId = bestRecord?.recordId ?: -1L
                )
            }
            .sortedBy { it.durationSec }
    }

    override fun onCleared() {
        guidedAudioManager.stopPlayback()
        // Pause movie if auto-control enabled
        if (_uiState.value.movieAutoControl) {
            try { spotifyManager.sendRemotePauseCommand() } catch (_: Exception) {}
        }
        // Also stop Spotify if still tracking
        if (_uiState.value.isMusicMode) {
            try {
                spotifyManager.stopTracking()
                spotifyManager.sendPauseAndRewindCommand()
            } catch (_: Exception) {}
        }
        super.onCleared()
    }

    /**
     * Builds a forecast that shows 100% chance to beat for every category,
     * used when no records exist for the selected duration+settings combo.
     */
    private fun noRecordForecast(): RecordForecast {
        val categories = PersonalBestCategory.entries.map { cat ->
            CategoryForecast(
                category = cat,
                trophyCount = cat.trophyCount(),
                label = when (cat) {
                    PersonalBestCategory.EXACT -> "Exact settings"
                    PersonalBestCategory.FOUR_SETTINGS -> "4 settings"
                    PersonalBestCategory.THREE_SETTINGS -> "3 settings"
                    PersonalBestCategory.TWO_SETTINGS -> "2 settings"
                    PersonalBestCategory.ONE_SETTING -> "1 setting"
                    PersonalBestCategory.GLOBAL -> "All settings"
                },
                recordMs = null,
                probability = 1.0f,
                confidence = ForecastConfidence.LOW
            )
        }
        return RecordForecast(
            status = ForecastStatus.Ready,
            exactProbability = 1.0f,
            categories = categories,
            totalRecords = 0,
            confidence = ForecastConfidence.LOW,
            recordLabel = "sessions"
        )
    }

    companion object {
        private const val TAG = "MinBreathVM"
    }
}
