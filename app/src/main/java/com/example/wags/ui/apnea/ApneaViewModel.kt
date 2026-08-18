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
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.usecase.breathing.EucapnicScalingEngine
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.ApneaState
import com.example.wags.domain.usecase.apnea.ApneaStateMachine
import com.example.wags.domain.usecase.apnea.ApneaTableGenerator
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.domain.usecase.apnea.ResonancePrepGate
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
    CONTRACTION_TABLES,
    RECENT_RECORDS,
    SESSION_ANALYTICS,
    STATS
}

/**
 * Corner-badge info for an accordion section: last-use timestamp (epoch ms)
 * with ANY settings vs. with the EXACT currently selected settings combo.
 * Null timestamps mean the session type has never been done under that constraint.
 */
data class SectionLastUse(
    val anySettingsMs: Long? = null,
    val currentSettingsMs: Long? = null
)

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
    // ── Contraction Tables trophy display ─────────────────────────────────────
    /** Best hold duration for Till Contraction mode + current settings. */
    val contractionTableBestMs: Long = 0L,
    /** Trophy category for the Till Contraction best record. */
    val contractionTableTrophyCategory: PersonalBestCategory? = null,
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
    /** The songs the user selected from the picker for the next session (ordered by selection). */
    val selectedSongs: List<SpotifyTrackDetail> = emptyList(),
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
    /** Forecast for the current settings combination (Free Hold). Null when insufficient data. */
    val recordForecast: RecordForecast? = null,
    /** Forecast for Progressive O₂ with the current settings. Null when insufficient data. */
    val progO2RecordForecast: RecordForecast? = null,
    /** Forecast for Min Breath with the current settings. Null when insufficient data. */
    val minBreathRecordForecast: RecordForecast? = null,
    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────
    /** Current eucapnic configuration (when EUCAPNIC_DIAPHRAGMATIC prep type is selected). */
    val eucapnicConfig: com.example.wags.domain.model.EucapnicConfig? = null,
    /** Whether the Past Configurations dialog is visible. */
    val showPastConfigurationsDialog: Boolean = false,
    // ── Hyper time-lock + per-setting last-used badges ─────────────────────────
    /** Whole days left until HYPER unlocks (0 = unlocked or never used). */
    val hyperRemainingLockDays: Int = 0,
    /** True when no resonance breathing session ended within the last ~5 minutes (RESONANCE prep locked). */
    val resonancePrepLocked: Boolean = false,
    /**
     * Last-use timestamp (epoch ms) per setting column → setting value name.
     * Keys: "lungVolume", "prepType", "timeOfDay", "posture", "audio".
     * Drives the days-since-used badge on each settings chip.
     */
    val lastUsedPerSetting: Map<String, Map<String, Long>> = emptyMap(),
    /**
     * Last-use timestamp (epoch ms) per setting column → setting value name,
     * where the record ALSO matched the currently selected values of ALL other
     * setting categories. Drives the lower-right combo badge on each settings
     * chip (recomputed whenever any selection changes).
     */
    val lastUsedPerCombo: Map<String, Map<String, Long>> = emptyMap(),
    /**
     * Per accordion section: last use with any settings and with the exact
     * currently selected settings combo. Drives the section corner badges.
     */
    val sectionLastUse: Map<ApneaSection, SectionLastUse> = emptyMap(),
)

