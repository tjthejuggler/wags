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
import com.example.wags.domain.model.DrillContext
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.trophyCount
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeDimension
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.domain.usecase.apnea.ResonancePrepGate
import com.example.wags.domain.usecase.apnea.MinBreathHoldResult
import com.example.wags.domain.usecase.apnea.MinBreathPhase
import com.example.wags.domain.usecase.apnea.MinBreathState
import com.example.wags.domain.usecase.apnea.MinBreathStateMachine
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.usecase.session.BiofeedbackHrSound
import com.example.wags.domain.usecase.session.BiofeedbackSonificationEngine
import com.example.wags.domain.usecase.session.BiofeedbackSpo2Texture
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────
    /** Current eucapnic configuration (when EUCAPNIC_DIAPHRAGMATIC prep type is selected). */
    val eucapnicConfig: com.example.wags.domain.model.EucapnicConfig? = null,
    /** True after the eucapnic pacer completed — the start button switches to START HOLD. */
    val eucapnicPrepCompleted: Boolean = false,
    // Final vitals (captured at session completion - used in CompleteContent)
    val finalHr: Int? = null,
    val finalSpO2: Int? = null,
    // Past sessions
    val pastDurations: List<DurationHistory> = emptyList(),
    // 5 standard apnea settings
    val lungVolume: String = "FULL",
    val prepType: String = "NO_PREP",
    val timeOfDay: String = "DAY",
    val posture: String = "LAYING",
    val audio: String = "SILENCE",
    // Filter state — selected option values per dimension (multi-select).
    // All options selected = unfiltered; empty set = nothing matches.
    val filterLungVolume: Set<String> = SettingFilterOptions.LUNG_VOLUMES.toSet(),
    val filterPrepType: Set<String> = SettingFilterOptions.PREP_TYPES.toSet(),
    val filterTimeOfDay: Set<String> = SettingFilterOptions.timeOfDayOptions(byHour = false).toSet(),
    val filterPosture: Set<String> = SettingFilterOptions.POSTURES.toSet(),
    val filterAudio: Set<String> = SettingFilterOptions.AUDIOS.toSet(),
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
    /** True when audio setting is BIOFEEDBACK — controls whether the biofeedback picker is shown. */
    val isBiofeedbackMode: Boolean = false,
    /** Selected biofeedback heartbeat instrument (null = not configured yet). */
    val biofeedbackHrSound: BiofeedbackHrSound? = null,
    /** Selected biofeedback SpO2 background texture (null = not configured yet). */
    val biofeedbackSpo2Texture: BiofeedbackSpo2Texture? = null,
    /** Volume (0..1) of the biofeedback heartbeat layer — persisted across runs. */
    val biofeedbackHrVolume: Float = 1f,
    /** Volume (0..1) of the biofeedback SpO2 soundscape layer — persisted across runs. */
    val biofeedbackSpo2Volume: Float = 1f,
    val guidedAudios: List<GuidedAudioEntity> = emptyList(),
    val guidedSelectedId: Long = -1L,
    val guidedSelectedName: String = "",
    val guidedCompletionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSongs: List<SpotifyTrackDetail> = emptyList(),
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
    val recordForecast: RecordForecast? = null,
    /** True when no resonance breathing session ended within the last ~5 minutes (RESONANCE prep locked). */
    val resonancePrepLocked: Boolean = false
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
    private val eucapnicConfigRepository: EucapnicConfigRepository,
    private val hyperLockManager: HyperLockManager,
    private val resonancePrepGate: ResonancePrepGate,
    private val timeDimensionStore: ApneaTimeDimensionStore,
    private val biofeedbackEngine: BiofeedbackSonificationEngine,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
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
    /**
     * Prep type captured at session start. The saved record and its personal-best
     * evaluation use this snapshot, guaranteeing a session that started as
     * RESONANCE is recorded as RESONANCE even if the staleness lock engages
     * while the session is still running (or in the instant between stop and save).
     */
    private var sessionPrepType: String? = null

    // Per-breath-period duration tracking (indexed by hold number that preceded the breath)
    private val breathDurations = mutableMapOf<Int, Long>()

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
                vibrationEnabled = audioHapticEngine.vibrationEnabled,
                isBiofeedbackMode = savedAudio == AudioSetting.BIOFEEDBACK.name,
                biofeedbackHrSound = prefs.getString("biofeedback_hr_sound", null)
                    ?.let { runCatching { BiofeedbackHrSound.valueOf(it) }.getOrNull() },
                biofeedbackSpo2Texture = prefs.getString("biofeedback_spo2_texture", null)
                    ?.let { runCatching { BiofeedbackSpo2Texture.valueOf(it) }.getOrNull() },
                biofeedbackHrVolume = prefs.getFloat("biofeedback_hr_volume", 1f),
                biofeedbackSpo2Volume = prefs.getFloat("biofeedback_spo2_volume", 1f)
            )
        }

        // Load the bundled field recordings for the SpO2 nature textures.
        biofeedbackEngine.loadSamples(appContext)

        // ── Biofeedback sonification — feed live metrics to the engine ──────
        // Forwarding unconditionally is cheap (volatile writes); the engine
        // only consumes the values while a session is actually running.
        viewModelScope.launch {
            hrDataSource.liveHr.collect { hr -> hr?.let { biofeedbackEngine.updateHr(it.toFloat()) } }
        }
        viewModelScope.launch {
            hrDataSource.liveSpO2.collect { spo2 -> spo2?.let { biofeedbackEngine.updateSpO2(it) } }
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
                        // Stop biofeedback sonification if BIOFEEDBACK was selected
                        if (_uiState.value.isBiofeedbackMode) {
                            biofeedbackEngine.stop()
                        }
                        telemetryJob?.cancel()
                        // Persist song history to SharedPreferences
                        if (trackedSongs.isNotEmpty()) {
                            persistSongHistory(trackedSongs)
                        }
                        val recordId = saveSession(state)
                        // Capture final HR/SpO2 values for the completion screen
                        val currentHr = hrDataSource.liveHr.value
                        val currentSpO2 = hrDataSource.liveSpO2.value
                        _uiState.update { it.copy(
                            isSessionActive = false,
                            completedRecordId = recordId,
                            finalHr = currentHr,
                            finalSpO2 = currentSpO2
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
    }

    fun setTimeOfDay(v: String) {
        // BY_HOUR mode: the bucket is automatic (derived from the session start
        // time) — manual time-of-day selection is disabled.
        if (timeDimensionStore.isByHour) return
        // Stamp the edit time so the main ApneaScreen adopts this tod choice on
        // resume: it does not persist tod itself (smart-set from the clock), so
        // the timestamp proves this is a fresh user edit, not a stale leftover.
        prefs.edit()
            .putString("setting_time_of_day", v)
            .putLong("setting_tod_edit_ms", System.currentTimeMillis())
            .apply()
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
                isGuidedMode = isGuided,
                isBiofeedbackMode = v == AudioSetting.BIOFEEDBACK.name
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

    // ── Biofeedback sonification config (prefs shared with Free Hold) ────────

    fun setBiofeedbackHrSound(sound: BiofeedbackHrSound) {
        prefs.edit().putString("biofeedback_hr_sound", sound.name).apply()
        biofeedbackEngine.setHrSound(sound)
        _uiState.update { it.copy(biofeedbackHrSound = sound) }
    }

    fun setBiofeedbackSpo2Texture(texture: BiofeedbackSpo2Texture) {
        prefs.edit().putString("biofeedback_spo2_texture", texture.name).apply()
        biofeedbackEngine.setSpo2Texture(texture)
        _uiState.update { it.copy(biofeedbackSpo2Texture = texture) }
    }

    /** Set the heartbeat layer volume (0..1) — persisted across app runs. */
    fun setBiofeedbackHrVolume(volume: Float) {
        prefs.edit().putFloat("biofeedback_hr_volume", volume).apply()
        biofeedbackEngine.setHrVolume(volume)
        _uiState.update { it.copy(biofeedbackHrVolume = volume) }
    }

    /** Set the SpO2 soundscape layer volume (0..1) — persisted across app runs. */
    fun setBiofeedbackSpo2Volume(volume: Float) {
        prefs.edit().putFloat("biofeedback_spo2_volume", volume).apply()
        biofeedbackEngine.setSpo2Volume(volume)
        _uiState.update { it.copy(biofeedbackSpo2Volume = volume) }
    }

    /** Audition a heartbeat instrument over the currently selected texture. */
    fun previewBiofeedbackHrSound(sound: BiofeedbackHrSound) {
        val state = _uiState.value
        biofeedbackEngine.previewHrSound(
            sound,
            withTexture = state.biofeedbackSpo2Texture ?: BiofeedbackSpo2Texture.NONE,
            spo2Vol = state.biofeedbackSpo2Volume,
            hrVol = state.biofeedbackHrVolume
        )
    }

    /** Audition a SpO2 soundscape under the currently selected instrument. */
    fun previewBiofeedbackSpo2Texture(texture: BiofeedbackSpo2Texture) {
        val state = _uiState.value
        biofeedbackEngine.previewSpo2Texture(
            texture,
            withSound = state.biofeedbackHrSound ?: BiofeedbackHrSound.NONE,
            spo2Vol = state.biofeedbackSpo2Volume,
            hrVol = state.biofeedbackHrVolume
        )
    }

    /** Stop any in-flight picker preview. */
    fun stopBiofeedbackPreview() {
        biofeedbackEngine.stopPreview()
    }

    // ── Filter methods ────────────────────────────────────────────────────────

    fun setFilterLungVolume(v: Set<String>) { _uiState.update { it.copy(filterLungVolume = v) }; loadPastSessions() }
    fun setFilterPrepType(v: Set<String>)   { _uiState.update { it.copy(filterPrepType = v) }; loadPastSessions() }
    fun setFilterTimeOfDay(v: Set<String>) { _uiState.update { it.copy(filterTimeOfDay = v) }; loadPastSessions() }
    fun setFilterPosture(v: Set<String>)   { _uiState.update { it.copy(filterPosture = v) }; loadPastSessions() }
    fun setFilterAudio(v: Set<String>)     { _uiState.update { it.copy(filterAudio = v) }; loadPastSessions() }

    /** Signature of the "settings to be used" (tod normalized to the active dimension). */
    private fun settingsSignature(lv: String, pt: String, tod: String, pos: String, aud: String): String =
        listOf(lv, pt, TimeBuckets.normalizeSessionBucket(tod, timeDimensionStore.current), pos, aud).joinToString("|")

    /** Settings signature the history filters were last synced to (null = never synced). */
    private var filtersSyncedTo: String? = null

    fun resetFilters() {
        val s = _uiState.value
        _uiState.update {
            it.copy(
                filterLungVolume = setOf(s.lungVolume),
                filterPrepType   = setOf(s.prepType),
                filterTimeOfDay  = setOf(TimeBuckets.normalizeSessionBucket(s.timeOfDay, timeDimensionStore.current)),
                filterPosture    = setOf(s.posture),
                filterAudio      = setOf(s.audio),
                guidedCountdownComplete = false
            )
        }
        filtersSyncedTo = settingsSignature(s.lungVolume, s.prepType, s.timeOfDay, s.posture, s.audio)
        loadPastSessions()
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
            loadPastSessions()
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
        val byHour = timeDimensionStore.current == TimeDimension.BY_HOUR
        _uiState.update {
            it.copy(
                filterLungVolume = SettingFilterOptions.LUNG_VOLUMES.toSet(),
                filterPrepType   = SettingFilterOptions.PREP_TYPES.toSet(),
                filterTimeOfDay  = SettingFilterOptions.timeOfDayOptions(byHour).toSet(),
                filterPosture    = SettingFilterOptions.POSTURES.toSet(),
                filterAudio      = SettingFilterOptions.AUDIOS.toSet()
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
     * user starts the hold, so every new session begins with a fresh prep.
     */
    fun setEucapnicPrepCompleted(completed: Boolean) {
        _uiState.update { it.copy(eucapnicPrepCompleted = completed) }
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
                    SpotifyTrackDetail(
                        spotifyUri = "", title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt
                    )
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
                        val first = newSelected.first()
                        spotifyManager.preloadTrackList(
                            allUris, firstTrackTitle = first.title, firstTrackArtist = first.artist
                        )
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

    // ── Spotify preload confirmation ────────────────────────────────────────
    /** Non-null when a staged song could not be verified — UI shows a confirm dialog. */
    val preloadConfirm = spotifyManager.preloadConfirm

    /** User confirmed the song is loaded in Spotify. */
    fun confirmPreloadLoaded() = spotifyManager.confirmPreloadLoaded()

    /** User said the song did not load — retry staging the same selection now. */
    fun retryPreload() = spotifyManager.retryPreload()

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

    // ── Public API ──────────────────────────────────────────────────────────

    fun loadPastSessions() {
        viewModelScope.launch {
            try {
                val allRecords = apneaRepository.getAllRecordsOnce()
                val s = _uiState.value
                val filtered = if (
                    s.filterLungVolume.isEmpty() || s.filterPrepType.isEmpty() || s.filterTimeOfDay.isEmpty() ||
                    s.filterPosture.isEmpty() || s.filterAudio.isEmpty()
                ) {
                    // A category with nothing selected can never match — skip the work.
                    emptyList()
                } else {
                    val byHourTod = s.filterTimeOfDay.any { TimeBuckets.isHourBucket(it) }
                    allRecords
                        .filter { it.tableType == "MIN_BREATH" }
                        .filter { it.lungVolume in s.filterLungVolume }
                        .filter { it.prepType in s.filterPrepType }
                        .filter {
                            (if (byHourTod) TimeBuckets.fromTimestamp(it.timestamp) else it.timeOfDay) in s.filterTimeOfDay
                        }
                        .filter { it.posture in s.filterPosture }
                        .filter { it.audio in s.filterAudio }
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
        sessionPrepType = _uiState.value.prepType
        breathDurations.clear()
        _uiState.update { it.copy(
            isSessionActive = true,
            completedRecordId = null,
            finalHr = null,
            finalSpO2 = null
        ) }

        // Start Spotify if MUSIC is selected.
        // Song was pre-loaded in selectSong() — just resume playback.
        if (_uiState.value.isMusicMode) {
            spotifyManager.startTracking()
            spotifyManager.startSessionPlayback(_uiState.value.selectedSongs.firstOrNull()?.spotifyUri)
        }

        // Start movie playback if MOVIE mode with auto-control enabled
        if (_uiState.value.movieAutoControl) {
            spotifyManager.sendRemotePlayCommand()
        }

        // Start guided audio if GUIDED is selected — but skip if it was already
        // started during the hyper countdown (startMp3WithHyper == true)
        if (_uiState.value.isGuidedMode && !guidedAudioManager.isPlaying) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }

        // Start biofeedback sonification if BIOFEEDBACK is selected — the
        // live HR/SpO2 collectors in init{} keep the engine fed.
        if (_uiState.value.audio == AudioSetting.BIOFEEDBACK.name) {
            _uiState.value.biofeedbackHrSound?.let { biofeedbackEngine.setHrSound(it) }
            _uiState.value.biofeedbackSpo2Texture?.let { biofeedbackEngine.setSpo2Texture(it) }
            biofeedbackEngine.setHrVolume(_uiState.value.biofeedbackHrVolume)
            biofeedbackEngine.setSpo2Volume(_uiState.value.biofeedbackSpo2Volume)
            biofeedbackEngine.start(viewModelScope)
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

        // Stop biofeedback sonification if BIOFEEDBACK was selected
        if (_uiState.value.isBiofeedbackMode) {
            biofeedbackEngine.stop()
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
                // Capture final HR/SpO2 values for the completion screen
                val currentHr = hrDataSource.liveHr.value
                val currentSpO2 = hrDataSource.liveSpO2.value
                _uiState.update { it.copy(
                    completedRecordId = recordId,
                    finalHr = currentHr,
                    finalSpO2 = currentSpO2
                ) }
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

        // Stop biofeedback sonification if BIOFEEDBACK was selected
        if (_uiState.value.isBiofeedbackMode) {
            biofeedbackEngine.stop()
        }

        // Mark inactive BEFORE stopping the state machine to prevent the
        // init-block observer from saving when it sees COMPLETE.
        _uiState.update { it.copy(isSessionActive = false) }
        stateMachine.stop()
        sessionPrepType = null
        // Do NOT save the session or fire tail increments
    }

    fun markContraction() {
        // Vibrate only when this tap actually records the FIRST contraction of
        // the current hold — same guard as MinBreathStateMachine.markContraction().
        val current = stateMachine.state.value
        val accepted = current.phase == MinBreathPhase.HOLD && current.currentHoldContractionMs == null
        stateMachine.markContraction()
        if (accepted) audioHapticEngine.vibrateContractionLogged()
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
        _uiState.update { it.copy(
            completedRecordId = null,
            finalHr = null,
            finalSpO2 = null
        ) }
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
        // Timestamps mark when the session STARTED (the user's Start click),
        // not when it was saved — history should show the start time.
        val sessionStartTs = if (sessionStartMs > 0L) sessionStartMs else now
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
            timestamp = sessionStartTs,
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

        // Prep type comes from the session-start snapshot so the record reflects
        // what the session actually started with.
        val sessionPrep = sessionPrepType
        val currentState = _uiState.value

        // Honor the user's explicit audio choice. Never downgrade MUSIC to SILENCE
        // based on Spotify track tracking, which is unreliable and caused music
        // sessions to be mis-recorded as silent. The user's setting is authoritative.
        val effectiveAudio = currentState.audio

        // Check broader PB BEFORE saving so queries compare against prior records only
        val drill = DrillContext.minBreath(sessionDurationSec)
        val pbResult = if (totalHoldTimeMs > 0L) {
            apneaRepository.checkBroaderPersonalBest(
                drill, totalHoldTimeMs,
                currentState.lungVolume, sessionPrep ?: currentState.prepType, currentState.timeOfDay,
                currentState.posture, effectiveAudio
            )
        } else null

        // Capture guided hyper state at save time
        val wasGuided = currentState.guidedHyperEnabled && currentState.isHyperPrep

        val recordId = apneaRepository.saveRecord(
            ApneaRecordEntity(
                timestamp = sessionStartTs,
                durationMs = totalHoldTimeMs,
                lungVolume = currentState.lungVolume,
                prepType = sessionPrep ?: currentState.prepType,
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
                biofeedbackHrSound = if (effectiveAudio == AudioSetting.BIOFEEDBACK.name) currentState.biofeedbackHrSound?.name else null,
                biofeedbackSpo2Texture = if (effectiveAudio == AudioSetting.BIOFEEDBACK.name) currentState.biofeedbackSpo2Texture?.name else null,
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
        try {
            val holdMinutes = HabitIntegrationRepository.millisToMinutes(totalHoldTimeMs)
            // Sessions-primary habit: +1 session (primary) + hold minutes
            // (minutes slot) in ONE atomic Tail write.
            habitRepo.sendSessionWithMinutes(Slot.MIN_BREATH, holdMinutes)
        } catch (_: Exception) {}

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
        biofeedbackEngine.stop()
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
