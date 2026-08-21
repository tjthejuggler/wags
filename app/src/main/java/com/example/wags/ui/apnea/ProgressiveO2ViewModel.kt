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
import com.example.wags.data.repository.ApneaTimeDimensionStore
import com.example.wags.data.repository.EucapnicConfigRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.DrillContext
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeDimension
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.domain.usecase.apnea.ResonancePrepGate
import com.example.wags.domain.usecase.apnea.ProgressiveO2Phase
import com.example.wags.domain.usecase.apnea.ProgressiveO2RoundResult
import com.example.wags.domain.usecase.apnea.ProgressiveO2State
import com.example.wags.domain.usecase.apnea.ProgressiveO2StateMachine
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named

// ── UI state ────────────────────────────────────────────────────────────────

data class ProgressiveO2UiState(
    val sessionState: ProgressiveO2State = ProgressiveO2State(),
    /** User-configurable breath period in seconds (shown on setup screen). */
    val breathPeriodSec: Int = 60,
    /** Breath period history for the setup screen display. */
    val pastBreathPeriods: List<BreathPeriodHistory> = emptyList(),
    val isSessionActive: Boolean = false,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Set after session is saved — used for navigation to record detail screen. */
    val completedRecordId: Long? = null,
    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────
    /** Current eucapnic configuration (when EUCAPNIC_DIAPHRAGMATIC prep type is selected). */
    val eucapnicConfig: com.example.wags.domain.model.EucapnicConfig? = null,
    /** True after the eucapnic pacer completed — the start button switches to START HOLD. */
    val eucapnicPrepCompleted: Boolean = false,
    // ── Apnea settings (read from SharedPreferences) ─────────────────────────
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
    // ── Song picker / Spotify ────────────────────────────────────────────────
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
    // ── Personal best celebration ──────────────────────────────────────────────
    val newPersonalBest: PersonalBestResult? = null,
    // ── Progressive O2 personal bests ───────────────────────────────────────────
    /** Best total hold time for current breath period + current 5 settings (ms). Null if no records. */
    val personalBestCurrentSettingsMs: Long? = null,
    /** Best total hold time for current breath period across all 5 settings (ms). Null if no records. */
    val personalBestBreathPeriodMs: Long? = null,
    /** Best total hold time across all breath periods and all settings (ms). Null if no records. */
    val personalBestGlobalMs: Long? = null,
    // ── Record-breaking forecast ──────────────────────────────────────────────
    /** Forecast for the current settings combination. Null when insufficient data. */
    val recordForecast: RecordForecast? = null,
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
    /** True when no resonance breathing session ended within the last ~5 minutes (RESONANCE prep locked). */
    val resonancePrepLocked: Boolean = false
)

