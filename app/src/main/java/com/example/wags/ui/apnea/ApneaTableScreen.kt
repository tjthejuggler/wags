package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.ApneaTableStep
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.usecase.apnea.ApneaState
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaTableScreen(
    navController: NavController,
    tableType: String,
    viewModel: ApneaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val parsedType = runCatching { ApneaTableType.valueOf(tableType) }.getOrDefault(ApneaTableType.O2)

    val isActive = state.apneaState != ApneaState.IDLE && state.apneaState != ApneaState.COMPLETE

    // Keep screen on during COMPLETE too so the user can review results
    val keepScreenOn = isActive || state.apneaState == ApneaState.COMPLETE

    SessionBackHandler(enabled = isActive) { navController.popBackStack() }
    KeepScreenOn(enabled = keepScreenOn)

    // Load table when screen enters with a valid personal best
    LaunchedEffect(parsedType, state.personalBestMs) {
        if (state.personalBestMs > 0L && state.currentTable == null) {
            viewModel.loadTable(parsedType)
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("${parsedType.name} Table") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }

        if (showSongPicker) {
            SongPickerDialog(
                songs = state.previousSongs,
                isLoading = state.loadingSongs,
                selectedSong = state.selectedSong,
                loadingSelectedSong = state.loadingSelectedSong,
                onSongSelected = { track -> viewModel.selectSong(track) },
                onDismiss = { showSongPicker = false }
            )
        }

        if (state.personalBestMs <= 0L) {
            NoPbContent(modifier = Modifier.padding(padding))
        } else {
            // Box allows the overlay to sit behind the scrollable content
            Box(modifier = Modifier.fillMaxSize()) {
                ContractionOverlay(
                    uiState = state,
                    onLogContraction = { viewModel.logContraction() }
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Settings summary — always visible so the user knows which settings are active
                    item {
                        ApneaSettingsSummaryBanner(
                            lungVolume = state.selectedLungVolume,
                            prepType   = state.prepType.name,
                            timeOfDay  = state.timeOfDay.name,
                            posture    = state.posture.name,
                            audio      = state.audio.name
                        )
                    }
                    // Hyperventilating advice — shown only when prep type is HYPER
                    if (state.prepType == PrepType.HYPER) {
                        item {
                            AdviceBanner(section = AdviceSection.APNEA_HYPER)
                        }
                    }
                    item {
                        SessionStatusCard(
                            apneaState = state.apneaState,
                            currentRound = state.currentRound,
                            totalRounds = state.totalRounds,
                            remainingSeconds = state.remainingSeconds
                        )
                    }
                    item {
                        ContractionSummaryCard(uiState = state)
                    }
                    // Song picker — shown when MUSIC is selected, Spotify connected, session not active
                    if (state.audio == AudioSetting.MUSIC && state.spotifyConnected &&
                        state.apneaState == ApneaState.IDLE) {
                        item {
                            if (state.selectedSong != null) {
                                SelectedSongBanner(track = state.selectedSong!!) {
                                    viewModel.clearSelectedSong()
                                }
                            }
                            SongPickerButton(onClick = {
                                viewModel.loadPreviousSongs()
                                showSongPicker = true
                            })
                        }
                    }
                    item {
                        SessionControlRow(
                            apneaState = state.apneaState,
                            onStart = {
                                viewModel.loadTable(parsedType)
                                viewModel.startTableSession()
                            },
                            onStop = { viewModel.stopTableSession() }
                        )
                    }
                    state.currentTable?.let { table ->
                        item {
                            Text(
                                "Table Steps (PB: ${table.personalBestMs / 1000L}s)",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        itemsIndexed(table.steps) { index, step ->
                            TableStepRow(
                                step = step,
                                isActive = state.apneaState != ApneaState.IDLE &&
                                        state.currentRound == step.roundNumber,
                                isComplete = state.currentRound > step.roundNumber
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoPbContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("No Personal Best Set", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Set your personal best on the Apnea screen first.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SessionStatusCard(
    apneaState: ApneaState,
    currentRound: Int,
    totalRounds: Int,
    remainingSeconds: Long
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = apneaState.name,
                style = MaterialTheme.typography.headlineMedium,
                color = apneaStateColor(apneaState)
            )
            if (apneaState != ApneaState.IDLE && apneaState != ApneaState.COMPLETE) {
                Text(
                    "${remainingSeconds}s",
                    style = MaterialTheme.typography.displayLarge,
                    color = apneaStateColor(apneaState)
                )
                if (totalRounds > 0) {
                    Text(
                        "Round $currentRound / $totalRounds",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    LinearProgressIndicator(
                        progress = { currentRound.toFloat() / totalRounds.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = apneaStateColor(apneaState)
                    )
                }
            }
            if (apneaState == ApneaState.COMPLETE) {
                Text("Session Complete!", style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary)
            }
        }
    }
}

@Composable
private fun SessionControlRow(
    apneaState: ApneaState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    when (apneaState) {
        ApneaState.IDLE, ApneaState.COMPLETE -> {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(if (apneaState == ApneaState.COMPLETE) "Restart" else "Start Session")
            }
        }
        else -> {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("Stop Session")
            }
        }
    }
}

@Composable
private fun TableStepRow(
    step: ApneaTableStep,
    isActive: Boolean,
    isComplete: Boolean
) {
    val containerColor = when {
        isActive -> SurfaceVariant
        isComplete -> SurfaceDark.copy(alpha = 0.5f)
        else -> SurfaceDark
    }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Round ${step.roundNumber}",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isComplete) TextDisabled else TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hold", style = MaterialTheme.typography.labelMedium)
                        InfoHelpBubble(
                            title = HOLD_HELP_TITLE,
                            content = HOLD_HELP_CONTENT
                        )
                    }
                    Text(
                        "${step.apneaDurationMs / 1000L}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isActive) ApneaHold else TextPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rest", style = MaterialTheme.typography.labelMedium)
                        InfoHelpBubble(
                            title = REST_HELP_TITLE,
                            content = REST_HELP_CONTENT
                        )
                    }
                    Text(
                        "${step.ventilationDurationMs / 1000L}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isActive) ApneaVentilation else TextSecondary
                    )
                }
            }
            if (isActive) {
                Text("▶", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
            } else if (isComplete) {
                Text("✓", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            }
        }
    }
}

private fun apneaStateColor(state: ApneaState) = when (state) {
    ApneaState.IDLE -> TextSecondary
    ApneaState.VENTILATION -> ApneaVentilation
    ApneaState.APNEA -> ApneaHold
    ApneaState.RECOVERY -> ApneaRecovery
    ApneaState.COMPLETE -> TextPrimary
}

private const val REST_HELP_TITLE = "Rest Phase (Ventilation)"
private const val REST_HELP_CONTENT = """
Purpose: Recovery between breath-holds. Allows CO₂ to clear and O₂ to replenish.

In CO₂ Tables: Rest decreases each round to build CO₂ tolerance.
Formula: R_n = R₁ - ((n-1) × ΔR)
• R₁ = Initial rest (equals hold time)
• ΔR = (R₁ - R_min) / (N-1)
• n = Current round number

In O₂ Tables: Rest is fixed (120–180s) to allow full O₂ recovery.
"""

private const val HOLD_HELP_TITLE = "Breath-Hold Phase (Apnea)"
private const val HOLD_HELP_CONTENT = """
Purpose: The actual breath-hold. Your body consumes O₂ and produces CO₂.

In CO₂ Tables: Hold is fixed at T_hold = T_PB × hold%
In O₂ Tables: Hold increases each round.
Formula: H_n = H₁ + ((n-1) × ΔH)
• H₁ = T_PB × 40% (first hold)
• H_max = T_PB × 80–85% (max hold)
• ΔH = (H_max - H₁) / (N-1)
• T_PB = Your Personal Best

Physiological note: The urge to breathe is triggered by rising CO₂, not falling O₂.
"""

private const val CONTRACTION_HELP_TITLE = "Diaphragmatic Contractions"
private const val CONTRACTION_HELP_CONTENT = """
Purpose: Involuntary diaphragm contractions signal rising CO₂ levels.

Phases:
• Cruising Phase: Time from hold start to first contraction (aerobic zone)
• Struggle Phase: Time from first contraction to hold end (anaerobic zone)

Training insight: A longer Cruising Phase indicates better CO₂ tolerance.
The ratio Cruising/Total Hold is your "efficiency score".

Formula: Efficiency = T_cruise / T_total × 100%
• T_cruise = Time to first contraction
• T_total = Total hold duration
"""
