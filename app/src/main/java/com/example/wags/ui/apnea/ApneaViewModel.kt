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
import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
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
)

@HiltViewModel
class ApneaViewModel @Inject constructor(
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository,
    private val tableGenerator: ApneaTableGenerator,
    private val stateMachine: ApneaStateMachine,
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

    // Separate flows for the two settings that drive the best-time / filtered-records queries
    private val _lungVolume = MutableStateFlow("FULL")
    private val _prepType   = MutableStateFlow(PrepType.NO_PREP)

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

        // Whenever lungVolume or prepType changes, re-subscribe to best-time query
        viewModelScope.launch {
            combine(_lungVolume, _prepType) { lv, pt -> lv to pt }
                .collectLatest { (lv, pt) ->
                    apneaRepository.getBestFreeHold(lv, pt.name).collect { best ->
                        _uiState.update { it.copy(bestTimeForSettingsMs = best ?: 0L) }
                    }
                }
        }
        // Whenever lungVolume or prepType changes, re-subscribe to filtered records
        viewModelScope.launch {
            combine(_lungVolume, _prepType) { lv, pt -> lv to pt }
                .collectLatest { (lv, pt) ->
                    apneaRepository.getBySettings(lv, pt.name).collect { records ->
                        _uiState.update { it.copy(recentRecords = records) }
                    }
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
        _uiState.update { it.copy(personalBestMs = pbMs) }
        prefs.edit().putLong("pb_ms", pbMs).apply()
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
        _uiState.update { it.copy(freeHoldActive = false, freeHoldDurationMs = duration) }
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
            // Each oximeter sample is stored as a separate row so the detail
            // screen can plot both HR and SpO2 over time.
            for ((timestampMs, reading) in oxSnapshot) {
                // Only include samples that fall within the hold window
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

    fun setShowTimer(show: Boolean) {
        _uiState.update { it.copy(showTimer = show) }
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
        stateMachine.stop()
    }
}
