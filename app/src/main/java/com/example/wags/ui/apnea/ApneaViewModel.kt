package com.example.wags.ui.apnea

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.OximeterBleManager
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.OximeterReading
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    /** Non-null when a new personal best was just set — holds the new PB in ms for the congrats dialog. */
    val newPersonalBestMs: Long? = null,
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
)

@HiltViewModel
class ApneaViewModel @Inject constructor(
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository,
    private val tableGenerator: ApneaTableGenerator,
    private val stateMachine: ApneaStateMachine,
    private val advancedStateMachine: AdvancedApneaStateMachine,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApneaUiState())
    val uiState: StateFlow<ApneaUiState> = _uiState.asStateFlow()

    private var freeHoldStartTime = 0L

    /**
     * Timestamped oximeter readings collected while a free hold is active.
     * Each entry is (epochMs, OximeterReading). Cleared at the start of each hold.
     */
    private val oximeterSamples = mutableListOf<Pair<Long, OximeterReading>>()
    private var oximeterCollectionJob: Job? = null
    private var advancedSessionStartMs: Long = 0L

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
            oximeterBleManager.liveHr.collect { hr ->
                _uiState.update { it.copy(liveOxHr = hr) }
            }
        }
        viewModelScope.launch {
            oximeterBleManager.liveSpO2.collect { spo2 ->
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
        // Whenever any setting changes, re-subscribe to filtered records
        viewModelScope.launch {
            combine(_lungVolume, _prepType, _timeOfDay) { lv, pt, tod -> Triple(lv, pt, tod) }
                .collectLatest { (lv, pt, tod) ->
                    apneaRepository.getBySettings(lv, pt.name, tod.name).collect { records ->
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
                totalRounds = state.totalRounds
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
                totalRounds = advState.totalRounds
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
        val isNewPb = pbMs > current
        _uiState.update {
            it.copy(
                personalBestMs = pbMs,
                newPersonalBestMs = if (isNewPb) pbMs else null
            )
        }
        prefs.edit().putLong("pb_ms", pbMs).apply()
    }

    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBestMs = null) }
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

    fun startTableSession(deviceId: String) {
        bleManager.startRrStream(deviceId)
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

    fun startFreeHold(deviceId: String) {
        bleManager.startRrStream(deviceId)
        freeHoldStartTime = System.currentTimeMillis()
        _uiState.update { it.copy(freeHoldActive = true, freeHoldDurationMs = 0L) }

        // Start collecting oximeter readings for this hold
        oximeterSamples.clear()
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = viewModelScope.launch {
            oximeterBleManager.readings.collect { reading ->
                oximeterSamples.add(System.currentTimeMillis() to reading)
            }
        }
    }

    fun stopFreeHold() {
        val duration = System.currentTimeMillis() - freeHoldStartTime
        // Stop collecting oximeter readings before saving so the snapshot is stable
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        val currentBest = _uiState.value.bestTimeForSettingsMs
        val isNewBest = duration > currentBest && currentBest > 0L
        _uiState.update {
            it.copy(
                freeHoldActive = false,
                freeHoldDurationMs = duration,
                newPersonalBestMs = if (isNewBest) duration else it.newPersonalBestMs
            )
        }
        audioHapticEngine.vibrateHoldEnd()
        saveFreeHoldRecord(duration)
    }

    private fun saveFreeHoldRecord(durationMs: Long) {
        // Take a stable snapshot of the oximeter samples collected during this hold
        val oxSnapshot = oximeterSamples.toList()
        oximeterSamples.clear()

        viewModelScope.launch {
            // ── Polar RR-derived HR samples ───────────────────────────────────
            val rrSnapshot = bleManager.rrBuffer.readLast(512)
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
                    tableType = null
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
