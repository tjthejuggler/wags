package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.db.entity.GuidedAudioEntity
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
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.ContractionTableMode
import com.example.wags.domain.usecase.apnea.ContractionTablePhase
import com.example.wags.domain.usecase.apnea.ContractionTableRoundResult
import com.example.wags.domain.usecase.apnea.ContractionTableState
import com.example.wags.domain.usecase.apnea.ContractionTableStateMachine
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.domain.usecase.apnea.ResonancePrepGate
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named

// ── UI state ────────────────────────────────────────────────────────────────

data class ContractionTableUiState(
    val sessionState: ContractionTableState = ContractionTableState(),
    // ── Configuration ──────────────────────────────────────────────────────────
    /** Active drill mode (persisted across sessions). */
    val mode: ContractionTableMode = ContractionTableMode.TILL_CONTRACTION,
    /** Number of rounds in the table (1-indexed). */
    val rounds: Int = ContractionTableViewModel.DEFAULT_TILL_ROUNDS,
    /** Rest before round 1 (seconds). */
    val restStartSec: Int = ContractionTableViewModel.DEFAULT_TILL_REST_START_SEC,
    /** Rest before the final round (seconds) — linearly interpolated between. */
    val restEndSec: Int = ContractionTableViewModel.DEFAULT_TILL_REST_END_SEC,
    /** Contraction target (CONTRACTION_COUNT mode only). */
    val contractionTarget: Int = ContractionTableViewModel.DEFAULT_COUNT_TARGET,
    // ── Session ────────────────────────────────────────────────────────────────
    val isSessionActive: Boolean = false,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Set after session is saved — sessionId of the saved ApneaSessionEntity (detail screen nav). */
    val completedSessionId: Long? = null,
    /** Set after session is saved — recordId of the saved ApneaRecordEntity. */
    val completedRecordId: Long? = null,
    // ── History ────────────────────────────────────────────────────────────────
    val pastSessions: List<ContractionTableHistoryEntry> = emptyList(),
    // ── Apnea settings (read from SharedPreferences) ──────────────────────────
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
    // ── Song picker / Spotify / guided audio ──────────────────────────────────
    val spotifyConnected: Boolean = false,
    val isMusicMode: Boolean = false,
    val isGuidedMode: Boolean = false,
    val guidedAudios: List<GuidedAudioEntity> = emptyList(),
    val guidedSelectedId: Long = -1L,
    val guidedSelectedName: String = "",
    val guidedCompletionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSelectedSong: Boolean = false,
    // ── Personal best / forecast ───────────────────────────────────────────────
    val newPersonalBest: PersonalBestResult? = null,
    /** Best duration for the current mode (+ target) and current 5 settings (ms). */
    val personalBestCurrentSettingsMs: Long? = null,
    /** Trophy category of that best record. */
    val personalBestTrophyCategory: PersonalBestCategory? = null,
    val recordForecast: RecordForecast? = null,
    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────
    val eucapnicConfig: EucapnicConfig? = null,
    // ── Safety ─────────────────────────────────────────────────────────────────
    /** True when the prep type is HYPER — the setup screen shows a hypoxia advisory. */
    val isHyperPrep: Boolean = false,
    /** True when no resonance breathing session ended within the last ~5 minutes (RESONANCE prep locked). */
    val resonancePrepLocked: Boolean = false
)

