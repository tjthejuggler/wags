package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.ApneaRecordEntity
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    /** When true, a live elapsed-time counter is shown during the breath hold. */
    val showTimer: Boolean = true,
    /** Best free-hold for the current lungVolume + prepType combination (from DB). */
    val bestTimeForSettingsMs: Long = 0L,
    /** recordId of the best free-hold record for the current settings combination (null if none). */
    val bestTimeForSettingsRecordId: Long? = null,
    /**
     * The broadest PB category that the current best record holds.
     * Determines how many trophy emojis to show (1–6). Null when no best record exists.
     */
    val bestTimeTrophyCategory: PersonalBestCategory? = null,
    val selectedLength: TableLength = TableLength.MEDIUM,
    val selectedDifficulty: TableDifficulty = TableDifficulty.MEDIUM,
    // Contraction tracking
    val contractionTimestamps: List<Long> = emptyList(),
    val contractionCount: Int = 0,
    val firstContractionElapsedMs: Long? = null,
    val currentRoundStartMs: Long = 0L,
    val lastHoldDurationMs: Long = 0L,
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

        _uiState.update {
            it.copy(
                personalBestMs = if (savedPb > 0L) savedPb else it.personalBestMs,
                selectedLungVolume = savedLungVolume,
                prepType = savedPrepType,
                posture = savedPosture,
                audio = savedAudio,
                showTimer = savedShowTimer,
                selectedLength = savedLength,
                selectedDifficulty = savedDifficulty
            )
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
                        _uiState.update { it.copy(bestTimeForSettingsMs = best ?: 0L) }
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
    }

    private fun onStateChanged(state: ApneaState) {
        when (state) {
            ApneaState.APNEA -> {
                audioHapticEngine.announceHoldBegin()
                onApneaPhaseStarted()
            }
            ApneaState.RECOVERY -> {
                audioHapticEngine.announceBreath()
                val table = _uiState.value.currentTable
                val round = _uiState.value.currentRound
                val holdMs = table?.steps?.getOrNull(round - 1)?.apneaDurationMs ?: 0L
                _uiState.update { it.copy(lastHoldDurationMs = holdMs) }
            }
            ApneaState.VENTILATION -> audioHapticEngine.announceBreath()
            ApneaState.COMPLETE -> {
                audioHapticEngine.announceSessionComplete()
                saveCompletedSession()
                // Signal the Habit app that a full O2/CO2 table session was completed
                habitRepo.sendHabitIncrement(Slot.TABLE_TRAINING)
            }
            else -> Unit
        }
    }

    private fun saveCompletedSession() {
        viewModelScope.launch {
            val state = _uiState.value
            val tableType = state.currentTable?.type?.name ?: "UNKNOWN"
            val variantStr = "${state.selectedLength.name}_${state.selectedDifficulty.name}"
            val contractionJson = state.contractionTimestamps
                .joinToString(",", "[", "]") { it.toString() }
            val entity = ApneaSessionEntity(
                timestamp = System.currentTimeMillis(),
                tableType = tableType,
                tableVariant = variantStr,
                tableParamsJson = "{}",
                pbAtSessionMs = state.personalBestMs,
                totalSessionDurationMs = state.currentTable?.steps
                    ?.sumOf { it.apneaDurationMs + it.ventilationDurationMs } ?: 0L,
                contractionTimestampsJson = contractionJson,
                maxHrBpm = null,
                lowestSpO2 = null,
                roundsCompleted = state.currentRound,
                totalRounds = state.totalRounds,
                hrDeviceId = hrDataSource.activeHrDeviceLabel()
            )
            sessionRepository.saveSession(entity)
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
        val polarDeviceId = hrDataSource.connectedPolarDeviceId()
        if (polarDeviceId != null) {
            deviceManager.startRrStream(polarDeviceId)
        }
        stateMachine.setCallbacks(
            onWarning = { secs -> onWarning(secs) },
            onStateChange = { /* state collected via flow in init */ }
        )
        stateMachine.start(viewModelScope)
        // Start Spotify if MUSIC is selected
        if (_audio.value == AudioSetting.MUSIC) {
            val selected = _uiState.value.selectedSong
            val hasValidUri = selected != null
                && selected.spotifyUri.isNotBlank()
                && spotifyAuthManager.isConnected.value
            spotifyManager.startTracking()
            if (hasValidUri) {
                viewModelScope.launch {
                    val success = spotifyApiClient.startPlayback(selected!!.spotifyUri)
                    if (!success) spotifyManager.sendPlayCommand()
                }
            } else {
                spotifyManager.sendPlayCommand()
            }
        }
    }

    fun stopTableSession() {
        val tracksPlayed = if (_audio.value == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        stateMachine.stop()
        if (tracksPlayed.isNotEmpty()) {
            persistSongHistory(tracksPlayed.map { SpotifySong(it.title, it.artist, null, it.spotifyUri, it.startedAtMs, it.endedAtMs) })
        }
    }

    private fun onWarning(remainingSeconds: Long) {
        audioHapticEngine.announceTimeRemaining(remainingSeconds.toInt())
        when {
            remainingSeconds <= 3L -> audioHapticEngine.vibrateFinalCountdown()
            remainingSeconds == 10L -> audioHapticEngine.vibrateFinalCountdown()
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
                    hrDeviceId = deviceLabel
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
        _uiState.update { it.copy(audio = audio) }
        prefs.edit().putString("setting_audio", audio.name).apply()
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

    // ── Layout / accordion ────────────────────────────────────────────────────

    fun toggleSettings() {
        _uiState.update { it.copy(settingsExpanded = !it.settingsExpanded) }
    }

    fun toggleShowAllStats() {
        _uiState.update { it.copy(showAllStats = !it.showAllStats) }
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
        // Start Spotify if MUSIC is selected
        if (_audio.value == AudioSetting.MUSIC) {
            val selected = _uiState.value.selectedSong
            val hasValidUri = selected != null
                && selected.spotifyUri.isNotBlank()
                && spotifyAuthManager.isConnected.value
            spotifyManager.startTracking()
            if (hasValidUri) {
                viewModelScope.launch {
                    val success = spotifyApiClient.startPlayback(selected!!.spotifyUri)
                    if (!success) spotifyManager.sendPlayCommand()
                }
            } else {
                spotifyManager.sendPlayCommand()
            }
        }
    }

    fun stopAdvancedSession() {
        val tracksPlayed = if (_audio.value == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
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
            // Load from DB (free holds)
            val dbSongs = apneaRepository.getDistinctSongs()
            // Load from prefs (table/advanced sessions)
            val prefsSongs = loadSongHistoryFromPrefs()
            // Merge: prefer DB entries (they have more metadata), deduplicate by URI then title+artist
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
            _uiState.update { it.copy(previousSongs = details, loadingSongs = false) }
        }
    }

    fun selectSong(track: SpotifyTrackDetail) {
        _uiState.update { it.copy(selectedSong = track) }
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
            val key = if (!song.spotifyUri.isNullOrBlank()) song.spotifyUri!! else "${song.title}|${song.artist}"
            val alreadyPresent = existing.any { s ->
                val k = if (!s.spotifyUri.isNullOrBlank()) s.spotifyUri!! else "${s.title}|${s.artist}"
                k == key
            }
            if (!alreadyPresent) existing.add(0, song)
        }
        // Keep most recent 50
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
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SpotifySong>()
        for (song in dbSongs + prefsSongs) {
            val key = if (!song.spotifyUri.isNullOrBlank()) song.spotifyUri!! else "${song.title}|${song.artist}"
            if (seen.add(key)) result.add(song)
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
        stateMachine.stop()
        advancedStateMachine.stop()
    }
}