data class BreathPeriodHistory(
    val breathPeriodSec: Int,
    /** Longest completed hold (in seconds) across all sessions with this breath period. */
    val maxHoldReachedSec: Int,
    /** How many sessions used this breath period. */
    val sessionCount: Int,
    /** Record ID of the record that achieved the max hold — used for navigation to detail. */
    val maxHoldRecordId: Long
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class ProgressiveO2ViewModel @Inject constructor(
    private val stateMachine: ProgressiveO2StateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val hrDataSource: HrDataSource,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    private val eucapnicConfigRepository: EucapnicConfigRepository,
    private val hyperLockManager: HyperLockManager,
    private val resonancePrepGate: ResonancePrepGate,
    private val timeDimensionStore: ApneaTimeDimensionStore,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressiveO2UiState())

    val uiState: StateFlow<ProgressiveO2UiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        stateMachine.state,
        spotifyAuthManager.isConnected
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val ui = args[0] as ProgressiveO2UiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val session = args[3] as ProgressiveO2State
        val connected = args[4] as Boolean
        ui.copy(sessionState = session, liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProgressiveO2UiState()
    )

    // Telemetry collection
    private data class TelemetrySample(val timestampMs: Long, val hr: Int?, val spO2: Int?)
    private val telemetrySamples = mutableListOf<TelemetrySample>()
    private var telemetryJob: Job? = null
    private var sessionStartMs: Long = 0L
    /**
     * Prep type captured at session start. The saved record and its personal-best
     * evaluation use this snapshot, guaranteeing a session that started as
     * RESONANCE is recorded as RESONANCE even if the staleness lock engages
     * while the session is still running (or in the instant between stop and save).
     */
    private var sessionPrepType: String? = null

    // Track previous phase + timer for audio/haptic cues (edge-triggered warnings)
    private var previousPhase: ProgressiveO2Phase = ProgressiveO2Phase.IDLE
    private var previousTimerMs: Long = 0L

    // Spotify tracks played during the session (captured at stop time)
    private var trackedSongs: List<SpotifySong> = emptyList()

    /** Selected time-bucket dimension (Time of Day vs By the Hour) — drives selector/filter UI. */
    val timeDimension: StateFlow<TimeDimension> = timeDimensionStore.dimension

    /**
     * Effective time-bucket for the collapsed settings banner: the selected
     * Morning/Day/Night name, or the automatic current-hour bucket ("H14")
     * when By-the-Hour is active. Refreshes automatically when the
     * wall-clock hour rolls over.
     */
    val effectiveTod: StateFlow<String> = timeDimensionStore.effectiveTod(
        _uiState.map { it.timeOfDay }.distinctUntilChanged()
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TimeBuckets.normalizeSessionBucket(_uiState.value.timeOfDay, timeDimensionStore.current)
    )

    /** Bumped when settings change — triggers forecast recompute. */
    private val _forecastRefreshTrigger = MutableStateFlow(0)

    /** Call after any settings change to recompute the forecast. */
    private fun refreshForecast() { _forecastRefreshTrigger.value++ }

    init {
        // Restore persisted breath period
        val savedBreathPeriod = prefs.getInt("prog_o2_breath_period_sec", 60)

        // Read persisted apnea settings
        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
        val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"
        val savedAudio      = prefs.getString("setting_audio", "SILENCE") ?: "SILENCE"

        val isHyperPrep = savedPrepType == PrepType.HYPER.name

        _uiState.update {
            it.copy(
                breathPeriodSec = savedBreathPeriod,
                lungVolume  = savedLungVolume,
                prepType    = savedPrepType,
                timeOfDay   = TimeOfDay.fromCurrentTime().name,
                posture     = savedPosture,
                audio       = savedAudio,
                isMusicMode = savedAudio == AudioSetting.MUSIC.name,
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

        // ── Resonance prep staleness lock ──────────────────────────────────────
        viewModelScope.launch {
            resonancePrepGate.isLocked.collect { locked ->
                _uiState.update { it.copy(resonancePrepLocked = locked) }
                // Auto-deselect RESONANCE only while idle — never yank the setting
                // mid-session. The rule is that a resonance-prepped session cannot
                // *start* after the 5-minute window; once the session has started
                // with RESONANCE it stays RESONANCE for its entire duration.
                if (locked && _uiState.value.prepType == PrepType.RESONANCE.name &&
                    !_uiState.value.isSessionActive
                ) {
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

        // Load personal bests for Progressive O2
        loadPersonalBests()

        // Observe state machine for audio/haptic cues
        viewModelScope.launch {
            stateMachine.state.collect { state ->
                handlePhaseTransition(state)
                previousPhase = state.phase
            }
        }

        // ── Record-breaking forecast: recompute when settings change ──────────
        viewModelScope.launch {
            _forecastRefreshTrigger.collectLatest {
                delay(150) // debounce
                try {
                    val s = _uiState.value
                    // Pass ALL Progressive O₂ records (not pre-filtered by breath period).
                    // The breath period is supplied as drillParam so the whole history feeds
                    // one regression; pre-filtering starved the fit and pinned every
                    // probability at 100%.
                    val records = apneaRepository.getAllProgressiveO2Once()
                    val settings = ForecastSettings(
                        lungVolume = s.lungVolume,
                        prepType = s.prepType,
                        // BY_HOUR mode: resolve the automatic hour bucket so the
                        // calculator matches records by start-time hour.
                        timeOfDay = TimeBuckets.normalizeSessionBucket(s.timeOfDay, timeDimensionStore.current),
                        posture = s.posture,
                        audio = s.audio
                    )
                    val forecast = RecordForecastCalculator.compute(
                        records = records,
                        settings = settings,
                        nowEpochMs = System.currentTimeMillis(),
                        recordLabel = "sessions",
                        drillParam = s.breathPeriodSec
                    )
                    _uiState.update { it.copy(recordForecast = if (forecast.status == ForecastStatus.Ready) forecast else null) }
                } catch (_: Exception) { }
            }
        }
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
        val isHyper = v == PrepType.HYPER.name
        _uiState.update {
            it.copy(
                prepType = v,
                isHyperPrep = isHyper,
                guidedHyperEnabled = if (isHyper) it.guidedHyperEnabled else false
            )
        }
        refreshForecast()
        loadPersonalBests()
    }

    fun setTimeOfDay(v: String) {
        // BY_HOUR mode: the bucket is automatic (derived from the session start
        // time) — manual time-of-day selection is disabled.
        if (timeDimensionStore.isByHour) return
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

    /** Load personal bests for Progressive O2. */
    private fun loadPersonalBests() {
        viewModelScope.launch {
            val s = _uiState.value
            val currentSettingsBest = apneaRepository.getProgressiveO2BestForCurrentSettings(
                breathPeriodSec = s.breathPeriodSec,
                lungVolume = s.lungVolume,
                prepType = s.prepType,
                timeOfDay = s.timeOfDay,
                posture = s.posture,
                audio = s.audio
            )
            val breathPeriodBest = apneaRepository.getProgressiveO2BestForBreathPeriod(
                breathPeriodSec = s.breathPeriodSec
            )
            val globalBest = apneaRepository.getProgressiveO2BestGlobal()
            _uiState.update {
                it.copy(
                    personalBestCurrentSettingsMs = currentSettingsBest,
                    personalBestBreathPeriodMs = breathPeriodBest,
                    personalBestGlobalMs = globalBest
                )
            }
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        audioHapticEngine.voiceEnabled = enabled
        _uiState.update { it.copy(voiceEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        audioHapticEngine.vibrationEnabled = enabled
        _uiState.update { it.copy(vibrationEnabled = enabled) }
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
        // After guided hyper completes, the user must tap Start to begin the session
    }

    fun onGuidedCountdownCancelled() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
        // Stop guided audio if it was started with hyper
        if (_uiState.value.isGuidedMode && _uiState.value.startMp3WithHyper) {
            guidedAudioManager.stopPlayback()
        }
    }

    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────

    /**
     * Update the eucapnic configuration.
     */
    fun updateEucapnicConfig(config: EucapnicConfig) {
        _uiState.update { it.copy(eucapnicConfig = config) }
    }

    /**
     * Load a saved eucapnic configuration.
     */
    fun loadEucapnicConfiguration(config: EucapnicConfig) {
        _uiState.update { it.copy(eucapnicConfig = config) }
    }

    /**
     * Mark eucapnic prep as completed (called after returning from the
     * eucapnic pacer screen). Consumed — and reset to false — when the
     * user starts the session, so every new session begins with a fresh prep.
     */
    fun setEucapnicPrepCompleted(completed: Boolean) {
        _uiState.update { it.copy(eucapnicPrepCompleted = completed) }
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
            // On a forced refresh we bypass the cache and re-fetch everything.
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

    /**
     * Stable identity key for a song card — uses URI when available, otherwise title+artist.
     */
    private fun SpotifyTrackDetail.cardKey(): String =
        if (spotifyUri.isNotBlank()) spotifyUri else "$title|$artist"

    /**
     * Called when the user taps a song card in the picker.
     * Toggles the song in the selected songs list with numbered ordering.
     * If the song is already selected, it's deselected and other songs shift down.
     * If not selected, it's added to the end of the list.
     */
    fun selectSong(track: SpotifyTrackDetail) {
        val trackKey = track.cardKey()
        _uiState.update { currentState ->
            val currentSelected = currentState.selectedSongs
            val existingIndex = currentSelected.indexOfFirst { it.cardKey() == trackKey }
            
            val newSelected = if (existingIndex >= 0) {
                // Song is already selected - deselect it
                currentSelected.toMutableList().apply { removeAt(existingIndex) }
            } else {
                // Song is not selected - add it to the end
                currentSelected + track
            }
            
            // Sync Spotify playback with the new selection (if connected).
            // The FULL ordered selection is sent directly to Spotify via
            // PUT /v1/me/player/play with the uris array. This replaces the
            // active playback context atomically, avoiding playlist creation.
            // Re-send on EVERY selection change so all songs are queued in order.
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

    // ── Filter methods ────────────────────────────────────────────────────────

    fun setFilterLungVolume(v: String) { _uiState.update { it.copy(filterLungVolume = v) }; loadBreathPeriodHistory() }
    fun setFilterPrepType(v: String)   { _uiState.update { it.copy(filterPrepType = v) }; loadBreathPeriodHistory() }
    fun setFilterTimeOfDay(v: String) { _uiState.update { it.copy(filterTimeOfDay = v) }; loadBreathPeriodHistory() }
    fun setFilterPosture(v: String)   { _uiState.update { it.copy(filterPosture = v) }; loadBreathPeriodHistory() }
    fun setFilterAudio(v: String)     { _uiState.update { it.copy(filterAudio = v) }; loadBreathPeriodHistory() }

    /** Signature of the "settings to be used" (tod normalized to the active dimension). */
    private fun settingsSignature(lv: String, pt: String, tod: String, pos: String, aud: String): String =
        listOf(lv, pt, TimeBuckets.normalizeSessionBucket(tod, timeDimensionStore.current), pos, aud).joinToString("|")

    /** Settings signature the history filters were last synced to (null = never synced). */
    private var filtersSyncedTo: String? = null

    fun resetFilters() {
        val s = _uiState.value
        _uiState.update {
            it.copy(
                filterLungVolume = s.lungVolume,
                filterPrepType   = s.prepType,
                filterTimeOfDay  = TimeBuckets.normalizeSessionBucket(s.timeOfDay, timeDimensionStore.current),
                filterPosture    = s.posture,
                filterAudio      = s.audio,
                guidedCountdownComplete = false
            )
        }
        filtersSyncedTo = settingsSignature(s.lungVolume, s.prepType, s.timeOfDay, s.posture, s.audio)
        loadBreathPeriodHistory()
    }

    /**
     * ON_RESUME entry point. Re-reads the persisted "settings to be used" —
     * they may have changed on the main apnea screen or via a record's
     * "use these settings" while this screen was in the back stack:
     *  * changed   → adopt the new settings and re-sync the filters to them;
     *  * unchanged → keep the user's filter edits (e.g. tweaks made in the
     *    filter dialog before visiting a record's detail screen) and only
     *    refresh the history list so edits/deletes made away still show up.
     */
    fun syncFiltersOnResume() {
        // First sync after ViewModel creation: mirror the current settings
        // (timeOfDay is smart-set from the clock at init, not read from prefs).
        if (filtersSyncedTo == null) { resetFilters(); return }

        val s = _uiState.value
        val savedLungVolume = prefs.getString("setting_lung_volume", s.lungVolume) ?: s.lungVolume
        val savedPrepType   = prefs.getString("setting_prep_type",   s.prepType)   ?: s.prepType
        val savedTimeOfDay  = prefs.getString("setting_time_of_day", s.timeOfDay)  ?: s.timeOfDay
        val savedPosture    = prefs.getString("setting_posture",    s.posture)    ?: s.posture
        val savedAudio      = prefs.getString("setting_audio",      s.audio)      ?: s.audio

        if (settingsSignature(savedLungVolume, savedPrepType, savedTimeOfDay, savedPosture, savedAudio) == filtersSyncedTo) {
            loadBreathPeriodHistory()
            return
        }
        if (savedLungVolume != s.lungVolume) setLungVolume(savedLungVolume)
        if (savedPrepType != s.prepType)     setPrepType(savedPrepType)
        if (savedTimeOfDay != s.timeOfDay)   setTimeOfDay(savedTimeOfDay)
        if (savedPosture != s.posture)       setPosture(savedPosture)
        if (savedAudio != s.audio)           setAudio(savedAudio)
        resetFilters()
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
        loadBreathPeriodHistory()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun loadBreathPeriodHistory() {
        viewModelScope.launch {
            try {
                val allRecords = apneaRepository.getAllRecordsOnce()
                val allSessions = sessionRepository.getAllSessionsOnce()
                // Create a map of timestamp -> session for quick lookup
                val sessionMap = allSessions.associateBy { it.timestamp }
                
                val s = _uiState.value
                val filtered = allRecords
                    .filter { it.tableType == "PROGRESSIVE_O2" }
                    .let { records ->
                        var result = records
                        if (s.filterLungVolume.isNotEmpty()) result = result.filter { it.lungVolume == s.filterLungVolume }
                        if (s.filterPrepType.isNotEmpty()) result = result.filter { it.prepType == s.filterPrepType }
                        if (s.filterTimeOfDay.isNotEmpty()) result = result.filter {
                            (if (TimeBuckets.isHourBucket(s.filterTimeOfDay)) TimeBuckets.fromTimestamp(it.timestamp) else it.timeOfDay) == s.filterTimeOfDay
                        }
                        if (s.filterPosture.isNotEmpty()) result = result.filter { it.posture == s.filterPosture }
                        if (s.filterAudio.isNotEmpty()) result = result.filter { it.audio == s.filterAudio }
                        result
                    }
                val history = buildBreathPeriodHistoryFromRecords(filtered, sessionMap)
                _uiState.update { it.copy(pastBreathPeriods = history) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load breath period history", e)
            }
        }
    }

    fun setBreathPeriod(seconds: Int) {
        _uiState.update { it.copy(breathPeriodSec = seconds) }
        prefs.edit().putInt("prog_o2_breath_period_sec", seconds).apply()
        refreshForecast()
    }

    fun startSession() {
        val breathPeriodMs = _uiState.value.breathPeriodSec * 1000L
        sessionStartMs = System.currentTimeMillis()
        sessionPrepType = _uiState.value.prepType
        _uiState.update { it.copy(isSessionActive = true, completedRecordId = null) }

        // Start Spotify if MUSIC is selected.
        // Song was pre-loaded in selectSong() — just resume playback.
        if (_uiState.value.isMusicMode) {
            spotifyManager.startTracking()
            spotifyManager.sendPlayCommand()
        }

        // Start guided audio if GUIDED is selected — but skip if it was already
        // started during the hyper countdown (startMp3WithHyper == true)
        if (_uiState.value.isGuidedMode && !guidedAudioManager.isPlaying) {
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

        stateMachine.start(breathPeriodMs, viewModelScope)
    }

    /**
     * Stops the session and saves the record.
     * Called when the user explicitly stops the session via the Stop button
     * or when the session completes naturally.
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

        // Stop Spotify if MUSIC was selected (no tracking save since we're cancelling)
        if (_uiState.value.isMusicMode) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }

        // Stop guided audio if GUIDED was selected
        if (_uiState.value.isGuidedMode) {
            guidedAudioManager.stopPlayback()
        }

        _uiState.update { it.copy(isSessionActive = false) }
        stateMachine.stop()
        sessionPrepType = null
        // Do NOT save the session or fire tail increments
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
     * Restarts the same Progressive O₂ session from scratch without navigating away.
     * Cancels any running session, resets result state, then calls [startSession] again.
     * Called by [ProgressiveO2PipContent] when the user taps "Again" inside PiP.
     */
    fun restartSameSession() {
        cancelSession()
        _uiState.update { it.copy(completedRecordId = null, newPersonalBest = null) }
        startSession()
    }

    /** Log first contraction during a HOLD phase. */
    fun logFirstContraction() {
        stateMachine.signalFirstContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    // ── Audio / haptic cues ─────────────────────────────────────────────────

    private fun handlePhaseTransition(state: ProgressiveO2State) {
        if (state.phase == previousPhase) {
            // Same phase — edge-trigger the warning waveform exactly once,
            // when the countdown crosses into the configured warning window.
            // (State ticks arrive every ~100 ms, so a plain range check would
            // fire repeatedly.)
            when (state.phase) {
                ProgressiveO2Phase.BREATHING -> {
                    val warning = audioHapticEngine.effectiveBreathWarning
                    if (warning.enabled &&
                        previousTimerMs > warning.windowMs &&
                        state.timerMs <= warning.windowMs
                    ) {
                        audioHapticEngine.playBreathWarning()
                    }
                }
                ProgressiveO2Phase.HOLD -> {
                    val warning = audioHapticEngine.effectiveHoldWarning
                    if (warning.enabled &&
                        previousTimerMs > warning.windowMs &&
                        state.timerMs <= warning.windowMs
                    ) {
                        audioHapticEngine.playHoldWarning()
                    }
                }
                else -> Unit
            }
            previousTimerMs = state.timerMs
            return
        }

        // Phase changed — stop any in-flight warning waveform.
        audioHapticEngine.cancelWarningVibrations()
        previousTimerMs = state.timerMs

        when (state.phase) {
            ProgressiveO2Phase.HOLD -> {
                audioHapticEngine.announceHoldBegin()
            }
            ProgressiveO2Phase.BREATHING -> {
                // When the hold countdown's final-second pulse is enabled it
                // already covers this moment, so the generic buzz is skipped.
                audioHapticEngine.vibrateHoldEnd(countdownCovered = true)
                audioHapticEngine.announceBreath()
            }
            ProgressiveO2Phase.COMPLETE -> {
                audioHapticEngine.announceSessionComplete()
            }
            ProgressiveO2Phase.IDLE -> { /* no cue */ }
        }
    }

    // ── Session saving ──────────────────────────────────────────────────────

    private suspend fun saveSession(finalState: ProgressiveO2State): Long {
        val now = System.currentTimeMillis()
        val totalDurationMs = now - sessionStartMs
        // Timestamps mark when the session STARTED (the user's Start click),
        // not when it was saved — history should show the start time.
        val sessionStartTs = if (sessionStartMs > 0L) sessionStartMs else now
        val breathPeriodSec = _uiState.value.breathPeriodSec
        val deviceLabel = hrDataSource.activeHrDeviceLabel()
        val telemetrySnapshot = telemetrySamples.toList()
        telemetrySamples.clear()

        // Compute aggregates from telemetry
        val maxHr = telemetrySnapshot.mapNotNull { it.hr }.maxOrNull()
        val minHr = telemetrySnapshot.mapNotNull { it.hr }.minOrNull()
        val lowestSpO2 = telemetrySnapshot.mapNotNull { it.spO2 }.minOrNull()

        // Build tableParamsJson
        val paramsJson = buildParamsJson(breathPeriodSec, finalState.roundResults)

        // Count completed rounds
        val completedRounds = finalState.roundResults.count { it.completed }
        val totalRoundsAttempted = finalState.roundResults.size

        // 1. Save ApneaSessionEntity
        val sessionEntity = ApneaSessionEntity(
            timestamp = sessionStartTs,
            tableType = "PROGRESSIVE_O2",
            tableVariant = "ENDLESS",
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

        // 2. Save ApneaRecordEntity (total hold time across all rounds as durationMs)
        val totalHoldTimeMs = finalState.totalHoldTimeMs

        // Use settings from UI state (already in sync with SharedPreferences).
        // Prep type comes from the session-start snapshot so the record reflects
        // what the session actually started with.
        val currentState = _uiState.value
        val lungVolume = currentState.lungVolume
        val prepType = sessionPrepType ?: currentState.prepType
        val timeOfDay = currentState.timeOfDay
        val posture = currentState.posture
        val audio = currentState.audio

        // Honor the user's explicit audio choice. Never downgrade MUSIC to SILENCE
        // based on Spotify track tracking, which is unreliable and caused music
        // sessions to be mis-recorded as silent. The user's setting is authoritative.
        val effectiveAudio = audio

        // Check broader PB BEFORE saving so queries compare against prior records only
        val drill = DrillContext.progressiveO2(breathPeriodSec)
        val pbResult = if (totalHoldTimeMs > 0L) {
            apneaRepository.checkBroaderPersonalBest(
                drill, totalHoldTimeMs, lungVolume, prepType, timeOfDay, posture, effectiveAudio
            )
        } else null

        // Capture guided hyper state at save time
        val wasGuided = currentState.guidedHyperEnabled && currentState.isHyperPrep

        val recordId = apneaRepository.saveRecord(
            ApneaRecordEntity(
                timestamp = sessionStartTs,
                durationMs = totalHoldTimeMs,
                lungVolume = lungVolume,
                prepType = prepType,
                minHrBpm = minHr?.toFloat() ?: 0f,
                maxHrBpm = maxHr?.toFloat() ?: 0f,
                tableType = "PROGRESSIVE_O2",
                lowestSpO2 = lowestSpO2,
                timeOfDay = timeOfDay,
                hrDeviceId = deviceLabel,
                posture = posture,
                audio = effectiveAudio,
                drillParamValue = breathPeriodSec,
                guidedAudioName = if (effectiveAudio == AudioSetting.GUIDED.name) _uiState.value.guidedSelectedName else null,
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

        // Fire Tail habit for every completed Progressive O2 session
        try {
            val holdMinutes = HabitIntegrationRepository.millisToMinutes(totalHoldTimeMs)
            habitRepo.sendHabitIncrementWithMinutes(Slot.PROGRESSIVE_O2, holdMinutes)
            habitRepo.sendSecondaryValueIncrement(Slot.PROGRESSIVE_O2, 1)
        } catch (_: Exception) {}

        // Fire music habit if applicable (once per TimeOfDay per day)
        try { habitRepo.sendMusicHabitIncrementIfNeeded(effectiveAudio, timeOfDay) } catch (_: Exception) {}

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

    // ── JSON helpers ────────────────────────────────────────────────────────

    private fun buildParamsJson(
        breathPeriodSec: Int,
        rounds: List<ProgressiveO2RoundResult>
    ): String {
        val root = JSONObject()
        root.put("breathPeriodSec", breathPeriodSec)
        val roundsArray = JSONArray()
        for (r in rounds) {
            val obj = JSONObject()
            obj.put("round", r.roundNumber)
            obj.put("targetMs", r.targetHoldMs)
            obj.put("actualMs", r.actualHoldMs)
            obj.put("completed", r.completed)
            if (r.contractionElapsedMs != null) {
                obj.put("contractionMs", r.contractionElapsedMs)
            } else {
                obj.put("contractionMs", JSONObject.NULL)
            }
            roundsArray.put(obj)
        }
        root.put("rounds", roundsArray)
        return root.toString()
    }

    private fun buildBreathPeriodHistoryFromRecords(
        records: List<ApneaRecordEntity>,
        sessionMap: Map<Long, ApneaSessionEntity>
    ): List<BreathPeriodHistory> {
        // Calculate total hold time from session data for each record
        data class RecordWithTotalHold(
            val record: ApneaRecordEntity,
            val totalHoldMs: Long
        )
        
        val recordsWithTotalHold = records.mapNotNull { record ->
            val session = sessionMap[record.timestamp]
            if (session == null) null else {
                val totalHoldMs = try {
                    val json = org.json.JSONObject(session.tableParamsJson)
                    val roundsArray = json.optJSONArray("rounds") ?: return@mapNotNull null
                    var total = 0L
                    for (i in 0 until roundsArray.length()) {
                        val r = roundsArray.getJSONObject(i)
                        total += r.optLong("actualMs", 0L)
                    }
                    total
                } catch (e: Exception) {
                    0L
                }
                RecordWithTotalHold(record, totalHoldMs)
            }
        }
        
        return recordsWithTotalHold
            .filter { it.record.drillParamValue != null && it.record.drillParamValue > 0 }
            .groupBy { it.record.drillParamValue!! }
            .map { (bp, group) ->
                val maxEntry = group.maxByOrNull { it.totalHoldMs }
                BreathPeriodHistory(
                    breathPeriodSec = bp,
                    maxHoldReachedSec = if (maxEntry != null) (maxEntry.totalHoldMs / 1000).toInt() else 0,
                    sessionCount = group.size,
                    maxHoldRecordId = maxEntry?.record?.recordId ?: -1L
                )
            }
            .sortedBy { it.breathPeriodSec }
    }

    private suspend fun buildBreathPeriodHistory(
        sessions: List<ApneaSessionEntity>
    ): List<BreathPeriodHistory> {
        // Parse each session's tableParamsJson to extract breathPeriodSec and total hold time
        data class Parsed(val breathPeriodSec: Int, val totalHoldSec: Int, val sessionId: Long, val timestamp: Long)

        val parsed = sessions.mapNotNull { entity ->
            try {
                val json = JSONObject(entity.tableParamsJson)
                val breathPeriod = json.optInt("breathPeriodSec", 60)
                val roundsArray = json.optJSONArray("rounds") ?: JSONArray()

                var totalHoldMs = 0L
                for (i in 0 until roundsArray.length()) {
                    val r = roundsArray.getJSONObject(i)
                    // Sum actual hold time from all rounds (both completed and partial)
                    val actualMs = r.optLong("actualMs", 0L)
                    totalHoldMs += actualMs
                }
                Parsed(breathPeriod, (totalHoldMs / 1000).toInt(), entity.sessionId, entity.timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse session ${entity.sessionId}", e)
                null
            }
        }

        // Group by breath period, compute max total hold and session count
        return parsed.groupBy { it.breathPeriodSec }
            .map { (bp, group) ->
                val maxEntry = group.maxByOrNull { it.totalHoldSec }
                // Resolve the record ID from the session's timestamp + table type
                val recordId = if (maxEntry != null) {
                    apneaRepository.getRecordByTimestampAndType(maxEntry.timestamp, "PROGRESSIVE_O2")?.recordId ?: -1L
                } else -1L
                BreathPeriodHistory(
                    breathPeriodSec = bp,
                    maxHoldReachedSec = maxEntry?.totalHoldSec ?: 0,
                    sessionCount = group.size,
                    maxHoldRecordId = recordId
                )
            }
            .sortedBy { it.breathPeriodSec }
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
        private const val TAG = "ProgressiveO2VM"
    }
}