@HiltViewModel
class ApneaViewModel @Inject constructor(
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository,
    private val tableGenerator: ApneaTableGenerator,
    private val stateMachine: ApneaStateMachine,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    private val forecastCalibrationDao: ForecastCalibrationDao,
    private val hyperLockManager: HyperLockManager,
    private val resonancePrepGate: ResonancePrepGate,
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
    /** Tracks played during the current table session; populated in stopTableSession() and read in saveCompletedSession(). */
    private var tableTracksPlayed: List<TrackInfo> = emptyList()

    /**
     * Timestamped oximeter readings collected while a free hold is active.
     * Each entry is (epochMs, OximeterReading). Cleared at the start of each hold.
     */
    private val oximeterSamples = mutableListOf<Pair<Long, OximeterReading>>()
    private var oximeterCollectionJob: Job? = null
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

    /** Bumped after a free hold record is saved — triggers forecast recompute with fresh data. */
    private val _forecastRefreshTrigger = MutableStateFlow(0)

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
        // ── Hyper time-lock + per-setting last-used badges ─────────────────────
        // (The lock length is configured in app Settings → Apnea; it is re-read
        // from prefs on resume via refreshDrillParams() and on every DB change.)
        viewModelScope.launch {
            apneaRepository.observeLastUsedPerSetting().collect { tuples ->
                val grouped = tuples
                    .groupBy({ it.settingKey }, { it.settingValue to it.lastUsedMs })
                    .mapValues { (_, pairs) -> pairs.toMap() }
                _uiState.update {
                    it.copy(
                        lastUsedPerSetting = grouped,
                        hyperRemainingLockDays = HyperLockManager.remainingLockDays(
                            grouped["prepType"]?.get(PrepType.HYPER.name),
                            hyperLockManager.lockDays,
                            System.currentTimeMillis()
                        )
                    )
                }
                // Auto-deselect HYPER when the lock is engaged (e.g. right after
                // finishing a HYPER session, or when restoring a stale persisted
                // selection on launch).
                if (_prepType.value == PrepType.HYPER && _uiState.value.hyperRemainingLockDays > 0) {
                    setPrepType(PrepType.NO_PREP)
                }
            }
        }
        // ── Resonance prep staleness lock ────────────────────────────────────────
        // RESONANCE prep requires a resonance breathing session that ended within
        // the last ~5 minutes. The ticker re-emits every couple of seconds so the
        // lock engages (and the selection auto-deselects) the moment the window
        // elapses, and clears instantly when a fresh resonance session is saved.
        viewModelScope.launch {
            resonancePrepGate.isLocked.collect { locked ->
                _uiState.update { it.copy(resonancePrepLocked = locked) }
                // Auto-deselect RESONANCE only while idle — never yank the setting
                // mid-session (free hold or table). The rule is that a
                // resonance-prepped activity cannot *start* after the 5-minute
                // window; once it has started with RESONANCE it stays RESONANCE.
                if (locked && _prepType.value == PrepType.RESONANCE &&
                    stateMachine.state.value == ApneaState.IDLE &&
                    !_uiState.value.freeHoldActive
                ) {
                    setPrepType(PrepType.NO_PREP)
                }
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
        // ── Contraction Tables best + trophy (Till Contraction, current settings) ──
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud ->
                arrayOf(lv, pt, tod, pos, aud)
            }.collectLatest { arr ->
                val lv = arr[0] as String; val pt = (arr[1] as PrepType).name
                val tod = (arr[2] as TimeOfDay).name; val pos = (arr[3] as Posture).name
                val aud = (arr[4] as AudioSetting).name
                val drill = DrillContext.CONTRACTION_TILL
                val result = apneaRepository.getDrillBestAndTrophy(drill, lv, pt, tod, pos, aud)
                _uiState.update {
                    it.copy(
                        contractionTableBestMs = result?.first ?: 0L,
                        contractionTableTrophyCategory = result?.second
                    )
                }
            }
        }

        // ── Record-breaking forecast: recompute with 150 ms debounce when settings change ──
        // Also recomputes when _forecastRefreshTrigger is bumped (e.g. after a new record is saved).
        viewModelScope.launch {
            combine(
                combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud ->
                    ForecastSettings(lv, pt.name, tod.name, pos.name, aud.name)
                },
                _forecastRefreshTrigger
            ) { settings, _ -> settings }
            .collectLatest { settings ->
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

        // ── Progressive O₂ forecast: recompute when settings change ──────────
        viewModelScope.launch {
            combine(
                combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio, _progO2BreathPeriodSec) { args -> args },
                _forecastRefreshTrigger
            ) { arr, _ -> arr }
            .collectLatest { arr ->
                delay(150) // debounce
                try {
                    val lv = arr[0] as String; val pt = (arr[1] as PrepType).name
                    val tod = (arr[2] as TimeOfDay).name; val pos = (arr[3] as Posture).name
                    val aud = (arr[4] as AudioSetting).name; val bp = arr[5] as Int
                    val settings = ForecastSettings(lv, pt, tod, pos, aud)
                    // Pass ALL Progressive O₂ records; breath period is a regression
                    // feature via drillParam (pre-filtering starved the fit → always 100%).
                    val records = apneaRepository.getAllProgressiveO2Once()
                    val forecast = RecordForecastCalculator.compute(
                        records = records,
                        settings = settings,
                        nowEpochMs = System.currentTimeMillis(),
                        recordLabel = "rounds",
                        drillParam = bp
                    )
                    _uiState.update { it.copy(progO2RecordForecast = if (forecast.status == ForecastStatus.Ready) forecast else null) }
                } catch (e: Exception) {
                    Log.w("ApneaVM", "Progressive O₂ forecast computation failed", e)
                }
            }
        }

        // ── Min Breath forecast: recompute when settings change ──────────────
        viewModelScope.launch {
            combine(
                combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio, _minBreathSessionDurationSec) { args -> args },
                _forecastRefreshTrigger
            ) { arr, _ -> arr }
            .collectLatest { arr ->
                delay(150) // debounce
                try {
                    val lv = arr[0] as String; val pt = (arr[1] as PrepType).name
                    val tod = (arr[2] as TimeOfDay).name; val pos = (arr[3] as Posture).name
                    val aud = (arr[4] as AudioSetting).name; val sd = arr[5] as Int
                    val settings = ForecastSettings(lv, pt, tod, pos, aud)
                    // Pass ALL Min Breath records; session duration is a regression
                    // feature via drillParam (pre-filtering starved the fit → always 100%).
                    val records = apneaRepository.getAllMinBreathOnce()
                    val forecast = RecordForecastCalculator.compute(
                        records = records,
                        settings = settings,
                        nowEpochMs = System.currentTimeMillis(),
                        recordLabel = "sessions",
                        ceilingMs = sd * 1000L,  // Min Breath: max hold = entire session duration
                        drillParam = sd
                    )
                    _uiState.update { it.copy(minBreathRecordForecast = if (forecast.status == ForecastStatus.Ready) forecast else null) }
                } catch (e: Exception) {
                    Log.w("ApneaVM", "Min Breath forecast computation failed", e)
                }
            }
        }

        // ── Combo + section corner badges ────────────────────────────────────────
        // Recomputed whenever any of the five settings changes (the combo numbers
        // depend on the whole selection) and when returning from a session
        // (_forecastRefreshTrigger is bumped on resume and after a record is saved).
        viewModelScope.launch {
            combine(
                combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) { lv, pt, tod, pos, aud ->
                    listOf(lv, pt.name, tod.name, pos.name, aud.name)
                },
                _forecastRefreshTrigger
            ) { selected, _ -> selected }
                .collectLatest { sel ->
                    try {
                        val records = apneaRepository.getAllRecordsOnce()
                        val selected = mapOf(
                            "lungVolume" to sel[0],
                            "prepType"   to sel[1],
                            "timeOfDay"  to sel[2],
                            "posture"    to sel[3],
                            "audio"      to sel[4]
                        )
                        // Chip combo badges: chip's own value + currently selected values
                        // for every OTHER category.
                        val comboLast = selected.keys.associateWith { mutableMapOf<String, Long>() }
                        // Section badges: last use per session type (any settings / exact combo).
                        var freeAny: Long? = null;  var freeExact: Long? = null
                        var tableAny: Long? = null; var tableExact: Long? = null
                        var progAny: Long? = null;  var progExact: Long? = null
                        var minAny: Long? = null;   var minExact: Long? = null
                        var ctAny: Long? = null;    var ctExact: Long? = null

                        fun maxTs(current: Long?, ts: Long): Long =
                            if (current == null || ts > current) ts else current

                        for (r in records) {
                            val rec = mapOf(
                                "lungVolume" to r.lungVolume,
                                "prepType"   to r.prepType,
                                "timeOfDay"  to r.timeOfDay,
                                "posture"    to r.posture,
                                "audio"      to r.audio
                            )
                            for (cat in selected.keys) {
                                val othersMatch = selected.all { (k, v) -> k == cat || rec[k] == v }
                                if (othersMatch) {
                                    val perValue = comboLast.getValue(cat)
                                    val value = rec.getValue(cat)
                                    perValue[value] = maxOf(perValue[value] ?: 0L, r.timestamp)
                                }
                            }
                            val exact = rec == selected
                            when (r.tableType) {
                                null -> {
                                    freeAny = maxTs(freeAny, r.timestamp)
                                    if (exact) freeExact = maxTs(freeExact, r.timestamp)
                                }
                                "O2", "CO2" -> {
                                    tableAny = maxTs(tableAny, r.timestamp)
                                    if (exact) tableExact = maxTs(tableExact, r.timestamp)
                                }
                                "PROGRESSIVE_O2" -> {
                                    progAny = maxTs(progAny, r.timestamp)
                                    if (exact) progExact = maxTs(progExact, r.timestamp)
                                }
                                "MIN_BREATH" -> {
                                    minAny = maxTs(minAny, r.timestamp)
                                    if (exact) minExact = maxTs(minExact, r.timestamp)
                                }
                                "WONKA_FIRST_CONTRACTION", "WONKA_ENDURANCE" -> {
                                    ctAny = maxTs(ctAny, r.timestamp)
                                    if (exact) ctExact = maxTs(ctExact, r.timestamp)
                                }
                            }
                        }

                        _uiState.update {
                            it.copy(
                                lastUsedPerCombo = comboLast,
                                sectionLastUse = mapOf(
                                    ApneaSection.BEST_TIME          to SectionLastUse(freeAny, freeExact),
                                    ApneaSection.TABLE_TRAINING     to SectionLastUse(tableAny, tableExact),
                                    ApneaSection.PROGRESSIVE_O2     to SectionLastUse(progAny, progExact),
                                    ApneaSection.MIN_BREATH         to SectionLastUse(minAny, minExact),
                                    ApneaSection.CONTRACTION_TABLES to SectionLastUse(ctAny, ctExact)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("ApneaVM", "Corner badge computation failed", e)
                    }
                }
        }
    }

    /** ID of the most recent forecast calibration row (for updating after hold completes). */
    private var pendingForecastCalibrationId: Long? = null

    private fun onStateChanged(state: ApneaState) {
        when (state) {
            ApneaState.APNEA -> {
                // Stop any in-flight breath warning waveform before the hold starts.
                audioHapticEngine.cancelWarningVibrations()
                audioHapticEngine.announceHoldBegin()
                onApneaPhaseStarted()
            }
            ApneaState.VENTILATION -> {
                // Stop any in-flight hold warning waveform, then signal the end
                // of the hold. When the hold countdown's final-second pulse is
                // enabled it already covers this moment, so the generic buzz is
                // skipped to avoid stacking vibrations.
                audioHapticEngine.cancelWarningVibrations()
                audioHapticEngine.vibrateHoldEnd(countdownCovered = true)
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
                    val tableHoldMinutes = HabitIntegrationRepository.millisToMinutes(
                        _uiState.value.currentTable?.steps?.sumOf { it.apneaDurationMs } ?: 0L
                    )
                    // Split Tail slots: fire the slot matching the table type that just ran
                    val tableSlot = when (_uiState.value.currentTable?.type) {
                        ApneaTableType.CO2 -> Slot.CO2_TABLE
                        else -> Slot.O2_TABLE
                    }
                    habitRepo.sendHabitIncrementWithMinutes(tableSlot, tableHoldMinutes)
                    habitRepo.sendSecondaryValueIncrement(tableSlot, 1)
                    val uiSnap = _uiState.value
                    // Honor the user's explicit audio choice; never downgrade MUSIC
                    // to SILENCE based on unreliable Spotify track tracking.
                    val tableEffectiveAudio = uiSnap.audio.name
                    habitRepo.sendMusicHabitIncrementIfNeeded(tableEffectiveAudio, uiSnap.timeOfDay.name)
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
            // Honor the user's explicit audio choice; never downgrade MUSIC to
            // SILENCE based on unreliable Spotify track tracking.
            val tableEffectiveAudio = state.audio.name
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
                    audio = tableEffectiveAudio,
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
        tableTracksPlayed = if (_audio.value == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        // Stop guided audio if GUIDED is selected
        if (_audio.value == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        stateMachine.stop()
        audioHapticEngine.cancelWarningVibrations()
        if (tableTracksPlayed.isNotEmpty()) {
            persistSongHistory(tableTracksPlayed.map { SpotifySong(it.title, it.artist, null, it.spotifyUri, it.startedAtMs, it.endedAtMs) })
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
        audioHapticEngine.cancelWarningVibrations()
        // Do NOT save the session or fire tail increments
    }

    private fun onWarning(remainingSeconds: Long) {
        // announceTimeRemaining internally filters to its own cue points
        // (120/60/30/10..1), so the per-second callback is a no-op for voice
        // outside those points.
        audioHapticEngine.announceTimeRemaining(remainingSeconds.toInt())

        // Fire the configurable warning waveform exactly once, when the
        // countdown enters the configured warning window for the current phase.
        // Read directly from the StateFlow (always current) to avoid race with
        // the _uiState collector.
        when (stateMachine.state.value) {
            ApneaState.VENTILATION -> {
                val warning = audioHapticEngine.effectiveBreathWarning
                if (warning.enabled && remainingSeconds == warning.windowMs / 1000L) {
                    audioHapticEngine.playBreathWarning()
                }
            }
            ApneaState.APNEA -> {
                val warning = audioHapticEngine.effectiveHoldWarning
                if (warning.enabled && remainingSeconds == warning.windowMs / 1000L) {
                    audioHapticEngine.playHoldWarning()
                }
            }
            else -> Unit
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
                        totalFreeHolds = forecast.totalRecords,
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
        // Honor the user's explicit audio choice; never downgrade MUSIC to SILENCE
        // based on unreliable Spotify track tracking.
        val fhEffectiveAudio = state.audio.name
        // Signal the Habit app that a free breath hold was successfully completed
        val freeHoldMinutes = HabitIntegrationRepository.millisToMinutes(duration)
        habitRepo.sendHabitIncrementWithMinutes(Slot.FREE_HOLD, freeHoldMinutes)
        habitRepo.sendSecondaryValueIncrement(Slot.FREE_HOLD, 1)
        habitRepo.sendMusicHabitIncrementIfNeeded(fhEffectiveAudio, state.timeOfDay.name)
        viewModelScope.launch {
            // Check broader PB categories BEFORE saving so queries compare against prior records only.
            val pbResult = apneaRepository.checkBroaderPersonalBest(
                durationMs = duration,
                lungVolume = state.selectedLungVolume,
                prepType   = state.prepType.name,
                timeOfDay  = state.timeOfDay.name,
                posture    = state.posture.name,
                audio      = fhEffectiveAudio
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

            // Honor the user's explicit audio choice; never downgrade MUSIC to
            // SILENCE based on unreliable Spotify track tracking.
            val fhEffectiveAudio = state.audio.name

            // ── Save summary record ───────────────────────────────────────────
            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp = now,
                    durationMs = durationMs,
                    lungVolume = state.selectedLungVolume,
                    prepType = state.prepType.name,
                    timeOfDay = state.timeOfDay.name,
                    posture = state.posture.name,
                    audio = fhEffectiveAudio,
                    minHrBpm = minHr,
                    maxHrBpm = maxHr,
                    lowestSpO2 = lowestSpO2,
                    tableType = null,
                    firstContractionMs = firstContractionMs,
                    hrDeviceId = deviceLabel,
                    guidedAudioName = if (_audio.value == AudioSetting.GUIDED) _uiState.value.guidedSelectedName else null,
                    eucapnicPrepDurationSec = state.eucapnicConfig?.prepDurationSec,
                    eucapnicBreathsPerMin = state.eucapnicConfig?.breathsPerMin,
                    eucapnicInhaleSec = state.eucapnicConfig?.inhaleSec,
                    eucapnicTopPauseSec = state.eucapnicConfig?.topPauseSec,
                    eucapnicExhaleSec = state.eucapnicConfig?.exhaleSec,
                    eucapnicBottomPauseSec = state.eucapnicConfig?.bottomPauseSec,
                    eucapnicBreathDepthPercent = state.eucapnicConfig?.breathDepthPercent
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

            // ── Trigger forecast recompute with fresh data ────────────────────
            _forecastRefreshTrigger.value++
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
        // HYPER is time-locked: ignore attempts to select it while locked.
        if (type == PrepType.HYPER && _uiState.value.hyperRemainingLockDays > 0) return
        // RESONANCE prep is staleness-locked: no resonance breathing session ended
        // within the last ~5 minutes.
        if (type == PrepType.RESONANCE && _uiState.value.resonancePrepLocked) return

        _prepType.value = type
        _uiState.update { it.copy(prepType = type) }
        prefs.edit().putString("setting_prep_type", type.name).apply()
        
        // Initialize eucapnic config when EUCAPNIC_DIAPHRAGMATIC is selected
        if (type == PrepType.EUCAPNIC_DIAPHRAGMATIC && _uiState.value.eucapnicConfig == null) {
            _uiState.update { it.copy(eucapnicConfig = EucapnicConfig()) }
        }
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

        // Refresh the hyper lock so the remaining-days badge rolls over at midnight
        // and re-reads the configured lock length in case it changed elsewhere.
        val lastHyperUse = _uiState.value.lastUsedPerSetting["prepType"]?.get(PrepType.HYPER.name)
        _uiState.update {
            it.copy(
                hyperRemainingLockDays = HyperLockManager.remainingLockDays(
                    lastHyperUse,
                    hyperLockManager.lockDays,
                    System.currentTimeMillis()
                )
            )
        }
        if (_prepType.value == PrepType.HYPER && _uiState.value.hyperRemainingLockDays > 0) {
            setPrepType(PrepType.NO_PREP)
        }
    }

    /**
     * Bumps the forecast refresh trigger so the record-breaking probability
     * is recomputed with the latest data from the DB.
     * Called when the ApneaScreen resumes (e.g. after returning from a
     * completed free hold, drill, or table session on a separate screen).
     */
    fun refreshForecast() {
        _forecastRefreshTrigger.value++
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

    // ── Song picker (for table / advanced sessions on the main screen) ────────

    /**
     * Load distinct songs previously played during any apnea session.
     * Merges DB records with the SharedPreferences song history so songs
     * from table/advanced sessions (which have no DB record) are also shown.
     */
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
                        spotifyUri = "",
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
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
                        spotifyUri = uri,
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                }
            }
            // Final dedup by title+artist after API enrichment
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

    // ── Eucapnic Diaphragmatic Breathing ───────────────────────────────────────

    /**
     * Update the eucapnic configuration.
     */
    fun updateEucapnicConfig(config: EucapnicConfig) {
        _uiState.update { it.copy(eucapnicConfig = config) }
    }

    /**
     * Show the Past Configurations dialog.
     */
    fun showPastConfigurationsDialog() {
        _uiState.update { it.copy(showPastConfigurationsDialog = true) }
    }

    /**
     * Hide the Past Configurations dialog.
     */
    fun hidePastConfigurationsDialog() {
        _uiState.update { it.copy(showPastConfigurationsDialog = false) }
    }

    /**
     * Load a saved eucapnic configuration.
     */
    fun loadEucapnicConfiguration(config: EucapnicConfig) {
        _uiState.update { it.copy(eucapnicConfig = config) }
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
        guidedAudioManager.stopPlayback()
        stateMachine.stop()
    }
}
