package com.example.wags.ui.apnea

import android.content.SharedPreferences
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
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.PrepType
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
    /** When true, a live elapsed-time counter is shown during the breath hold. */
    val showTimer: Boolean = true,
    /** Best free-hold for the current lungVolume + prepType combination (from DB). */
    val bestTimeForSettingsMs: Long = 0L,
    /** recordId of the best free-hold record for the current settings combination (null if none). */
    val bestTimeForSettingsRecordId: Long? = null,
    /**
     * The broadest PB category that the current best record holds.
     * Determines how many trophy emojis to show (1–4). Null when no best record exists.
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
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
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

    // Separate flows for the three settings that drive the best-time / filtered-records queries
    private val _lungVolume  = MutableStateFlow("FULL")
    private val _prepType    = MutableStateFlow(PrepType.NO_PREP)
    private val _timeOfDay   = MutableStateFlow(TimeOfDay.fromCurrentTime())

    init {
        // Load persisted PB on startup
        val savedPb = prefs.getLong("pb_ms", 0L)
        if (savedPb > 0L) {
            _uiState.update { it.copy(personalBestMs = savedPb) }
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
            combine(_lungVolume, _prepType, _timeOfDay) { lv, pt, tod -> Triple(lv, pt, tod) }
                .collectLatest { (lv, pt, tod) ->
                    apneaRepository.getBestFreeHold(lv, pt.name, tod.name).collect { best ->
                        _uiState.update { it.copy(bestTimeForSettingsMs = best ?: 0L) }
                    }
                }
        }
        // Whenever any setting changes, re-subscribe to best-time record-id query
        // and recompute the trophy level for the best record.
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay) { lv, pt, tod -> Triple(lv, pt, tod) }
                .collectLatest { (lv, pt, tod) ->
                    apneaRepository.getBestFreeHoldRecordId(lv, pt.name, tod.name).collect { id ->
                        _uiState.update { it.copy(bestTimeForSettingsRecordId = id) }
                        // Compute trophy level for the best record
                        val trophyCategory = if (id != null) {
                            apneaRepository.getBestRecordTrophyLevel(lv, pt.name, tod.name)
                        } else null
                        _uiState.update { it.copy(bestTimeTrophyCategory = trophyCategory) }
                    }
                }
        }
        // Recent records: 10 most recent across ALL event types, filtered by current settings
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay) { lv, pt, tod -> Triple(lv, pt, tod) }
                .collectLatest { (lv, pt, tod) ->
                    apneaRepository.getRecentBySettings(lv, pt.name, tod.name, limit = 10).collect { records ->
                        _uiState.update { it.copy(recentRecords = records) }
                    }
                }
        }
        // Whenever any setting changes, re-subscribe to filtered stats
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay) { lv, pt, tod -> Triple(lv, pt, tod) }
                .collectLatest { (lv, pt, tod) ->
                    apneaRepository.getStats(lv, pt.name, tod.name).collect { stats ->
                        _uiState.update { it.copy(filteredStats = stats) }
                    }
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

    fun setLength(length: TableLength) {
        _uiState.update { it.copy(selectedLength = length) }
    }

    fun setDifficulty(difficulty: TableDifficulty) {
        _uiState.update { it.copy(selectedDifficulty = difficulty) }
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
    }

    fun stopTableSession() = stateMachine.stop()

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
    }

    /**
     * Cancels an in-progress free hold without saving any record.
     * Called when the user taps the back arrow while the hold is running.
     */
    fun cancelFreeHold() {
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
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
        // Signal the Habit app that a free breath hold was successfully completed
        habitRepo.sendHabitIncrement(Slot.FREE_HOLD)
        viewModelScope.launch {
            // Check broader PB categories BEFORE saving so queries compare against prior records only.
            val pbResult = apneaRepository.checkBroaderPersonalBest(
                durationMs = duration,
                lungVolume = state.selectedLungVolume,
                prepType   = state.prepType.name,
                timeOfDay  = state.timeOfDay.name
            )
            saveFreeHoldRecord(duration, firstContractionMs)
            if (pbResult != null) {
                _uiState.update { it.copy(newPersonalBest = pbResult) }
                habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD)
            }
        }
    }

    private fun saveFreeHoldRecord(durationMs: Long, firstContractionMs: Long? = null) {
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
        }
    }

    fun setLungVolume(volume: String) {
        _lungVolume.value = volume
        _uiState.update { it.copy(selectedLungVolume = volume) }
    }

    fun setPrepType(type: PrepType) {
        _prepType.value = type
        _uiState.update { it.copy(prepType = type) }
    }

    fun setTimeOfDay(tod: TimeOfDay) {
        _timeOfDay.value = tod
        _uiState.update { it.copy(timeOfDay = tod) }
    }

    fun setShowTimer(show: Boolean) {
        _uiState.update { it.copy(showTimer = show) }
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
    }

    fun stopAdvancedSession() {
        advancedStateMachine.stop()
        _uiState.update { it.copy(activeModalitySession = null) }
    }

    fun signalBreathTaken() {
        advancedStateMachine.signalBreathTaken()
        audioHapticEngine.announceHoldBegin()
    }

    fun signalFirstContraction() {
        advancedStateMachine.signalFirstContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
        stateMachine.stop()
        advancedStateMachine.stop()
    }
}
