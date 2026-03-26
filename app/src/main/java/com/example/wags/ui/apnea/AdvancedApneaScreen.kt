package com.example.wags.ui.apnea

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.WonkaConfig
import com.example.wags.domain.usecase.apnea.AdvancedApneaPhase
import com.example.wags.domain.usecase.apnea.AdvancedApneaState
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedApneaScreen(
    navController: NavController,
    modality: TrainingModality,
    length: TableLength,
    viewModel: AdvancedApneaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.sessionState

    val isActive = state.phase != AdvancedApneaPhase.IDLE && state.phase != AdvancedApneaPhase.COMPLETE

    SessionBackHandler(enabled = isActive) {
        viewModel.stopSession()
        navController.popBackStack()
    }
    KeepScreenOn(enabled = isActive)

    LaunchedEffect(modality, length) {
        if (state.phase == AdvancedApneaPhase.IDLE) {
            viewModel.startSession(modality, length)
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(modality.displayName()) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopSession()
                        navController.popBackStack()
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    ModalityHelpIcon(modality = modality)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }

        if (showSongPicker) {
            SongPickerDialog(
                songs = uiState.previousSongs,
                isLoading = uiState.loadingSongs,
                selectedSong = uiState.selectedSong,
                loadingSelectedSong = uiState.loadingSelectedSong,
                onSongSelected = { track -> viewModel.selectSong(track) },
                onDismiss = { showSongPicker = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Song picker — shown when MUSIC mode + Spotify connected + session not yet started
            if (uiState.isMusicMode && uiState.spotifyConnected && state.phase == AdvancedApneaPhase.IDLE) {
                if (uiState.selectedSong != null) {
                    SelectedSongBanner(track = uiState.selectedSong!!) {
                        viewModel.clearSelectedSong()
                    }
                }
                SongPickerButton(onClick = {
                    viewModel.loadPreviousSongs()
                    showSongPicker = true
                })
            }

            RoundProgressBar(state = state)
            PhaseTimerCard(state = state, modality = modality)
            ActionArea(
                state = state,
                modality = modality,
                onBreathTaken = { viewModel.signalBreathTaken() },
                onFirstContraction = { viewModel.signalFirstContraction() },
                onStop = {
                    viewModel.stopSession()
                    navController.popBackStack()
                }
            )
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun RoundProgressBar(state: AdvancedApneaState) {
    if (state.totalRounds == 0) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Round ${state.currentRound} / ${state.totalRounds}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Text(
                state.phase.displayLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = state.phase.phaseColor()
            )
        }
        LinearProgressIndicator(
            progress = {
                if (state.totalRounds > 0) state.currentRound.toFloat() / state.totalRounds else 0f
            },
            modifier = Modifier.fillMaxWidth(),
            color = state.phase.phaseColor()
        )
    }
}

@Composable
private fun PhaseTimerCard(state: AdvancedApneaState, modality: TrainingModality) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = state.phase.displayLabel(),
                style = MaterialTheme.typography.headlineMedium,
                color = state.phase.phaseColor()
            )

            when (state.phase) {
                AdvancedApneaPhase.WAITING_FOR_BREATH -> {
                    Text(
                        "Waiting for breath…",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextSecondary
                    )
                }
                AdvancedApneaPhase.WONKA_CRUISING -> {
                    Text(
                        formatMmSs(state.timerMs),
                        style = MaterialTheme.typography.displayLarge,
                        color = ApneaHold
                    )
                    Text(
                        "Counting up — log first contraction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                AdvancedApneaPhase.WONKA_ENDURANCE -> {
                    Text(
                        formatMmSs(state.timerMs),
                        style = MaterialTheme.typography.displayLarge,
                        color = ApneaHold
                    )
                    Text(
                        "Endurance phase — cruised ${formatMmSs(state.cruisingElapsedMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                AdvancedApneaPhase.COMPLETE -> {
                    Text(
                        "Session Complete!",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary
                    )
                }
                else -> {
                    if (state.phase != AdvancedApneaPhase.IDLE) {
                        Text(
                            formatMmSs(state.timerMs),
                            style = MaterialTheme.typography.displayLarge,
                            color = state.phase.phaseColor()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionArea(
    state: AdvancedApneaState,
    modality: TrainingModality,
    onBreathTaken: () -> Unit,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    when (state.phase) {
        AdvancedApneaPhase.WAITING_FOR_BREATH -> {
            Button(
                onClick = onBreathTaken,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonSuccess,
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    "ONE BREATH TAKEN →",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        AdvancedApneaPhase.WONKA_CRUISING -> {
            WonkaCruisingActions(
                onFirstContraction = onFirstContraction,
                onStop = onStop
            )
        }
        AdvancedApneaPhase.COMPLETE -> {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("Done")
            }
        }
        AdvancedApneaPhase.IDLE -> Unit
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
private fun WonkaCruisingActions(
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onFirstContraction() })
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onFirstContraction,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ButtonDanger,
                contentColor = TextPrimary
            )
        ) {
            Text("LOG CONTRACTION", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "or double-tap anywhere",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("Stop Session")
        }
    }
}

@Composable
private fun ModalityHelpIcon(modality: TrainingModality) {
    var showDialog by remember { mutableStateOf(false) }
    val (title, text) = modality.helpContent()

    IconButton(onClick = { showDialog = true }) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Info: $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(text, style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Got it") }
            }
        )
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun TrainingModality.displayName(): String = when (this) {
    TrainingModality.PROGRESSIVE_O2          -> "Progressive O₂"
    TrainingModality.MIN_BREATH              -> "Min Breath"
    TrainingModality.WONKA_FIRST_CONTRACTION -> "Wonka: Till Contraction"
    TrainingModality.WONKA_ENDURANCE         -> "Wonka: Endurance"
    else                                     -> name
}

private fun TrainingModality.helpContent(): Pair<String, String> = when (this) {
    TrainingModality.PROGRESSIVE_O2 -> "Progressive O₂ Training" to """Purpose: Simultaneously increases both hold and rest duration each round, building aerobic base and mental adaptation.

Formula:
• Hold_n = (30 + (n-1) × 15) seconds
• Rest_n = Hold_n (equal to hold)

Variables:
• n = Round number (1-indexed)

Effect: Both hold and rest grow together, preventing excessive CO₂ buildup while extending hypoxic exposure progressively."""

    TrainingModality.MIN_BREATH -> "Minimum Breath (One-Breath) Training" to """Purpose: Removes the fixed rest timer — you control recovery by signaling when you've taken exactly one breath.

Logic:
• Hold ends → app waits indefinitely
• You take one full exhale + inhale
• Tap "One Breath Taken" → next hold begins immediately

Effect: Trains breath efficiency and mental readiness. Forces you to commit to the next hold with minimal recovery."""

    TrainingModality.WONKA_FIRST_CONTRACTION,
    TrainingModality.WONKA_ENDURANCE -> "Wonka Tables (Contraction-Driven)" to """Purpose: Uses your body's own contraction signals as the training trigger, building awareness of your physiological limits.

Mode 1 — Till First Contraction:
• Timer counts up until you log your first contraction
• Round ends immediately at first contraction
• Trains you to identify your "cruising phase"

Mode 2 — Endurance (+X seconds):
• Timer counts up until first contraction (T_cruise)
• Then counts down X seconds of "struggle phase"
• Total Hold = T_cruise + X
• Formula: T_total = T_cruise + ΔT_endurance

Variables:
• T_cruise = Time from start to first contraction
• ΔT_endurance = User-defined endurance delta (default 45s)"""

    else -> name to ""
}

private fun AdvancedApneaPhase.displayLabel(): String = when (this) {
    AdvancedApneaPhase.IDLE               -> "Ready"
    AdvancedApneaPhase.VENTILATION        -> "Breathe"
    AdvancedApneaPhase.APNEA              -> "Hold"
    AdvancedApneaPhase.WAITING_FOR_BREATH -> "One Breath"
    AdvancedApneaPhase.WONKA_CRUISING     -> "Cruising"
    AdvancedApneaPhase.WONKA_ENDURANCE    -> "Endurance"
    AdvancedApneaPhase.RECOVERY           -> "Recovery"
    AdvancedApneaPhase.COMPLETE           -> "Complete"
}

private fun AdvancedApneaPhase.phaseColor(): androidx.compose.ui.graphics.Color = when (this) {
    AdvancedApneaPhase.IDLE               -> TextSecondary
    AdvancedApneaPhase.VENTILATION        -> ApneaVentilation
    AdvancedApneaPhase.APNEA              -> ApneaHold
    AdvancedApneaPhase.WAITING_FOR_BREATH -> TextSecondary
    AdvancedApneaPhase.WONKA_CRUISING     -> ApneaHold
    AdvancedApneaPhase.WONKA_ENDURANCE    -> TextSecondary
    AdvancedApneaPhase.RECOVERY           -> ApneaRecovery
    AdvancedApneaPhase.COMPLETE           -> TextPrimary
}

private fun formatMmSs(ms: Long): String {
    val totalSecs = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%02d:%02d".format(mins, secs)
}
