package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.OximeterBleManager
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Physiological sanity bounds
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Any HR or SpO2 value outside these ranges is a sensor glitch and must be
 * discarded before it reaches the database or any aggregate calculation.
 *
 * HR  : 20–250 bpm
 *   Lower bound: extreme diving bradycardia can reach ~20 bpm; anything below
 *   is a BLE dropout artefact (device reports 0 when signal is lost).
 *
 * SpO2: 1–100 %
 *   Lower bound is intentionally 1, NOT 50.  Elite freedivers have documented
 *   SpO2 readings in the 25–40 % range during competitive dives, so any
 *   physiologically plausible non-zero value must be preserved.
 *   Zero is the only value that is unambiguously a "no signal" artefact.
 */
internal object PhysiologicalBounds {
    const val HR_MIN   = 20
    const val HR_MAX   = 250
    const val SPO2_MIN = 1      // reject 0 only — real extreme dives can go very low
    const val SPO2_MAX = 100

    fun isValidHr(bpm: Int): Boolean    = bpm in HR_MIN..HR_MAX
    fun isValidHr(bpm: Float): Boolean  = bpm >= HR_MIN && bpm <= HR_MAX
    fun isValidSpO2(pct: Int): Boolean  = pct in SPO2_MIN..SPO2_MAX
    fun isValidSpO2(pct: Float): Boolean = pct >= SPO2_MIN && pct <= SPO2_MAX
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

data class FreeHoldActiveUiState(
    val freeHoldActive: Boolean = false,
    val showTimer: Boolean = true,
    val freeHoldFirstContractionMs: Long? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Non-null when the just-completed hold set a new PB for this settings combination. */
    val newPersonalBestMs: Long? = null,
    /** True while the async personal-best check is still running after a hold ends. */
    val pbCheckPending: Boolean = false
)

@HiltViewModel
class FreeHoldActiveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val hrDataSource: HrDataSource,
    private val apneaRepository: ApneaRepository,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository
) : ViewModel() {

    // Settings passed in via nav arguments — these are the source of truth for what gets saved
    val lungVolume: String = savedStateHandle.get<String>("lungVolume") ?: "FULL"
    val prepType: String   = savedStateHandle.get<String>("prepType")   ?: "NO_PREP"
    val timeOfDay: String  = savedStateHandle.get<String>("timeOfDay")  ?: "DAY"

    private val _uiState = MutableStateFlow(
        FreeHoldActiveUiState(
            showTimer = savedStateHandle.get<Boolean>("showTimer") ?: true
        )
    )

    val uiState: StateFlow<FreeHoldActiveUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FreeHoldActiveUiState(
            showTimer = savedStateHandle.get<Boolean>("showTimer") ?: true
        )
    )

    private var freeHoldStartTime = 0L
    private val oximeterSamples = mutableListOf<Pair<Long, OximeterReading>>()
    private var oximeterCollectionJob: Job? = null

    fun startFreeHold(deviceId: String) {
        bleManager.startRrStream(deviceId)
        freeHoldStartTime = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                freeHoldActive = true,
                freeHoldFirstContractionMs = null
            )
        }
        oximeterSamples.clear()
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = viewModelScope.launch {
            oximeterBleManager.readings.collect { reading ->
                oximeterSamples.add(System.currentTimeMillis() to reading)
            }
        }
    }

    fun cancelFreeHold() {
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
        _uiState.update { it.copy(freeHoldActive = false, freeHoldFirstContractionMs = null) }
    }

    fun recordFreeHoldFirstContraction() {
        if (_uiState.value.freeHoldFirstContractionMs != null) return
        val elapsed = System.currentTimeMillis() - freeHoldStartTime
        _uiState.update { it.copy(freeHoldFirstContractionMs = elapsed) }
        audioHapticEngine.vibrateContractionLogged()
    }

    fun stopFreeHold() {
        val duration = System.currentTimeMillis() - freeHoldStartTime
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        val firstContractionMs = _uiState.value.freeHoldFirstContractionMs
        _uiState.update {
            it.copy(
                freeHoldActive = false,
                freeHoldFirstContractionMs = null,
                pbCheckPending = true          // prevent navigation until PB check completes
            )
        }
        audioHapticEngine.vibrateHoldEnd()
        habitRepo.sendHabitIncrement(Slot.FREE_HOLD)
        viewModelScope.launch {
            // Query the prior best BEFORE saving so we can detect a new record correctly.
            // A null prior best means this is the first-ever hold for this settings combo —
            // that always counts as a new personal best.
            val priorBest = apneaRepository.getBestFreeHoldOnce(lungVolume, prepType, timeOfDay)
            val isNewPb = priorBest == null || duration > priorBest
            saveFreeHoldRecord(duration, firstContractionMs)
            if (isNewPb) {
                _uiState.update { it.copy(newPersonalBestMs = duration, pbCheckPending = false) }
                habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD)
            } else {
                _uiState.update { it.copy(pbCheckPending = false) }
            }
        }
    }

    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBestMs = null) }
    }

    private fun saveFreeHoldRecord(durationMs: Long, firstContractionMs: Long? = null) {
        val oxSnapshot = oximeterSamples.toList()
        oximeterSamples.clear()

        viewModelScope.launch {
            val rrSnapshot = bleManager.rrBuffer.readLast(512)

            // ── RR-derived HR — discard physiologically impossible beats ──────
            val rrHrValues = rrSnapshot
                .map { 60_000.0 / it }
                .filter { PhysiologicalBounds.isValidHr(it.toFloat()) }
            val minHrFromRr = rrHrValues.minOrNull()?.toFloat() ?: 0f
            val maxHrFromRr = rrHrValues.maxOrNull()?.toFloat() ?: 0f

            // ── Oximeter samples — discard glitch readings (0 bpm, 0 SpO2, etc.) ──
            val validOxHr   = oxSnapshot
                .map { it.second.heartRateBpm.toFloat() }
                .filter { PhysiologicalBounds.isValidHr(it) }
            val validOxSpO2 = oxSnapshot
                .map { it.second.spO2.toFloat() }
                .filter { PhysiologicalBounds.isValidSpO2(it) }

            val maxHrFromOx = validOxHr.maxOrNull() ?: 0f
            val lowestSpO2  = validOxSpO2.minOrNull()?.toInt()

            // Prefer Polar for HR aggregates; fall back to oximeter
            val minHr = if (minHrFromRr > 0f) minHrFromRr else validOxHr.minOrNull() ?: 0f
            val maxHr = if (maxHrFromRr > 0f) maxHrFromRr else maxHrFromOx

            val now = System.currentTimeMillis()

            // Use the settings that were baked in at navigation time — guaranteed correct
            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp          = now,
                    durationMs         = durationMs,
                    lungVolume         = lungVolume,
                    prepType           = prepType,
                    timeOfDay          = timeOfDay,
                    minHrBpm           = minHr,
                    maxHrBpm           = maxHr,
                    lowestSpO2         = lowestSpO2,
                    tableType          = null,
                    firstContractionMs = firstContractionMs
                )
            )

            if (recordId <= 0) return@launch

            val samples = mutableListOf<FreeHoldTelemetryEntity>()

            // ── Polar RR → per-beat HR telemetry (only valid beats) ──────────
            if (rrSnapshot.isNotEmpty()) {
                var cumulativeMs = 0L
                for (rrMs in rrSnapshot) {
                    cumulativeMs += rrMs.toLong()
                    if (cumulativeMs > durationMs) break
                    val bpm = (60_000.0 / rrMs).toInt()
                    if (!PhysiologicalBounds.isValidHr(bpm)) continue   // skip glitch beat
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId     = recordId,
                            timestampMs  = freeHoldStartTime + cumulativeMs,
                            heartRateBpm = bpm,
                            spO2         = null
                        )
                    )
                }
            }

            // ── Oximeter → HR + SpO2 telemetry (only valid readings) ─────────
            for ((timestampMs, reading) in oxSnapshot) {
                if (timestampMs < freeHoldStartTime) continue
                if (timestampMs > freeHoldStartTime + durationMs) continue
                val validHr   = reading.heartRateBpm.takeIf { PhysiologicalBounds.isValidHr(it) }
                val validSpO2 = reading.spO2.takeIf { PhysiologicalBounds.isValidSpO2(it) }
                // Only save the row if at least one field is valid
                if (validHr != null || validSpO2 != null) {
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId     = recordId,
                            timestampMs  = timestampMs,
                            heartRateBpm = validHr,
                            spO2         = validSpO2
                        )
                    )
                }
            }

            if (samples.isNotEmpty()) {
                apneaRepository.saveTelemetry(samples)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen breath-hold active screen.
 *
 * Settings (lungVolume, prepType, timeOfDay, showTimer) are passed as nav
 * arguments so the correct values are always saved — regardless of which
 * ViewModel instance is alive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeHoldActiveScreen(
    navController: NavController,
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    showTimer: Boolean,
    viewModel: FreeHoldActiveViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    // True once the user taps Stop — we wait for the async PB check before navigating.
    var stopRequested by remember { mutableStateOf(false) }

    // Navigate back when:
    //   • the user tapped Stop (stopRequested), AND
    //   • the async PB check has finished (pbCheckPending is false), AND
    //   • no PB dialog is showing (newPersonalBestMs is null — either no PB or dismissed).
    LaunchedEffect(stopRequested, state.freeHoldActive, state.newPersonalBestMs, state.pbCheckPending) {
        if (stopRequested && !state.freeHoldActive && !state.pbCheckPending && state.newPersonalBestMs == null) {
            navController.popBackStack()
        }
    }

    // Show the PB celebration dialog; dismiss clears the state which triggers the
    // LaunchedEffect above to pop back.
    state.newPersonalBestMs?.let { newPbMs ->
        NewPersonalBestDialog(
            newPbMs = newPbMs,
            onDismiss = { viewModel.dismissNewPersonalBest() }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Breath Hold", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.freeHoldActive) viewModel.cancelFreeHold()
                        navController.popBackStack()
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        FreeHoldActiveContent(
            freeHoldActive = state.freeHoldActive,
            showTimer = state.showTimer,
            firstContractionMs = state.freeHoldFirstContractionMs,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onStart = { viewModel.startFreeHold(deviceId) },
            onFirstContraction = { viewModel.recordFreeHoldFirstContraction() },
            onStop = {
                stopRequested = true
                viewModel.stopFreeHold()
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeHoldActiveContent(
    freeHoldActive: Boolean,
    showTimer: Boolean,
    firstContractionMs: Long?,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val holdStartWallClock = remember { mutableLongStateOf(0L) }

    LaunchedEffect(freeHoldActive) {
        if (freeHoldActive) {
            holdStartWallClock.longValue = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - holdStartWallClock.longValue
                delay(50L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Timer display ─────────────────────────────────────────────────────
        if (freeHoldActive && showTimer) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = formatElapsedMs(elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (freeHoldActive) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "HOLD",
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Main button area ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                !freeHoldActive -> {
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonSuccess,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "START",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                freeHoldActive && firstContractionMs == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onFirstContraction,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ReadinessOrange,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "First\nContraction",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonDanger,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Stop",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonDanger,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsedMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0)
        "${minutes}m ${seconds}s"
    else
        "${seconds}.${centis.toString().padStart(2, '0')}s"
}
