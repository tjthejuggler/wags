package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.dao.ForecastCalibrationDao
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ForecastCalibrationEntity
import com.example.wags.data.db.entity.GuidedAudioEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.data.spotify.TrackInfo
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.DrillContext
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.model.WonkaConfig
import com.example.wags.domain.usecase.apnea.AdvancedApneaPhase
import com.example.wags.domain.usecase.apnea.AdvancedApneaState
import com.example.wags.domain.usecase.apnea.AdvancedApneaStateMachine
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.ApneaState
import com.example.wags.domain.usecase.apnea.ApneaStateMachine
import com.example.wags.domain.usecase.apnea.ApneaTableGenerator
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.forecast.ForecastSettings
import com.example.wags.domain.usecase.apnea.forecast.RecordForecast
import com.example.wags.domain.usecase.apnea.forecast.RecordForecastCalculator
import com.example.wags.domain.usecase.apnea.forecast.ForecastStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * Identifies which accordion section is currently open.
 * Settings is controlled separately (independent toggle).
 * Only one of these can be open at a time.
 */
enum class ApneaSection {
    BEST_TIME,
    TABLE_TRAINING,   // PB + length/difficulty config + O2/CO2 launch buttons
    PROGRESSIVE_O2,
    MIN_BREATH,
    WONKA_CONTRACTION,
    WONKA_ENDURANCE,
    RECENT_RECORDS,
    SESSION_ANALYTICS,
    STATS
}

data class ApneaUiState(
    val apneaState: ApneaState = ApneaState.IDLE,
    val currentRound: Int = 0,
    val totalRounds: Int = 0,
    val remainingSeconds: Long = 0L,
    val currentTable: ApneaTable? = null,
    val personalBestMs: Long = 0L,
    val recentRecords: List<ApneaRecordEntity> = emptyList(),
    val freeHoldActive: Boolean = false,
    val freeHoldDurationMs: Long = 0L,
    val selectedLungVolume: String = "FULL",
    val prepType: PrepType = PrepType.NO_PREP,
    val timeOfDay: TimeOfDay = TimeOfDay.fromCurrentTime(),
    val posture: Posture = Posture.LAYING,
    val audio: AudioSetting = AudioSetting.SILENCE,
    // ── Voice / vibration toggles ─────────────────────────────────────────────
    val voiceEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /** When true, a live elapsed-time counter is shown during the breath hold. */
    val showTimer: Boolean = true,
    /** Best free-hold for the current lungVolume + prepType combination (from DB). */
    val bestTimeForSettingsMs: Long = 0L,
    /** Most recent free-hold duration for the current settings combination (from DB). */
    val lastFreeHoldForSettingsMs: Long = 0L,
    /** recordId of the best free-hold record for the current settings combination (null if none). */
    val bestTimeForSettingsRecordId: Long? = null,
    /** recordId of the most recent free-hold record for the current settings combination (null if none). */
    val lastFreeHoldForSettingsRecordId: Long? = null,
    /**
     * The broadest PB category that the current best record holds.
     * Determines how many trophy emojis to show (1–6). Null when no best record exists.
     */
    val bestTimeTrophyCategory: PersonalBestCategory? = null,
    // ── Progressive O₂ trophy display ────────────────────────────────────────
    /** Currently selected breath period (seconds) for Progressive O₂. */
    val progO2BreathPeriodSec: Int = 60,
    /** Best hold duration for the current breath period + current settings. */
    val progO2BestMs: Long = 0L,
    /** Trophy category for the Progressive O₂ best record. */
    val progO2TrophyCategory: PersonalBestCategory? = null,
    // ── Min Breath trophy display ────────────────────────────────────────────
    /** Currently selected session duration (seconds) for Min Breath. */
    val minBreathSessionDurationSec: Int = 300,
    /** Best hold duration for the current session duration + current settings. */
    val minBreathBestMs: Long = 0L,
    /** Trophy category for the Min Breath best record. */
    val minBreathTrophyCategory: PersonalBestCategory? = null,
    val selectedLength: TableLength = TableLength.MEDIUM,
    val selectedDifficulty: TableDifficulty = TableDifficulty.MEDIUM,
    // Contraction tracking
    val contractionTimestamps: List<Long> = emptyList(),
    val contractionCount: Int = 0,
    val firstContractionElapsedMs: Long? = null,
    val currentRoundStartMs: Long = 0L,
    val lastHoldDurationMs: Long = 0L,
    /** Whether the "First Contraction" button has been tapped this round. */
    val firstContractionTappedThisRound: Boolean = false,
    /** Per-round first contraction data: Map<roundNumber, elapsedMs>. */
    val roundFirstContractions: Map<Int, Long> = emptyMap(),
    // Live oximeter readings (null when oximeter not connected / no data yet)
    val liveOxHr: Int? = null,
    val liveOxSpO2: Int? = null,
    // ── UI layout state ───────────────────────────────────────────────────────
    /** Settings panel is independently collapsible (not part of the accordion). */
    val settingsExpanded: Boolean = true,
    /**
     * The one accordion section that is currently open.
     * BEST_TIME is open by default; null means all accordion sections are collapsed.
     */
    val openSection: ApneaSection? = ApneaSection.BEST_TIME,
    // ── New personal best dialog ──────────────────────────────────────────────
    /** Non-null when a new personal best was just set — contains the broadest beaten category. */
    val newPersonalBest: PersonalBestResult? = null,
    // ── Inline advanced-modality session state ────────────────────────────────
    /** Which modality (if any) has an active inline session running. */
    val activeModalitySession: TrainingModality? = null,
    /** Live state from the AdvancedApneaStateMachine for the currently running inline session. */
    val advancedSessionState: AdvancedApneaState = AdvancedApneaState(),
    // ── Stats ─────────────────────────────────────────────────────────────────
    /** Stats filtered by the current settings (lungVolume + prepType + timeOfDay). */
    val filteredStats: ApneaStats = ApneaStats(),
    /** Stats aggregated across ALL settings combinations. */
    val allStats: ApneaStats = ApneaStats(),
    /**
     * When true the stats section ignores the global settings and shows [allStats].
     * When false it shows [filteredStats].
     */
    val showAllStats: Boolean = false,
    // Live sensor readings for top bar
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    // ── Free-hold active screen ───────────────────────────────────────────────
    /**
     * Elapsed ms from hold start to the first contraction tap on the active-hold screen.
     * Null until the user taps "First Contraction" (or if they never tap it).
     */
    val freeHoldFirstContractionMs: Long? = null,
    /** The song currently playing in Spotify (null when SILENCE or Spotify not active). */
    val nowPlayingSong: TrackInfo? = null,
    // ── Song picker (for table / advanced sessions) ───────────────────────────
    /** True when the user's Spotify account is connected. */
    val spotifyConnected: Boolean = false,
    /** Songs previously played during any apnea session (loaded from DB + prefs). */
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    /** True while previous songs are being loaded. */
    val loadingSongs: Boolean = false,
    /** The song the user selected from the picker for the next session. */
    val selectedSong: SpotifyTrackDetail? = null,
    /** True while a selected song is being loaded into Spotify playback. */
    val loadingSelectedSong: Boolean = false,
    /** True when audio setting is GUIDED — controls whether the guided audio picker is shown. */
    val isGuidedMode: Boolean = false,
    /** All guided audios in the library (for the picker dialog). */
    val guidedAudios: List<GuidedAudioEntity> = emptyList(),
    /** ID of the currently selected guided audio (-1 if none). */
    val guidedSelectedId: Long = -1L,
    /** Display name of the currently selected guided audio file. */
    val guidedSelectedName: String = "",
    /** Guided audio completion status keyed by audioId. */
    val guidedCompletionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    // ── Record-breaking forecast ──────────────────────────────────────────────
    /** Forecast for the current settings combination. Null when insufficient data. */
    val recordForecast: RecordForecast? = null,
)