/** One past contraction-table session, parsed for the setup screen history list. */
data class ContractionTableHistoryEntry(
    val recordId: Long,
    val timestamp: Long,
    val mode: ContractionTableMode,
    /** Human-readable config, e.g. "8 × 90→60s rest" or "6 × 120→60s rest · 8c". */
    val configLabel: String,
    val roundsCompleted: Int,
    val roundsTotal: Int,
    /** Longest easy phase (hold start → first contraction) across rounds; null when none logged. */
    val bestCruiseMs: Long?,
    val longestHoldMs: Long,
    val totalHoldMs: Long,
    /** Average cruise ratio (cruise / total hold) across rounds with a logged contraction. */
    val avgCruiseRatio: Float?
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class ContractionTableViewModel @Inject constructor(
    private val stateMachine: ContractionTableStateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val hrDataSource: HrDataSource,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    private val hyperLockManager: HyperLockManager,
    private val resonancePrepGate: ResonancePrepGate,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContractionTableUiState())

    val uiState: StateFlow<ContractionTableUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        stateMachine.state,
        spotifyAuthManager.isConnected
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val ui = args[0] as ContractionTableUiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val session = args[3] as ContractionTableState
        val connected = args[4] as Boolean
        ui.copy(sessionState = session, liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContractionTableUiState()
    )

    // Telemetry collection
    private data class TelemetrySample(val timestampMs: Long, val hr: Int?, val spO2: Int?)
    private val telemetrySamples = mutableListOf<TelemetrySample>()
    private var telemetryJob: Job? = null
    private var sessionStartMs: Long = 0L

    // Track previous phase for audio/haptic cues
    private var previousPhase: ContractionTablePhase = ContractionTablePhase.IDLE

    // Spotify tracks played during the session (captured at stop time)
    private var trackedSongs: List<SpotifySong> = emptyList()

    /** Bumped when settings change — triggers forecast recompute. */
    private val _forecastRefreshTrigger = MutableStateFlow(0)

    /** Call after any settings change to recompute the forecast. */
    private fun refreshForecast() { _forecastRefreshTrigger.value++ }

    init {
        restoreConfig()

        // ── Resonance prep staleness lock ──────────────────────────────────────
        viewModelScope.launch {
            resonancePrepGate.isLocked.collect { locked ->
                _uiState.update { it.copy(resonancePrepLocked = locked) }
                if (locked && _uiState.value.prepType == PrepType.RESONANCE.name) {
                    applyPrepType(PrepType.NO_PREP.name)
                }
            }
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

        loadPersonalBests()
        loadSessionHistory()

        // Observe state machine: audio/haptic cues + natural-completion save.
        // Unlike Progressive O₂ (endless), this table has a fixed round count and
        // reaches COMPLETE on its own — the observer then finalises and saves.
        viewModelScope.launch {
            stateMachine.state.collect { state ->
                handlePhaseTransition(state)
                if (state.phase == ContractionTablePhase.COMPLETE &&
                    previousPhase != ContractionTablePhase.COMPLETE &&
                    _uiState.value.isSessionActive
                ) {
                    stopSession()
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
                    val records = apneaRepository.getAllRecordsOnce()
                        .filter { it.tableType == s.mode.tableType() }
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
                        drillParam = if (s.mode == ContractionTableMode.CONTRACTION_COUNT) s.contractionTarget else null
                    )
                    _uiState.update { it.copy(recordForecast = if (forecast.status == ForecastStatus.Ready) forecast else null) }
                } catch (_: Exception) { }
            }
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    private fun restoreConfig() {
        val savedMode = try {
            ContractionTableMode.valueOf(prefs.getString(PREF_MODE, null) ?: ContractionTableMode.TILL_CONTRACTION.name)
        } catch (_: Exception) { ContractionTableMode.TILL_CONTRACTION }

        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
        val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"
        val savedAudio      = prefs.getString("setting_audio", "SILENCE") ?: "SILENCE"

        val (rounds, restStart, restEnd, target) = if (savedMode == ContractionTableMode.TILL_CONTRACTION) {
            listOf(
                prefs.getInt(PREF_TILL_ROUNDS, DEFAULT_TILL_ROUNDS),
                prefs.getInt(PREF_TILL_REST_START, DEFAULT_TILL_REST_START_SEC),
                prefs.getInt(PREF_TILL_REST_END, DEFAULT_TILL_REST_END_SEC),
                DEFAULT_COUNT_TARGET
            )
        } else {
            listOf(
                prefs.getInt(PREF_COUNT_ROUNDS, DEFAULT_COUNT_ROUNDS),
                prefs.getInt(PREF_COUNT_REST_START, DEFAULT_COUNT_REST_START_SEC),
                prefs.getInt(PREF_COUNT_REST_END, DEFAULT_COUNT_REST_END_SEC),
                prefs.getInt(PREF_COUNT_TARGET, DEFAULT_COUNT_TARGET)
            )
        }

        _uiState.update {
            it.copy(
                mode = savedMode,
                rounds = rounds,
                restStartSec = restStart,
                restEndSec = restEnd,
                contractionTarget = target,
                lungVolume  = savedLungVolume,
                prepType    = savedPrepType,
                timeOfDay   = TimeOfDay.fromCurrentTime().name,
                posture     = savedPosture,
                audio       = savedAudio,
                isMusicMode = savedAudio == AudioSetting.MUSIC.name,
                isGuidedMode = savedAudio == AudioSetting.GUIDED.name,
                isHyperPrep = savedPrepType == PrepType.HYPER.name,
                guidedSelectedId = guidedAudioManager.selectedId,
                voiceEnabled = audioHapticEngine.voiceEnabled,
                vibrationEnabled = audioHapticEngine.vibrationEnabled
            )
        }
    }

    fun setMode(mode: ContractionTableMode) {
        // Persist the current config for the old mode, then restore the new mode's.
        persistCurrentModeConfig()
        prefs.edit().putString(PREF_MODE, mode.name).apply()
        val (rounds, restStart, restEnd, target) = if (mode == ContractionTableMode.TILL_CONTRACTION) {
            listOf(
                prefs.getInt(PREF_TILL_ROUNDS, DEFAULT_TILL_ROUNDS),
                prefs.getInt(PREF_TILL_REST_START, DEFAULT_TILL_REST_START_SEC),
                prefs.getInt(PREF_TILL_REST_END, DEFAULT_TILL_REST_END_SEC),
                DEFAULT_COUNT_TARGET
            )
        } else {
            listOf(
                prefs.getInt(PREF_COUNT_ROUNDS, DEFAULT_COUNT_ROUNDS),
                prefs.getInt(PREF_COUNT_REST_START, DEFAULT_COUNT_REST_START_SEC),
                prefs.getInt(PREF_COUNT_REST_END, DEFAULT_COUNT_REST_END_SEC),
                prefs.getInt(PREF_COUNT_TARGET, DEFAULT_COUNT_TARGET)
            )
        }
        _uiState.update {
            it.copy(mode = mode, rounds = rounds, restStartSec = restStart, restEndSec = restEnd, contractionTarget = target)
        }
        refreshForecast()
        loadPersonalBests()
    }

    private fun persistCurrentModeConfig() {
        val s = _uiState.value
        prefs.edit().apply {
            if (s.mode == ContractionTableMode.TILL_CONTRACTION) {
                putInt(PREF_TILL_ROUNDS, s.rounds)
                putInt(PREF_TILL_REST_START, s.restStartSec)
                putInt(PREF_TILL_REST_END, s.restEndSec)
            } else {
                putInt(PREF_COUNT_ROUNDS, s.rounds)
                putInt(PREF_COUNT_REST_START, s.restStartSec)
                putInt(PREF_COUNT_REST_END, s.restEndSec)
                putInt(PREF_COUNT_TARGET, s.contractionTarget)
            }
        }.apply()
    }

    fun setRounds(value: Int) {
        val clamped = value.coerceIn(MIN_ROUNDS, MAX_ROUNDS)
        _uiState.update { it.copy(rounds = clamped) }
        persistCurrentModeConfig()
    }

    fun setRestStartSec(value: Int) {
        val clamped = value.coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        _uiState.update { it.copy(restStartSec = clamped) }
        persistCurrentModeConfig()
    }

    fun setRestEndSec(value: Int) {
        val clamped = value.coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        _uiState.update { it.copy(restEndSec = clamped) }
        persistCurrentModeConfig()
    }

    fun setContractionTarget(value: Int) {
        val clamped = value.coerceIn(MIN_TARGET, MAX_TARGET)
        _uiState.update { it.copy(contractionTarget = clamped) }
        persistCurrentModeConfig()
        refreshForecast()
        loadPersonalBests()
    }

    // ── Settings setters ────────────────────────────────────────────────────

    fun setLungVolume(v: String) {
        prefs.edit().putString("setting_lung_volume", v).apply()
        _uiState.update { it.copy(lungVolume = v) }
        refreshForecast()
        loadPersonalBests()
    }

    fun setPrepType(v: String) {
        // HYPER is time-locked: check the lock (DB query) before applying.
        if (v == PrepType.HYPER.name) {
            viewModelScope.launch {
                if (!hyperLockManager.isLocked()) applyPrepType(v)
            }
        } else if (v == PrepType.RESONANCE.name) {
            // RESONANCE prep is staleness-locked: it needs a resonance breathing
            // session that ended within the last ~5 minutes.
            viewModelScope.launch {
                if (!resonancePrepGate.isLockedNow()) applyPrepType(v)
            }
        } else {
            applyPrepType(v)
        }
    }

    private fun applyPrepType(v: String) {
        prefs.edit().putString("setting_prep_type", v).apply()
        _uiState.update { it.copy(prepType = v, isHyperPrep = v == PrepType.HYPER.name) }
        refreshForecast()
        loadPersonalBests()
    }

    fun setTimeOfDay(v: String) {
        prefs.edit().putString("setting_time_of_day", v).apply()
        _uiState.update { it.copy(timeOfDay = v) }
        refreshForecast()
        loadPersonalBests()
    }

    fun setPosture(v: String) {
        prefs.edit().putString("setting_posture", v).apply()
        _uiState.update { it.copy(posture = v) }
        refreshForecast()
        loadPersonalBests()
    }

    fun setAudio(v: String) {
        prefs.edit().putString("setting_audio", v).apply()
        val isGuided = v == AudioSetting.GUIDED.name
        _uiState.update {
            it.copy(
                audio = v,
                isMusicMode = v == AudioSetting.MUSIC.name,
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
        loadPersonalBests()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        audioHapticEngine.voiceEnabled = enabled
        _uiState.update { it.copy(voiceEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        audioHapticEngine.vibrationEnabled = enabled
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    // ── Guided audio library ─────────────────────────────────────────────────

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

    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────

    fun updateEucapnicConfig(config: EucapnicConfig) {
        _uiState.update { it.copy(eucapnicConfig = config) }
    }

    // ── Song picker ─────────────────────────────────────────────────────────

    fun loadPreviousSongs(forceRefresh: Boolean = false) {
        // If we have a cached song list, show it instantly (unless forcing a refresh)
        val cached = spotifyManager.songPickerCache.value
        if (cached != null) {
            _uiState.update { it.copy(previousSongs = cached, loadingSongs = forceRefresh) }
        } else {
            _uiState.update { it.copy(loadingSongs = true) }
        }

        viewModelScope.launch {
            val dbSongs = apneaRepository.getDistinctSongs()
            val prefsSongs = loadSongHistoryFromPrefs()
            val merged = mergeSongs(dbSongs, prefsSongs)

            // Resolve URIs first (search-backfill for any song missing a URI).
            val isConnected = spotifyAuthManager.isConnected.value
            val withUris = merged.map { song ->
                var uri = song.spotifyUri
                if (uri == null && isConnected) {
                    uri = spotifyApiClient.searchTrack(song.title, song.artist)
                }
                song to uri
            }

            // Use the persistent cache to avoid hitting Spotify's rate limit.
            val existingCache = spotifyManager.songPickerCache.value ?: emptyList()
            val cacheByUri = if (forceRefresh) emptyMap() else existingCache.associateBy { it.spotifyUri }

            // Split into cache-hits vs URIs needing a fetch.
            val needFetch = mutableListOf<Pair<SpotifySong, String>>()
            val preliminary = withUris.map { (song, uri) ->
                if (uri != null) {
                    val hit = cacheByUri[uri]
                    if (hit != null) {
                        hit
                    } else {
                        needFetch.add(song to uri)
                        null // placeholder — filled by the batch fetch below
                    }
                } else {
                    SpotifyTrackDetail(spotifyUri = "", title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt)
                }
            }

            // ONE batch API call for all cache-misses (avoids per-track 429s).
            val fetched = if (needFetch.isNotEmpty() && isConnected) {
                spotifyApiClient.getTracksDetail(needFetch.map { it.second })
            } else emptyMap()

            // Merge everything back together in original order.
            var fetchIdx = 0
            val details = preliminary.map { prelim ->
                if (prelim != null) {
                    prelim
                } else {
                    val (song, uri) = needFetch[fetchIdx++]
                    fetched[uri] ?: SpotifyTrackDetail(
                        spotifyUri = uri, title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt
                    )
                }
            }
            val deduped = deduplicateTracks(details)
            spotifyManager.updateSongPickerCache(deduped)
            _uiState.update { it.copy(previousSongs = deduped, loadingSongs = false) }
        }
    }

    /** Stable identity key for a song card — uses URI when available, otherwise title+artist. */
    private fun SpotifyTrackDetail.cardKey(): String =
        if (spotifyUri.isNotBlank()) spotifyUri else "$title|$artist"

    fun selectSong(track: SpotifyTrackDetail) {
        val trackKey = track.cardKey()
        _uiState.update { currentState ->
            val currentSelected = currentState.selectedSongs
            val existingIndex = currentSelected.indexOfFirst { it.cardKey() == trackKey }

            val newSelected = if (existingIndex >= 0) {
                currentSelected.toMutableList().apply { removeAt(existingIndex) }
            } else {
                currentSelected + track
            }

            // Sync Spotify playback with the new selection (if connected).
            if (spotifyAuthManager.isConnected.value) {
                val allUris = newSelected.map { it.spotifyUri }.filter { it.isNotBlank() }
                viewModelScope.launch {
                    if (allUris.isNotEmpty()) {
                        spotifyManager.preloadTrackList(allUris)
                    }
                    _uiState.update { it.copy(loadingSelectedSong = false) }
                }
                currentState.copy(
                    selectedSongs = newSelected,
                    loadingSelectedSong = allUris.isNotEmpty()
                )
            } else {
                currentState.copy(selectedSongs = newSelected, loadingSelectedSong = false)
            }
        }
    }

    fun clearSelectedSong() {
        _uiState.update { it.copy(selectedSongs = emptyList()) }
    }

    private fun deduplicateTracks(tracks: List<SpotifyTrackDetail>): List<SpotifyTrackDetail> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SpotifyTrackDetail>()
        for (track in tracks) {
            if (seen.add(track.cardKey())) result.add(track)
        }
        return result
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

    // ── Filters ────────────────────────────────────────────────────────────────

    fun setFilterLungVolume(v: String) { _uiState.update { it.copy(filterLungVolume = v) }; loadSessionHistory() }
    fun setFilterPrepType(v: String)   { _uiState.update { it.copy(filterPrepType = v) }; loadSessionHistory() }
    fun setFilterTimeOfDay(v: String)  { _uiState.update { it.copy(filterTimeOfDay = v) }; loadSessionHistory() }
    fun setFilterPosture(v: String)    { _uiState.update { it.copy(filterPosture = v) }; loadSessionHistory() }
    fun setFilterAudio(v: String)      { _uiState.update { it.copy(filterAudio = v) }; loadSessionHistory() }

    fun resetFilters() {
        val s = _uiState.value
        _uiState.update {
            it.copy(
                filterLungVolume = s.lungVolume,
                filterPrepType   = s.prepType,
                filterTimeOfDay  = s.timeOfDay,
                filterPosture    = s.posture,
                filterAudio      = s.audio
            )
        }
        loadSessionHistory()
    }

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
        loadSessionHistory()
    }

    // ── Personal bests ────────────────────────────────────────────────────────

    private fun currentDrillContext(): DrillContext =
        if (_uiState.value.mode == ContractionTableMode.TILL_CONTRACTION) DrillContext.CONTRACTION_TILL
        else DrillContext.contractionCount(_uiState.value.contractionTarget)

    private fun loadPersonalBests() {
        viewModelScope.launch {
            try {
                val s = _uiState.value
                val best = apneaRepository.getDrillBestAndTrophy(
                    drill = currentDrillContext(),
                    lungVolume = s.lungVolume,
                    prepType = s.prepType,
                    timeOfDay = s.timeOfDay,
                    posture = s.posture,
                    audio = s.audio
                )
                _uiState.update {
                    it.copy(
                        personalBestCurrentSettingsMs = best?.first,
                        personalBestTrophyCategory = best?.second
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load personal bests", e)
            }
        }
    }

    // ── History ────────────────────────────────────────────────────────────────

    fun loadSessionHistory() {
        viewModelScope.launch {
            try {
                val s = _uiState.value
                val wonkaRecords = apneaRepository.getAllRecordsOnce()
                    .filter { it.tableType == TABLE_TYPE_TILL || it.tableType == TABLE_TYPE_COUNT }
                    .let { records ->
                        var result = records
                        if (s.filterLungVolume.isNotEmpty()) result = result.filter { it.lungVolume == s.filterLungVolume }
                        if (s.filterPrepType.isNotEmpty()) result = result.filter { it.prepType == s.filterPrepType }
                        if (s.filterTimeOfDay.isNotEmpty()) result = result.filter { it.timeOfDay == s.filterTimeOfDay }
                        if (s.filterPosture.isNotEmpty()) result = result.filter { it.posture == s.filterPosture }
                        if (s.filterAudio.isNotEmpty()) result = result.filter { it.audio == s.filterAudio }
                        result
                    }
                val sessionMap = sessionRepository.getAllSessionsOnce().associateBy { it.timestamp }

                val entries = wonkaRecords.mapNotNull { record ->
                    val session = sessionMap[record.timestamp] ?: return@mapNotNull null
                    parseHistoryEntry(record.recordId, record.timestamp, session.tableParamsJson)
                }.sortedByDescending { it.timestamp }

                _uiState.update { it.copy(pastSessions = entries) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load session history", e)
            }
        }
    }

    // ── Session lifecycle ──────────────────────────────────────────────────────

    fun startSession() {
        val s = _uiState.value
        sessionStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(isSessionActive = true, completedSessionId = null, completedRecordId = null) }

        // Start Spotify if MUSIC is selected (song pre-loaded in selectSong()).
        if (s.isMusicMode) {
            spotifyManager.startTracking()
            spotifyManager.sendPlayCommand()
        }

        // Start guided audio if GUIDED is selected.
        if (s.isGuidedMode && !guidedAudioManager.isPlaying) {
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
                    telemetrySamples.add(
                        TelemetrySample(System.currentTimeMillis(), hr, spo2)
                    )
                }
                delay(1000L)
            }
        }

        stateMachine.start(
            mode = s.mode,
            rounds = s.rounds,
            restStartSec = s.restStartSec,
            restEndSec = s.restEndSec,
            contractionTarget = s.contractionTarget,
            scope = viewModelScope
        )
    }

    /**
     * Stops the session and saves the record. Called when the user taps Stop,
     * when the table finishes its final round (completion observer in init),
     * or when a hold is bailed out of and no rounds remain.
     */
    fun stopSession() {
        if (!_uiState.value.isSessionActive) return // Already stopped/saved

        // Stop telemetry collection first for a stable snapshot
        telemetryJob?.cancel()
        telemetryJob = null

        // Stop Spotify if MUSIC was selected — capture tracked songs
        trackedSongs = if (_uiState.value.isMusicMode) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks.map { t ->
                SpotifySong(t.title, t.artist, null, t.spotifyUri, t.startedAtMs, t.endedAtMs ?: 0L)
            }
        } else emptyList()

        // Stop guided audio if GUIDED was selected
        if (_uiState.value.isGuidedMode) {
            guidedAudioManager.stopPlayback()
        }

        // Mark inactive BEFORE stopping the state machine to prevent the
        // init-block completion observer from re-entering stopSession().
        _uiState.update { it.copy(isSessionActive = false) }
        stateMachine.stop()
        val finalState = stateMachine.state.value

        // Persist song history to SharedPreferences
        if (trackedSongs.isNotEmpty()) {
            persistSongHistory(trackedSongs)
        }

        viewModelScope.launch {
            try {
                val (sessionId, recordId) = saveSession(finalState)
                _uiState.update { it.copy(completedSessionId = sessionId, completedRecordId = recordId) }
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

        if (_uiState.value.isMusicMode) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }

        if (_uiState.value.isGuidedMode) {
            guidedAudioManager.stopPlayback()
        }

        _uiState.update { it.copy(isSessionActive = false) }
        stateMachine.stop()
        // Do NOT save the session or fire habit increments
    }

    /** Clears completed IDs after the UI has navigated to the detail screen. */
    fun onSessionNavigated() {
        _uiState.update { it.copy(completedSessionId = null, completedRecordId = null) }
    }

    /** Dismiss the PB celebration dialog. */
    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    /**
     * Restarts the same table from scratch without navigating away.
     * Called by the PiP content when the user taps "Again" inside PiP.
     */
    fun restartSameSession() {
        cancelSession()
        _uiState.update { it.copy(completedSessionId = null, completedRecordId = null, newPersonalBest = null) }
        startSession()
    }

    // ── Contraction logging ────────────────────────────────────────────────────

    /** Log the first diaphragmatic contraction during CRUISE. */
    fun logFirstContraction() {
        stateMachine.signalFirstContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    /** Log a subsequent contraction during STRUGGLE (CONTRACTION_COUNT mode). */
    fun logContraction() {
        stateMachine.signalContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    /** End the current hold early (partial round) — the session continues. */
    fun endHoldEarly() {
        stateMachine.endHoldEarly()
        audioHapticEngine.vibrateHoldEnd()
    }

    // ── Audio / haptic cues ─────────────────────────────────────────────────

    private fun handlePhaseTransition(state: ContractionTableState) {
        if (state.phase == previousPhase) {
            // Same phase — check for breathing countdown tick
            if (state.phase == ContractionTablePhase.BREATHE && state.timerMs in 1000..10_000) {
                val isLast = state.timerMs <= 1000L
                audioHapticEngine.vibrateBreathingCountdownTick(isLastTick = isLast)
            }
            return
        }

        when (state.phase) {
            ContractionTablePhase.BREATHE -> {
                audioHapticEngine.vibrateHoldEnd()
                audioHapticEngine.announceBreath()
                // Announce the round that just finished (not before round 1).
                if (state.currentRound > 1 && state.totalRounds > 0) {
                    audioHapticEngine.announceRoundComplete(state.currentRound - 1, state.totalRounds)
                }
            }
            ContractionTablePhase.CRUISE -> {
                audioHapticEngine.announceHoldBegin()
            }
            ContractionTablePhase.STRUGGLE -> {
                // Vibration already fired in logFirstContraction().
            }
            ContractionTablePhase.COMPLETE -> {
                audioHapticEngine.announceSessionComplete()
            }
            ContractionTablePhase.IDLE -> { /* no cue */ }
        }
    }

    // ── Session saving ──────────────────────────────────────────────────────

    private suspend fun saveSession(finalState: ContractionTableState): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val totalDurationMs = now - sessionStartMs
        val s = _uiState.value
        val deviceLabel = hrDataSource.activeHrDeviceLabel()
        val telemetrySnapshot = telemetrySamples.toList()
        telemetrySamples.clear()

        // Compute aggregates from telemetry
        val maxHr = telemetrySnapshot.mapNotNull { it.hr }.maxOrNull()
        val minHr = telemetrySnapshot.mapNotNull { it.hr }.minOrNull()
        val lowestSpO2 = telemetrySnapshot.mapNotNull { it.spO2 }.minOrNull()

        // Build tableParamsJson
        val paramsJson = buildParamsJson(s, finalState.roundResults)

        // Count completed rounds
        val completedRounds = finalState.roundResults.count { it.completed }
        val totalRoundsAttempted = finalState.roundResults.size

        // 1. Save ApneaSessionEntity
        val sessionEntity = ApneaSessionEntity(
            timestamp = now,
            tableType = s.mode.tableType(),
            tableVariant = s.mode.name,
            tableParamsJson = paramsJson,
            pbAtSessionMs = 0L,
            totalSessionDurationMs = totalDurationMs,
            contractionTimestampsJson = "[]",
            maxHrBpm = maxHr,
            lowestSpO2 = lowestSpO2,
            roundsCompleted = completedRounds,
            totalRounds = totalRoundsAttempted,
            hrDeviceId = deviceLabel
        )
        val sessionId = sessionRepository.saveSession(sessionEntity)

        // 2. Save ApneaRecordEntity.
        //
        // Headline duration semantics per mode:
        //  - TILL_CONTRACTION: longest single-round hold (== longest cruise) —
        //    the drill's goal is extending the easy phase.
        //  - CONTRACTION_COUNT: total hold time across the session (consistent
        //    with Progressive O₂), partitioned into PB pools by the target.
        val totalHoldTimeMs = finalState.totalHoldTimeMs
        val longestHoldMs = finalState.longestHoldMs
        val headlineDurationMs = if (s.mode == ContractionTableMode.TILL_CONTRACTION) longestHoldMs else totalHoldTimeMs

        // First contraction of round 1 feeds the forecast feature extractor.
        val firstContractionMs = finalState.roundResults.firstOrNull()?.cruiseMs

        // Check broader PB BEFORE saving so queries compare against prior records only
        val drill = currentDrillContext()
        val pbResult = if (headlineDurationMs > 0L) {
            apneaRepository.checkBroaderPersonalBest(
                drill, headlineDurationMs, s.lungVolume, s.prepType, s.timeOfDay, s.posture, s.audio
            )
        } else null

        val recordId = apneaRepository.saveRecord(
            ApneaRecordEntity(
                timestamp = now,
                durationMs = headlineDurationMs,
                lungVolume = s.lungVolume,
                prepType = s.prepType,
                minHrBpm = minHr?.toFloat() ?: 0f,
                maxHrBpm = maxHr?.toFloat() ?: 0f,
                tableType = s.mode.tableType(),
                lowestSpO2 = lowestSpO2,
                timeOfDay = s.timeOfDay,
                hrDeviceId = deviceLabel,
                posture = s.posture,
                audio = s.audio,
                drillParamValue = if (s.mode == ContractionTableMode.CONTRACTION_COUNT) s.contractionTarget else null,
                firstContractionMs = firstContractionMs,
                guidedAudioName = if (s.audio == AudioSetting.GUIDED.name) s.guidedSelectedName else null
            )
        )

        // Show PB celebration + fire Tail habit if applicable
        if (pbResult != null) {
            _uiState.update { it.copy(newPersonalBest = pbResult) }
            try { habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD) } catch (_: Exception) {}
        }

        // Fire Tail habit for every completed contraction-table session
        // (mode-specific slot: Till Contraction vs Contraction Count)
        try {
            val holdMinutes = HabitIntegrationRepository.millisToMinutes(totalHoldTimeMs)
            val slot = if (s.mode == ContractionTableMode.TILL_CONTRACTION) Slot.TILL_CONTRACTION
                       else Slot.CONTRACTION_COUNT
            habitRepo.sendHabitIncrementWithMinutes(slot, holdMinutes)
            habitRepo.sendSecondaryValueIncrement(slot, 1)
        } catch (_: Exception) {}

        // Fire music habit if applicable (once per TimeOfDay per day)
        try { habitRepo.sendMusicHabitIncrementIfNeeded(s.audio, s.timeOfDay) } catch (_: Exception) {}

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

        // Refresh derived data for the setup screen
        loadPersonalBests()
        loadSessionHistory()

        return sessionId to recordId
    }

    // ── JSON helpers ────────────────────────────────────────────────────────

    private fun buildParamsJson(
        config: ContractionTableUiState,
        rounds: List<ContractionTableRoundResult>
    ): String {
        val root = JSONObject()
        root.put("mode", config.mode.name)
        root.put("rounds", config.rounds)
        root.put("restStartSec", config.restStartSec)
        root.put("restEndSec", config.restEndSec)
        if (config.mode == ContractionTableMode.CONTRACTION_COUNT) {
            root.put("contractionTarget", config.contractionTarget)
        }
        val roundsArray = JSONArray()
        for (r in rounds) {
            val obj = JSONObject()
            obj.put("round", r.roundNumber)
            obj.put("restBeforeMs", r.restBeforeMs)
            if (r.cruiseMs != null) {
                obj.put("cruiseMs", r.cruiseMs)
            } else {
                obj.put("cruiseMs", JSONObject.NULL)
            }
            obj.put("struggleMs", r.struggleMs)
            obj.put("totalHoldMs", r.totalHoldMs)
            obj.put("contractions", r.contractionsLogged)
            obj.put("completed", r.completed)
            obj.put("endedEarly", r.endedEarly)
            roundsArray.put(obj)
        }
        root.put("roundResults", roundsArray)
        return root.toString()
    }

    private fun parseHistoryEntry(
        recordId: Long,
        timestamp: Long,
        paramsJson: String
    ): ContractionTableHistoryEntry? {
        return try {
            val json = JSONObject(paramsJson)
            val mode = try {
                ContractionTableMode.valueOf(json.optString("mode", ContractionTableMode.TILL_CONTRACTION.name))
            } catch (_: Exception) { ContractionTableMode.TILL_CONTRACTION }
            val roundsCfg = json.optInt("rounds", 0)
            val restStart = json.optInt("restStartSec", 0)
            val restEnd = json.optInt("restEndSec", 0)
            val target = json.optInt("contractionTarget", 0)

            val roundsArray = json.optJSONArray("roundResults") ?: JSONArray()
            var bestCruise: Long? = null
            var longestHold = 0L
            var totalHold = 0L
            var completed = 0
            var ratioSum = 0f
            var ratioCount = 0
            for (i in 0 until roundsArray.length()) {
                val r = roundsArray.getJSONObject(i)
                val cruise = if (r.isNull("cruiseMs")) null else r.optLong("cruiseMs", 0L)
                val holdMs = r.optLong("totalHoldMs", 0L)
                if (cruise != null && (bestCruise == null || cruise > bestCruise)) bestCruise = cruise
                if (holdMs > longestHold) longestHold = holdMs
                totalHold += holdMs
                if (r.optBoolean("completed", false)) completed++
                if (cruise != null && holdMs > 0) {
                    ratioSum += cruise.toFloat() / holdMs.toFloat()
                    ratioCount++
                }
            }

            val configLabel = if (mode == ContractionTableMode.TILL_CONTRACTION) {
                "$roundsCfg × ${restStart}s rest"
            } else {
                "$roundsCfg × ${restStart}s rest · ${target}c"
            }

            ContractionTableHistoryEntry(
                recordId = recordId,
                timestamp = timestamp,
                mode = mode,
                configLabel = configLabel,
                roundsCompleted = completed,
                roundsTotal = roundsArray.length(),
                bestCruiseMs = bestCruise,
                longestHoldMs = longestHold,
                totalHoldMs = totalHold,
                avgCruiseRatio = if (ratioCount > 0) ratioSum / ratioCount else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse session params at $timestamp", e)
            null
        }
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

    override fun onCleared() {
        guidedAudioManager.stopPlayback()
        // Also stop Spotify if still tracking
        if (_uiState.value.isMusicMode) {
            try {
                spotifyManager.stopTracking()
                spotifyManager.sendPauseAndRewindCommand()
            } catch (_: Exception) {}
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ContractionTableVM"

        // tableType strings — kept identical to the legacy Wonka values so the
        // existing stats / history / ranking plumbing continues to work.
        const val TABLE_TYPE_TILL = "WONKA_FIRST_CONTRACTION"
        const val TABLE_TYPE_COUNT = "WONKA_ENDURANCE"

        private const val PREF_MODE = "contraction_mode"
        private const val PREF_TILL_ROUNDS = "ct_till_rounds"
        private const val PREF_TILL_REST_START = "ct_till_rest_start_sec"
        private const val PREF_TILL_REST_END = "ct_till_rest_end_sec"
        private const val PREF_COUNT_ROUNDS = "ct_count_rounds"
        private const val PREF_COUNT_REST_START = "ct_count_rest_start_sec"
        private const val PREF_COUNT_REST_END = "ct_count_rest_end_sec"
        private const val PREF_COUNT_TARGET = "ct_count_target"

        const val DEFAULT_TILL_ROUNDS = 8
        const val DEFAULT_TILL_REST_START_SEC = 90
        const val DEFAULT_TILL_REST_END_SEC = 60
        const val DEFAULT_COUNT_ROUNDS = 6
        const val DEFAULT_COUNT_REST_START_SEC = 120
        const val DEFAULT_COUNT_REST_END_SEC = 60
        const val DEFAULT_COUNT_TARGET = 8

        const val MIN_ROUNDS = 1
        const val MAX_ROUNDS = 20
        const val MIN_REST_SEC = 15
        const val MAX_REST_SEC = 300
        const val MIN_TARGET = 1
        const val MAX_TARGET = 50

    }
}

/** Storage key for this mode's records, shared with stats/history plumbing. */
fun ContractionTableMode.tableType(): String =
    if (this == ContractionTableMode.TILL_CONTRACTION) ContractionTableViewModel.TABLE_TYPE_TILL
    else ContractionTableViewModel.TABLE_TYPE_COUNT