@HiltViewModel
class ApneaViewModel @Inject constructor(
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository,
    private val tableGenerator: ApneaTableGenerator,
    private val stateMachine: ApneaStateMachine,
    private val advancedStateMachine: AdvancedApneaStateMachine,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    private val forecastCalibrationDao: ForecastCalibrationDao,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApneaUiState())

    /**
     * One-shot event fired when the user taps "Start Hold" on the Best Time card.
     * The nav graph observes this and navigates to the FreeHoldActiveScreen.
     */
    private val _navigateToFreeHoldActive = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToFreeHoldActive: SharedFlow<Unit> = _navigateToFreeHoldActive.asSharedFlow()

    val uiState: StateFlow<ApneaUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        spotifyAuthManager.isConnected
    ) { state, hr, spo2, connected ->
        state.copy(liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ApneaUiState()
    )

    private var freeHoldStartTime = 0L
    private var tableSessionStartTime = 0L

    /**
     * Timestamped oximeter readings collected while a free hold is active.
     * Each entry is (epochMs, OximeterReading). Cleared at the start of each hold.
     */
    private val oximeterSamples = mutableListOf<Pair<Long, OximeterReading>>()
    private var oximeterCollectionJob: Job? = null
    private var advancedSessionStartMs: Long = 0L
    /**
     * Captured at hold-start: true when the oximeter is the primary device
     * (no Polar connected). When false, any oximeter readings that arrive
     * from a background-connected oximeter are discarded at save time so
     * the record's SpO₂ fields stay null / N/A.
     */
    private var oximeterIsPrimary = false

    // Separate flows for the five settings that drive the best-time / filtered-records queries
    private val _lungVolume  = MutableStateFlow("FULL")
    private val _prepType    = MutableStateFlow(PrepType.NO_PREP)
    private val _timeOfDay   = MutableStateFlow(TimeOfDay.fromCurrentTime())
    private val _posture     = MutableStateFlow(Posture.LAYING)
    private val _audio       = MutableStateFlow(AudioSetting.SILENCE)

    // Drill-specific param flows — drive trophy queries for Progressive O₂ and Min Breath
    private val _progO2BreathPeriodSec = MutableStateFlow(60)
    private val _minBreathSessionDurationSec = MutableStateFlow(300)

    init {
        // ── Restore persisted settings (except Time of Day which is always smart-set) ──
        val savedPb = prefs.getLong("pb_ms", 0L)
        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType = runCatching {
            PrepType.valueOf(prefs.getString("setting_prep_type", PrepType.NO_PREP.name) ?: PrepType.NO_PREP.name)
        }.getOrDefault(PrepType.NO_PREP)
        val savedPosture = runCatching {
            Posture.valueOf(prefs.getString("setting_posture", Posture.LAYING.name) ?: Posture.LAYING.name)
        }.getOrDefault(Posture.LAYING)
        val savedAudio = runCatching {
            AudioSetting.valueOf(prefs.getString("setting_audio", AudioSetting.SILENCE.name) ?: AudioSetting.SILENCE.name)
        }.getOrDefault(AudioSetting.SILENCE)
        val savedShowTimer = prefs.getBoolean("setting_show_timer", true)
        val savedLength = runCatching {
            TableLength.valueOf(prefs.getString("setting_length", TableLength.MEDIUM.name) ?: TableLength.MEDIUM.name)
        }.getOrDefault(TableLength.MEDIUM)
        val savedDifficulty = runCatching {
            TableDifficulty.valueOf(prefs.getString("setting_difficulty", TableDifficulty.MEDIUM.name) ?: TableDifficulty.MEDIUM.name)
        }.getOrDefault(TableDifficulty.MEDIUM)

        // Apply restored settings to flows and UI state
        _lungVolume.value = savedLungVolume
        _prepType.value = savedPrepType
        _posture.value = savedPosture
        _audio.value = savedAudio

        // Restore drill-specific param values
        val savedBreathPeriod = prefs.getInt("prog_o2_breath_period_sec", 60)
        val savedSessionDuration = prefs.getInt("min_breath_session_duration_sec", 300)
        _progO2BreathPeriodSec.value = savedBreathPeriod
        _minBreathSessionDurationSec.value = savedSessionDuration

        _uiState.update {
            it.copy(
                personalBestMs = if (savedPb > 0L) savedPb else it.personalBestMs,
                selectedLungVolume = savedLungVolume,
                prepType = savedPrepType,
                posture = savedPosture,
                audio = savedAudio,
                showTimer = savedShowTimer,
                selectedLength = savedLength,
                selectedDifficulty = savedDifficulty,
                progO2BreathPeriodSec = savedBreathPeriod,
                minBreathSessionDurationSec = savedSessionDuration,
                voiceEnabled = audioHapticEngine.voiceEnabled,
                vibrationEnabled = audioHapticEngine.vibrationEnabled,
                isGuidedMode = savedAudio == AudioSetting.GUIDED,
                guidedSelectedId = guidedAudioManager.selectedId
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

        // Mirror live oximeter readings into UI state so the screen can show them
        viewModelScope.launch {
            deviceManager.genericBleManager.liveHr.collect { hr ->
                _uiState.update { it.copy(liveOxHr = hr) }
            }
        }
        viewModelScope.launch {
            deviceManager.genericBleManager.liveSpO2.collect { spo2 ->
                _uiState.update { it.copy(liveOxSpO2 = spo2) }
            }
        }

        viewModelScope.launch {
            stateMachine.state.collect { state ->
                _uiState.update { it.copy(apneaState = state) }
                onStateChanged(state)
            }
        }
        viewModelScope.launch {
            stateMachine.currentRound.collect { round ->
                _uiState.update { it.copy(currentRound = round) }
            }
        }
        viewModelScope.launch {
            stateMachine.remainingSeconds.collect { secs ->
                _uiState.update { it.copy(remainingSeconds = secs) }
            }
        }

        // Mirror advanced state machine into UI state
        viewModelScope.launch {
            advancedStateMachine.state.collect { advState ->
                _uiState.update { it.copy(advancedSessionState = advState) }
                if (advState.phase == AdvancedApneaPhase.COMPLETE) {
                    saveAdvancedSession(advState)
                }
            }
        }

        // Whenever any setting changes, re-subscribe to best-time query
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud -> arrayOf(lv, pt, tod, pos, aud) }
                .collectLatest { arr ->
                    val lv = arr[0] as String; val pt = arr[1] as PrepType; val tod = arr[2] as TimeOfDay
                    val pos = arr[3] as Posture; val aud = arr[4] as AudioSetting
                    apneaRepository.getBestFreeHold(lv, pt.name, tod.name, pos.name, aud.name).collect { best ->
                        val bestMs = best ?: 0L
                        _uiState.update { it.copy(bestTimeForSettingsMs = bestMs) }
                        // Auto-set PB from best free hold when no PB has been set yet
                        if (bestMs > 0L && _uiState.value.personalBestMs <= 0L) {
                            setPersonalBest(bestMs)
                        }
                    }
                }
        }
        // Whenever any setting changes, re-subscribe to last free-hold query
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud -> arrayOf(lv, pt, tod, pos, aud) }
                .collectLatest { arr ->
                    val lv = arr[0] as String; val pt = arr[1] as PrepType; val tod = arr[2] as TimeOfDay
                    val pos = arr[3] as Posture; val aud = arr[4] as AudioSetting
                    apneaRepository.getLastFreeHold(lv, pt.name, tod.name, pos.name, aud.name).collect { last ->
                        _uiState.update { it.copy(lastFreeHoldForSettingsMs = last ?: 0L) }
                    }
                }
        }
        // Whenever any setting changes, re-subscribe to last free-hold record-id query
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud -> arrayOf(lv, pt, tod, pos, aud) }
                .collectLatest { arr ->
                    val lv = arr[0] as String; val pt = arr[1] as PrepType; val tod = arr[2] as TimeOfDay
                    val pos = arr[3] as Posture; val aud = arr[4] as AudioSetting
                    apneaRepository.getLastFreeHoldRecordId(lv, pt.name, tod.name, pos.name, aud.name).collect { id ->
                        _uiState.update { it.copy(lastFreeHoldForSettingsRecordId = id) }
                    }
                }
        }
        // Whenever any setting changes, re-subscribe to best-time record-id query
        // and recompute the trophy level for the best record.
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud -> arrayOf(lv, pt, tod, pos, aud) }
                .collectLatest { arr ->
                    val lv = arr[0] as String; val pt = arr[1] as PrepType; val tod = arr[2] as TimeOfDay
                    val pos = arr[3] as Posture; val aud = arr[4] as AudioSetting
                    apneaRepository.getBestFreeHoldRecordId(lv, pt.name, tod.name, pos.name, aud.name).collect { id ->
                        _uiState.update { it.copy(bestTimeForSettingsRecordId = id) }
                        // Compute trophy level for the best record
                        val trophyCategory = if (id != null) {
                            apneaRepository.getBestRecordTrophyLevel(lv, pt.name, tod.name, pos.name, aud.name)
                        } else null
                        _uiState.update { it.copy(bestTimeTrophyCategory = trophyCategory) }
                    }
                }
        }
        // Recent records: 10 most recent across ALL event types, filtered by current settings
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud -> arrayOf(lv, pt, tod, pos, aud) }
                .collectLatest { arr ->
                    val lv = arr[0] as String; val pt = arr[1] as PrepType; val tod = arr[2] as TimeOfDay
                    val pos = arr[3] as Posture; val aud = arr[4] as AudioSetting
                    apneaRepository.getRecentBySettings(lv, pt.name, tod.name, pos.name, aud.name, limit = 10).collect { records ->
                        _uiState.update { it.copy(recentRecords = records) }
                    }
                }
        }
        // Whenever any setting changes, re-subscribe to filtered stats
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud -> arrayOf(lv, pt, tod, pos, aud) }
                .collectLatest { arr ->
                    val lv = arr[0] as String; val pt = arr[1] as PrepType; val tod = arr[2] as TimeOfDay
                    val pos = arr[3] as Posture; val aud = arr[4] as AudioSetting
                    apneaRepository.getStats(lv, pt.name, tod.name, pos.name, aud.name).collect { stats ->
                        _uiState.update { it.copy(filteredStats = stats) }
                    }
                }
        }
        // Mirror Spotify now-playing into UI state
        viewModelScope.launch {
            spotifyManager.currentSong.collect { track ->
                _uiState.update { it.copy(nowPlayingSong = track) }
            }
        }
        // All-settings stats (independent of settings changes)
        viewModelScope.launch {
            apneaRepository.getStatsAll().collect { stats ->
                _uiState.update { it.copy(allStats = stats) }
            }
        }
        // ── Progressive O₂ best + trophy (for current breath period + current settings) ──
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio, _progO2BreathPeriodSec) { args ->
                args
            }.collectLatest { arr ->
                    val lv = arr[0] as String; val pt = (arr[1] as PrepType).name
                    val tod = (arr[2] as TimeOfDay).name; val pos = (arr[3] as Posture).name
                    val aud = (arr[4] as AudioSetting).name; val bp = arr[5] as Int
                    val drill = DrillContext.progressiveO2(bp)
                    val result = apneaRepository.getDrillBestAndTrophy(drill, lv, pt, tod, pos, aud)
                    _uiState.update {
                        it.copy(
                            progO2BestMs = result?.first ?: 0L,
                            progO2TrophyCategory = result?.second,
                            progO2BreathPeriodSec = bp
                        )
                    }
                }
        }
        // ── Min Breath best + trophy (for current session duration + current settings) ──
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio, _minBreathSessionDurationSec) { args ->
                args
            }.collectLatest { arr ->
                    val lv = arr[0] as String; val pt = (arr[1] as PrepType).name
                    val tod = (arr[2] as TimeOfDay).name; val pos = (arr[3] as Posture).name
                    val aud = (arr[4] as AudioSetting).name; val sd = arr[5] as Int
                    val drill = DrillContext.minBreath(sd)
                    val result = apneaRepository.getDrillBestAndTrophy(drill, lv, pt, tod, pos, aud)
                    _uiState.update {
                        it.copy(
                            minBreathBestMs = result?.first ?: 0L,
                            minBreathTrophyCategory = result?.second,
                            minBreathSessionDurationSec = sd
                        )
                    }
                }
        }

        // ── Record-breaking forecast: recompute with 150 ms debounce when settings change ──
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud ->
                ForecastSettings(lv, pt.name, tod.name, pos.name, aud.name)
            }.collectLatest { settings ->
                delay(150) // debounce
                try {
                    val records = apneaRepository.getAllFreeHoldsOnce()
                    val forecast = RecordForecastCalculator.compute(
                        records = records,
                        settings = settings,
                        nowEpochMs = System.currentTimeMillis()
                    )
                    _uiState.update { it.copy(recordForecast = if (forecast.status == ForecastStatus.Ready) forecast else null) }
                } catch (e: Exception) {
                    Log.w("ApneaVM", "Forecast computation failed", e)
                }
            }
        }
    }

    /** ID of the most recent forecast calibration row (for updating after hold completes). */
    private var pendingForecastCalibrationId: Long? = null

    private fun onStateChanged(state: ApneaState) {
        when (state) {
            ApneaState.APNEA -> {
                audioHapticEngine.announceHoldBegin()
                onApneaPhaseStarted()
            }
            ApneaState.VENTILATION -> {
                // Single longer vibration: signals the user to stop holding
                audioHapticEngine.vibrateHoldEnd()
                audioHapticEngine.announceBreath()
                // Capture hold duration for the contraction summary card
                val table = _uiState.value.currentTable
                // After apnea completes, currentRound has already advanced for the next round,
                // so the just-finished round is currentRound - 1 (or currentRound if not yet incremented)
                val justFinishedRound = _uiState.value.currentRound - 1
                val holdMs = table?.steps?.getOrNull(justFinishedRound.coerceAtLeast(0))?.apneaDurationMs ?: 0L
                _uiState.update { it.copy(lastHoldDurationMs = holdMs) }
            }
            ApneaState.COMPLETE -> {
                audioHapticEngine.announceSessionComplete()
                if (!tableSessionCancelled) {
                    saveCompletedSession()
                    // Signal the Habit app that a full O2/CO2 table session was completed
                    habitRepo.sendHabitIncrement(Slot.TABLE_TRAINING)
                }
                tableSessionCancelled = false
            }
            else -> Unit
        }
    }

    private fun saveCompletedSession() {
        // Stop collecting oximeter readings before saving so the snapshot is stable
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null

        val oxSnapshot = if (oximeterIsPrimary) oximeterSamples.toList() else emptyList()
        oximeterSamples.clear()
        val deviceLabel = hrDataSource.activeHrDeviceLabel()

        viewModelScope.launch {
            val state = _uiState.value
            val tableType = state.currentTable?.type?.name ?: "UNKNOWN"
            val variantStr = "${state.selectedLength.name}_${state.selectedDifficulty.name}"
            val now = System.currentTimeMillis()
            val table = state.currentTable ?: return@launch

            // ── HR / SpO₂ aggregates (same logic as free hold) ──────────────
            val rrSnapshot = if (!oximeterIsPrimary) deviceManager.rrBuffer.readLast(512) else emptyList()
            val rrHrValues = rrSnapshot.map { 60_000.0 / it }
            val minHrFromRr = rrHrValues.minOrNull()?.toFloat() ?: 0f
            val maxHrFromRr = rrHrValues.maxOrNull()?.toFloat() ?: 0f

            val oxHrValues = oxSnapshot.map { it.second.heartRateBpm.toFloat() }
            val oxSpO2Values = oxSnapshot.map { it.second.spO2.toFloat() }
            val maxHrFromOx = oxHrValues.maxOrNull() ?: 0f
            val lowestSpO2 = oxSpO2Values.minOrNull()?.toInt()

            val minHr = if (minHrFromRr > 0f) minHrFromRr else oxHrValues.minOrNull() ?: 0f
            val maxHr = if (maxHrFromRr > 0f) maxHrFromRr else maxHrFromOx

            // Build per-round first contraction JSON: {"1":12345,"3":23456}
            val roundFcJson = if (state.roundFirstContractions.isNotEmpty()) {
                state.roundFirstContractions.entries
                    .joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
            } else "{}"

            val totalSessionMs = table.steps.sumOf { it.apneaDurationMs + it.ventilationDurationMs }

            // 1. Save the session entity with per-round contraction data
            val sessionEntity = ApneaSessionEntity(
                timestamp = now,
                tableType = tableType,
                tableVariant = variantStr,
                tableParamsJson = roundFcJson,
                pbAtSessionMs = state.personalBestMs,
                totalSessionDurationMs = totalSessionMs,
                contractionTimestampsJson = "[]",
                maxHrBpm = maxHr.toInt().takeIf { it > 0 },
                lowestSpO2 = lowestSpO2,
                roundsCompleted = state.currentRound,
                totalRounds = state.totalRounds,
                hrDeviceId = deviceLabel
            )
            sessionRepository.saveSession(sessionEntity)

            // 2. Save a SINGLE ApneaRecordEntity for the whole table session
            //    so it appears in All Records, Stats, and Calendar.
            //    Duration = total hold time (sum of all hold durations).
            val totalHoldMs = table.steps.sumOf { it.apneaDurationMs }
            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp = now,
                    durationMs = totalHoldMs,
                    lungVolume = state.selectedLungVolume,
                    prepType = state.prepType.name,
                    minHrBpm = minHr,
                    maxHrBpm = maxHr,
                    tableType = tableType,
                    lowestSpO2 = lowestSpO2,
                    timeOfDay = state.timeOfDay.name,
                    firstContractionMs = null,
                    hrDeviceId = deviceLabel,
                    posture = state.posture.name,
                    audio = state.audio.name,
                    guidedAudioName = if (_audio.value == AudioSetting.GUIDED) _uiState.value.guidedSelectedName else null
                )
            )

            // 3. Save telemetry rows so the detail screen can show HR/SpO₂ charts
            if (recordId > 0) {
                val samples = mutableListOf<FreeHoldTelemetryEntity>()

                // Polar RR → per-beat HR telemetry
                if (rrSnapshot.isNotEmpty()) {
                    var cumulativeMs = 0L
                    for (rrMs in rrSnapshot) {
                        cumulativeMs += rrMs.toLong()
                        val bpm = (60_000.0 / rrMs).toInt()
                        samples.add(
                            FreeHoldTelemetryEntity(
                                recordId = recordId,
                                timestampMs = tableSessionStartTime + cumulativeMs,
                                heartRateBpm = bpm,
                                spO2 = null
                            )
                        )
                    }
                }

                // Oximeter → HR + SpO₂ telemetry
                for ((timestampMs, reading) in oxSnapshot) {
                    if (timestampMs < tableSessionStartTime) continue
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId = recordId,
                            timestampMs = timestampMs,
                            heartRateBpm = reading.heartRateBpm,
                            spO2 = reading.spO2
                        )
                    )
                }

                if (samples.isNotEmpty()) {
                    apneaRepository.saveTelemetry(samples)
                }
            }
        }
    }

    private fun saveAdvancedSession(advState: AdvancedApneaState) {
        viewModelScope.launch {
            val modality = _uiState.value.activeModalitySession ?: return@launch
            val pbMs = prefs.getLong("pb_ms", 0L)
            val totalDurationMs = System.currentTimeMillis() - advancedSessionStartMs
            val entity = ApneaSessionEntity(
                timestamp = System.currentTimeMillis(),
                tableType = modality.name,
                tableVariant = _uiState.value.selectedLength.name,
                tableParamsJson = "{}",
                pbAtSessionMs = pbMs,
                totalSessionDurationMs = totalDurationMs,
                contractionTimestampsJson = "[]",
                maxHrBpm = null,
                lowestSpO2 = null,
                roundsCompleted = advState.currentRound,
                totalRounds = advState.totalRounds,
                hrDeviceId = hrDataSource.activeHrDeviceLabel()
            )
            sessionRepository.saveSession(entity)
        }
    }

    private fun onApneaPhaseStarted() {
        _uiState.update {
            it.copy(
                contractionTimestamps = emptyList(),
                contractionCount = 0,
                firstContractionElapsedMs = null,
                firstContractionTappedThisRound = false,
                currentRoundStartMs = System.currentTimeMillis()
            )
        }
    }

    fun logContraction() {
        val now = System.currentTimeMillis()
        val elapsed = now - _uiState.value.currentRoundStartMs
        val isFirst = _uiState.value.contractionTimestamps.isEmpty()
        _uiState.update { state ->
            state.copy(
                contractionTimestamps = state.contractionTimestamps + now,
                contractionCount = state.contractionCount + 1,
                firstContractionElapsedMs = if (isFirst) elapsed else state.firstContractionElapsedMs
            )
        }
        audioHapticEngine.vibrateContractionLogged()
    }

    /** Called when the user taps the "First Contraction" button during a hold. */
    fun logFirstContraction() {
        val now = System.currentTimeMillis()
        val elapsed = now - _uiState.value.currentRoundStartMs
        val round = _uiState.value.currentRound
        _uiState.update { state ->
            state.copy(
                firstContractionTappedThisRound = true,
                firstContractionElapsedMs = state.firstContractionElapsedMs ?: elapsed,
                roundFirstContractions = state.roundFirstContractions + (round to elapsed)
            )
        }
        audioHapticEngine.vibrateContractionLogged()
    }

    /** Update a specific step's hold or breath time in the current table. */
    fun updateTableStep(roundNumber: Int, newHoldMs: Long? = null, newBreathMs: Long? = null) {
        val table = _uiState.value.currentTable ?: return
        val updatedSteps = table.steps.map { step ->
            if (step.roundNumber == roundNumber) {
                step.copy(
                    apneaDurationMs = newHoldMs ?: step.apneaDurationMs,
                    ventilationDurationMs = newBreathMs ?: step.ventilationDurationMs
                )
            } else step
        }
        val updatedTable = table.copy(steps = updatedSteps)
        stateMachine.load(updatedTable)
        _uiState.update { it.copy(currentTable = updatedTable) }
    }

    fun setPersonalBest(pbMs: Long) {
        val current = _uiState.value.personalBestMs
        _uiState.update {
            it.copy(personalBestMs = pbMs)
        }
        prefs.edit().putLong("pb_ms", pbMs).apply()
    }

    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    fun loadTable(type: ApneaTableType) {
        val pb = _uiState.value.personalBestMs
        if (pb <= 0) return
        if (type == ApneaTableType.FREE) return
        val length = _uiState.value.selectedLength
        val difficulty = _uiState.value.selectedDifficulty
        val table = when (type) {
            ApneaTableType.O2  -> tableGenerator.generateO2Table(pb, length, difficulty)
            ApneaTableType.CO2 -> tableGenerator.generateCo2Table(pb, length, difficulty)
            ApneaTableType.FREE -> return
        }
        stateMachine.load(table)
        _uiState.update {
            it.copy(
                currentTable = table,
                totalRounds = table.steps.size
            )
        }
    }

    fun startTableSession() {
        tableSessionCancelled = false
        val polarDeviceId = hrDataSource.connectedPolarDeviceId()
        if (polarDeviceId != null) {
            deviceManager.startRrStream(polarDeviceId)
        }
        tableSessionStartTime = System.currentTimeMillis()
        oximeterIsPrimary = hrDataSource.isOximeterPrimaryDevice()
        oximeterSamples.clear()
        oximeterCollectionJob?.cancel()
        if (oximeterIsPrimary) {
            oximeterCollectionJob = viewModelScope.launch {
                deviceManager.oximeterReadings.collect { reading ->
                    oximeterSamples.add(System.currentTimeMillis() to reading)
                }
            }
        }
        stateMachine.setCallbacks(
            onWarning = { secs -> onWarning(secs) },
            onStateChange = { /* state collected via flow in init */ }
        )
        stateMachine.start(viewModelScope)
        // Start Spotify if MUSIC is selected.
        // Song was pre-loaded in selectSong() — just resume playback.
        if (_audio.value == AudioSetting.MUSIC) {
            spotifyManager.startTracking()
            spotifyManager.sendPlayCommand()
        }
        // Start guided audio if GUIDED is selected
        if (_audio.value == AudioSetting.GUIDED) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }
    }

    /** Flag to prevent auto-save when the user cancels a table session via back arrow. */
    private var tableSessionCancelled = false

    fun stopTableSession() {
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
        val tracksPlayed = if (_audio.value == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        // Stop guided audio if GUIDED is selected
        if (_audio.value == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        stateMachine.stop()
        if (tracksPlayed.isNotEmpty()) {
            persistSongHistory(tracksPlayed.map { SpotifySong(it.title, it.artist, null, it.spotifyUri, it.startedAtMs, it.endedAtMs) })
        }
    }

    /**
     * Cancels an in-progress table session without saving any record.
     * Called when the user taps the back arrow while the session is running.
     */
    fun cancelTableSession() {
        tableSessionCancelled = true
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
        // Stop Spotify if MUSIC was selected (no tracking save since we're cancelling)
        if (_audio.value == AudioSetting.MUSIC) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }
        // Stop guided audio if GUIDED was selected
        if (_audio.value == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        stateMachine.stop()
        // Do NOT save the session or fire tail increments
    }

    private fun onWarning(remainingSeconds: Long) {
        audioHapticEngine.announceTimeRemaining(remainingSeconds.toInt())
        // During the VENTILATION (breathing) phase, fire a single tick vibration every
        // second for the last 10 seconds — warns the user the next hold is approaching.
        // Read directly from the StateFlow (always current) to avoid race with _uiState collector.
        if (stateMachine.state.value == ApneaState.VENTILATION && remainingSeconds in 1L..10L) {
            val isLast = remainingSeconds == 1L
            audioHapticEngine.vibrateBreathingCountdownTick(isLastTick = isLast)
        }
    }

    // ── Free Hold ────────────────────────────────────────────────────────────

    /** Called from the Best Time card — fires navigation event; actual hold starts on the new screen. */
    fun requestStartFreeHold() {
        _navigateToFreeHoldActive.tryEmit(Unit)
    }

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
        _uiState.update {
            it.copy(
                freeHoldActive = true,
                freeHoldDurationMs = 0L,
                freeHoldFirstContractionMs = null
            )
        }

        // Start collecting oximeter readings for this hold — only when the
        // oximeter is the primary device. When a Polar device is connected,
        // background oximeter readings are incidental resting values.
        oximeterSamples.clear()
        oximeterCollectionJob?.cancel()
        if (oximeterIsPrimary) {
            oximeterCollectionJob = viewModelScope.launch {
                deviceManager.oximeterReadings.collect { reading ->
                    oximeterSamples.add(System.currentTimeMillis() to reading)
                }
            }
        }

        // If MUSIC is selected, start Spotify and begin song tracking
        if (_audio.value == AudioSetting.MUSIC) {
            spotifyManager.sendPlayCommand()
            spotifyManager.startTracking()
        }
        // Start guided audio if GUIDED is selected
        if (_audio.value == AudioSetting.GUIDED) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }

        // ── Log forecast calibration (predictions before hold) ────────────────
        val forecast = _uiState.value.recordForecast
        if (forecast != null) {
            val state = _uiState.value
            val predictions = forecast.categories.joinToString(",") { c ->
                "${c.category.name}=${"%.4f".format(c.probability)}"
            }
            viewModelScope.launch {
                val id = forecastCalibrationDao.insert(
                    ForecastCalibrationEntity(
                        timestamp = System.currentTimeMillis(),
                        lungVolume = state.selectedLungVolume,
                        prepType = state.prepType.name,
                        timeOfDay = state.timeOfDay.name,
                        posture = state.posture.name,
                        audio = state.audio.name,
                        totalFreeHolds = forecast.totalFreeHolds,
                        confidence = forecast.confidence.name,
                        predictions = predictions
                    )
                )
                pendingForecastCalibrationId = id
            }
        }
    }

    /**
     * Cancels an in-progress free hold without saving any record.
     * Called when the user taps the back arrow while the hold is running.
     */
    fun cancelFreeHold() {
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
        // Pause Spotify and rewind to start of song if MUSIC was selected
        if (_audio.value == AudioSetting.MUSIC) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }
        // Stop guided audio if GUIDED was selected
        if (_audio.value == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        _uiState.update {
            it.copy(
                freeHoldActive = false,
                freeHoldDurationMs = it.freeHoldDurationMs, // keep last completed hold duration
                freeHoldFirstContractionMs = null
            )
        }
    }

    /** Record the first contraction time during an active free hold. */
    fun recordFreeHoldFirstContraction() {
        if (_uiState.value.freeHoldFirstContractionMs != null) return // already recorded
        val elapsed = System.currentTimeMillis() - freeHoldStartTime
        _uiState.update { it.copy(freeHoldFirstContractionMs = elapsed) }
        audioHapticEngine.vibrateContractionLogged()
    }

    fun stopFreeHold() {
        val duration = System.currentTimeMillis() - freeHoldStartTime
        // Stop collecting oximeter readings before saving so the snapshot is stable
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        val firstContractionMs = _uiState.value.freeHoldFirstContractionMs
        val state = _uiState.value
        _uiState.update {
            it.copy(
                freeHoldActive = false,
                freeHoldDurationMs = duration,
                freeHoldFirstContractionMs = null
            )
        }
        audioHapticEngine.vibrateHoldEnd()
        // Stop Spotify tracking, collect songs, then pause + rewind to start of song
        val tracksPlayed = if (state.audio == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        // Stop guided audio if GUIDED was selected
        if (state.audio == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        // Signal the Habit app that a free breath hold was successfully completed
        habitRepo.sendHabitIncrement(Slot.FREE_HOLD)
        viewModelScope.launch {
            // Check broader PB categories BEFORE saving so queries compare against prior records only.
            val pbResult = apneaRepository.checkBroaderPersonalBest(
                durationMs = duration,
                lungVolume = state.selectedLungVolume,
                prepType   = state.prepType.name,
                timeOfDay  = state.timeOfDay.name,
                posture    = state.posture.name,
                audio      = state.audio.name
            )
            saveFreeHoldRecord(duration, firstContractionMs, tracksPlayed)
            if (pbResult != null) {
                _uiState.update { it.copy(newPersonalBest = pbResult) }
                habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD)
            }

            // ── Update forecast calibration with actual outcome ───────────────
            val calId = pendingForecastCalibrationId
            if (calId != null) {
                pendingForecastCalibrationId = null
                val brokenCategories = mutableListOf<String>()
                val forecast = _uiState.value.recordForecast
                if (forecast != null) {
                    for (cat in forecast.categories) {
                        if (cat.recordMs != null && duration > cat.recordMs) {
                            brokenCategories.add(cat.category.name)
                        } else if (cat.recordMs == null) {
                            // No prior record → any hold breaks it
                            brokenCategories.add(cat.category.name)
                        }
                    }
                }
                val brokenStr = brokenCategories.joinToString(",")
                forecastCalibrationDao.updateActual(calId, duration, brokenStr, System.currentTimeMillis())
            }
        }
    }

    private fun saveFreeHoldRecord(
        durationMs: Long,
        firstContractionMs: Long? = null,
        tracksPlayed: List<TrackInfo> = emptyList()
    ) {
        // Only use oximeter data when the oximeter was the primary device at hold-start.
        // When a Polar device is the primary HR source, any background-connected oximeter
        // readings are incidental resting values (typically 99 %) and must be discarded.
        val oxSnapshot = if (oximeterIsPrimary) oximeterSamples.toList() else emptyList()
        oximeterSamples.clear()
        // Capture device label at the moment the hold ends (before any disconnect)
        val deviceLabel = hrDataSource.activeHrDeviceLabel()

        viewModelScope.launch {
            // ── Polar RR-derived HR samples (only when Polar is the primary device) ──
            val rrSnapshot = if (!oximeterIsPrimary) deviceManager.rrBuffer.readLast(512) else emptyList()
            val rrHrValues = rrSnapshot.map { 60_000.0 / it }
            val minHrFromRr = rrHrValues.minOrNull()?.toFloat() ?: 0f
            val maxHrFromRr = rrHrValues.maxOrNull()?.toFloat() ?: 0f

            // ── Oximeter-derived aggregates ───────────────────────────────────
            val oxHrValues  = oxSnapshot.map { it.second.heartRateBpm.toFloat() }
            val oxSpO2Values = oxSnapshot.map { it.second.spO2.toFloat() }
            val maxHrFromOx  = oxHrValues.maxOrNull() ?: 0f
            val lowestSpO2   = oxSpO2Values.minOrNull()?.toInt()

            // Prefer Polar for HR aggregates; fall back to oximeter
            val minHr = if (minHrFromRr > 0f) minHrFromRr else oxHrValues.minOrNull() ?: 0f
            val maxHr = if (maxHrFromRr > 0f) maxHrFromRr else maxHrFromOx

            val state = _uiState.value
            val now = System.currentTimeMillis()

            // ── Save summary record ───────────────────────────────────────────
            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp = now,
                    durationMs = durationMs,
                    lungVolume = state.selectedLungVolume,
                    prepType = state.prepType.name,
                    timeOfDay = state.timeOfDay.name,
                    posture = state.posture.name,
                    audio = state.audio.name,
                    minHrBpm = minHr,
                    maxHrBpm = maxHr,
                    lowestSpO2 = lowestSpO2,
                    tableType = null,
                    firstContractionMs = firstContractionMs,
                    hrDeviceId = deviceLabel,
                    guidedAudioName = if (_audio.value == AudioSetting.GUIDED) _uiState.value.guidedSelectedName else null
                )
            )

            if (recordId <= 0) return@launch

            val samples = mutableListOf<FreeHoldTelemetryEntity>()

            // ── Polar RR → per-beat HR telemetry ─────────────────────────────
            if (rrSnapshot.isNotEmpty()) {
                var cumulativeMs = 0L
                for (rrMs in rrSnapshot) {
                    cumulativeMs += rrMs.toLong()
                    if (cumulativeMs > durationMs) break
                    val bpm = (60_000.0 / rrMs).toInt()
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId = recordId,
                            timestampMs = freeHoldStartTime + cumulativeMs,
                            heartRateBpm = bpm,
                            spO2 = null
                        )
                    )
                }
            }

            // ── Oximeter → HR + SpO2 telemetry ───────────────────────────────
            for ((timestampMs, reading) in oxSnapshot) {
                if (timestampMs < freeHoldStartTime) continue
                if (timestampMs > freeHoldStartTime + durationMs) continue
                samples.add(
                    FreeHoldTelemetryEntity(
                        recordId = recordId,
                        timestampMs = timestampMs,
                        heartRateBpm = reading.heartRateBpm,
                        spO2 = reading.spO2
                    )
                )
            }

            if (samples.isNotEmpty()) {
                apneaRepository.saveTelemetry(samples)
            }

            // ── Save Spotify song log ─────────────────────────────────────────
            if (tracksPlayed.isNotEmpty()) {
                val songs = tracksPlayed.map { track ->
                    SpotifySong(
                        title        = track.title,
                        artist       = track.artist,
                        spotifyUri   = track.spotifyUri,
                        startedAtMs  = track.startedAtMs,
                        endedAtMs    = track.endedAtMs
                    )
                }
                apneaRepository.saveSongLog(recordId, songs)
            }
        }
    }

    // ── Auto-set best settings ─────────────────────────────────────────────────
    /** Cached list of best settings combos, sorted by probability descending. */
    private var bestSettingsList: List<com.example.wags.domain.usecase.apnea.forecast.SettingsWithProbability> = emptyList()
    /** Index into the top-probability group for cycling through ties. */
    private var autoSetCycleIndex: Int = 0

    /**
     * Auto-set the 4 changeable settings (lung volume, prep type, posture, audio)
     * to the combination with the highest predicted record-breaking probability.
     * Time-of-day is NOT changed. Repeated calls cycle through tied combinations.
     */
    fun autoSetBestSettings() {
        viewModelScope.launch {
            val records = apneaRepository.getAllFreeHoldsOnce()
            val tod = _timeOfDay.value.name
            val best = RecordForecastCalculator.computeBestSettings(records, tod, System.currentTimeMillis())
            if (best.isEmpty()) return@launch

            val topProb = best.first().probability
            val topGroup = best.filter { it.probability == topProb }

            // If we don't have a cached list or the list changed, reset
            if (bestSettingsList != topGroup) {
                bestSettingsList = topGroup
                autoSetCycleIndex = 0
            }

            // Pick the next one in the cycle
            val chosen = bestSettingsList[autoSetCycleIndex % bestSettingsList.size]
            autoSetCycleIndex = (autoSetCycleIndex + 1) % bestSettingsList.size

            // Apply the settings
            setLungVolume(chosen.settings.lungVolume)
            setPrepType(PrepType.valueOf(chosen.settings.prepType))
            setPosture(Posture.valueOf(chosen.settings.posture))
            setAudio(AudioSetting.valueOf(chosen.settings.audio))
        }
    }

    fun setLungVolume(volume: String) {
        _lungVolume.value = volume
        _uiState.update { it.copy(selectedLungVolume = volume) }
        prefs.edit().putString("setting_lung_volume", volume).apply()
    }

    fun setPrepType(type: PrepType) {
        _prepType.value = type
        _uiState.update { it.copy(prepType = type) }
        prefs.edit().putString("setting_prep_type", type.name).apply()
    }

    fun setTimeOfDay(tod: TimeOfDay) {
        _timeOfDay.value = tod
        _uiState.update { it.copy(timeOfDay = tod) }
        // Time of Day is intentionally NOT persisted — always smart-set from current time on launch.
    }

    fun setPosture(posture: Posture) {
        _posture.value = posture
        _uiState.update { it.copy(posture = posture) }
        prefs.edit().putString("setting_posture", posture.name).apply()
    }

    fun setAudio(audio: AudioSetting) {
        _audio.value = audio
        prefs.edit().putString("setting_audio", audio.name).apply()
        val isGuided = audio == AudioSetting.GUIDED
        _uiState.update {
            it.copy(
                audio = audio,
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
    }

    // ── Guided audio library methods ─────────────────────────────────────────

    fun loadGuidedAudios() {
        // Already loading via Flow in init — this is a no-op
    }

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
            val state = _uiState.value
            val map = mutableMapOf<Long, GuidedCompletionStatus>()
            for (audio in audios) {
                val ever = apneaRepository.wasGuidedAudioUsedEver(audio.fileName)
                val withSettings = apneaRepository.wasGuidedAudioUsedWithSettings(
                    audio.fileName,
                    state.selectedLungVolume,
                    state.prepType.name,
                    state.timeOfDay.name,
                    state.posture.name,
                    state.audio.name
                )
                map[audio.audioId] = GuidedCompletionStatus(
                    completedEver = ever,
                    completedWithCurrentSettings = withSettings
                )
            }
            _uiState.update { it.copy(guidedCompletionStatuses = map) }
        }
    }

    fun setShowTimer(show: Boolean) {
        _uiState.update { it.copy(showTimer = show) }
        prefs.edit().putBoolean("setting_show_timer", show).apply()
    }

    fun setLength(length: TableLength) {
        _uiState.update { it.copy(selectedLength = length) }
        prefs.edit().putString("setting_length", length.name).apply()
    }

    fun setDifficulty(difficulty: TableDifficulty) {
        _uiState.update { it.copy(selectedDifficulty = difficulty) }
        prefs.edit().putString("setting_difficulty", difficulty.name).apply()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        audioHapticEngine.voiceEnabled = enabled
        _uiState.update { it.copy(voiceEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        audioHapticEngine.vibrationEnabled = enabled
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    // ── Layout / accordion ────────────────────────────────────────────────────

    fun toggleSettings() {
        _uiState.update { it.copy(settingsExpanded = !it.settingsExpanded) }
    }

    fun toggleShowAllStats() {
        _uiState.update { it.copy(showAllStats = !it.showAllStats) }
    }

    /**
     * Re-reads drill-specific param values from SharedPreferences.
     * Call this when the ApneaScreen resumes (e.g. after navigating back from
     * the Progressive O₂ or Min Breath setup screens where the user may have
     * changed the breath period or session duration).
     */
    fun refreshDrillParams() {
        val bp = prefs.getInt("prog_o2_breath_period_sec", 60)
        val sd = prefs.getInt("min_breath_session_duration_sec", 300)
        _progO2BreathPeriodSec.value = bp
        _minBreathSessionDurationSec.value = sd
    }

    /**
     * Opens [section] if it is currently closed; closes it if it is already open.
     * Only one accordion section can be open at a time (settings is independent).
     */
    fun toggleSection(section: ApneaSection) {
        _uiState.update { state ->
            val newOpen = if (state.openSection == section) null else section
            state.copy(openSection = newOpen)
        }
    }

    // ── Inline advanced-modality sessions ────────────────────────────────────

    fun startAdvancedSession(modality: TrainingModality, wonkaConfig: WonkaConfig = WonkaConfig()) {
        val pbMs = _uiState.value.personalBestMs
        val length = _uiState.value.selectedLength
        advancedSessionStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(activeModalitySession = modality) }
        advancedStateMachine.start(modality, length, pbMs, wonkaConfig, viewModelScope)
        // Start Spotify if MUSIC is selected.
        // Song was pre-loaded in selectSong() — just resume playback.
        if (_audio.value == AudioSetting.MUSIC) {
            spotifyManager.startTracking()
            spotifyManager.sendPlayCommand()
        }
        // Start guided audio if GUIDED is selected
        if (_audio.value == AudioSetting.GUIDED) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }
    }

    fun stopAdvancedSession() {
        val tracksPlayed = if (_audio.value == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        // Stop guided audio if GUIDED was selected
        if (_audio.value == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        advancedStateMachine.stop()
        _uiState.update { it.copy(activeModalitySession = null) }
        // Persist any tracks played to song history
        if (tracksPlayed.isNotEmpty()) {
            persistSongHistory(tracksPlayed.map { SpotifySong(it.title, it.artist, null, it.spotifyUri, it.startedAtMs, it.endedAtMs) })
        }
    }

    fun signalBreathTaken() {
        advancedStateMachine.signalBreathTaken()
        audioHapticEngine.announceHoldBegin()
    }

    fun signalFirstContraction() {
        advancedStateMachine.signalFirstContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    // ── Song picker (for table / advanced sessions on the main screen) ────────

    /**
     * Load distinct songs previously played during any apnea session.
     * Merges DB records with the SharedPreferences song history so songs
     * from table/advanced sessions (which have no DB record) are also shown.
     */
    fun loadPreviousSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSongs = true) }
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
                        spotifyUri = uri,
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                } else {
                    SpotifyTrackDetail(
                        spotifyUri = "",
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                }
            }
            // Final dedup by title+artist after API enrichment
            _uiState.update { it.copy(previousSongs = deduplicateTracks(details), loadingSongs = false) }
        }
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

    /**
     * Persists a list of played songs to SharedPreferences so they survive
     * app restarts and are available for all session types (not just free holds).
     * Stores up to 50 unique songs (by URI or title+artist), most recent first.
     */
    fun persistSongHistory(songs: List<SpotifySong>) {
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
            if (!seenByTitleArtist.add(titleArtistKey)) continue
            if (!song.spotifyUri.isNullOrBlank()) {
                if (!seenByUri.add(song.spotifyUri!!)) continue
            }
            result.add(song)
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
        guidedAudioManager.stopPlayback()
        stateMachine.stop()
        advancedStateMachine.stop()
    }
}
