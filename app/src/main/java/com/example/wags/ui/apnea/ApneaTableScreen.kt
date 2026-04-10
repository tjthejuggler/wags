package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
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
import com.example.wags.ui.navigation.WagsRoutes
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
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Settings summary
                    item {
                        ApneaSettingsSummaryBanner(
                            lungVolume = state.selectedLungVolume,
                            prepType   = state.prepType.name,
                            timeOfDay  = state.timeOfDay.name,
                            posture    = state.posture.name,
                            audio      = state.audio.name
                        )
                    }
                    // Hyperventilating advice
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
                    // First Contraction button — shown during APNEA phase, hidden once tapped
                    if (state.apneaState == ApneaState.APNEA && !state.firstContractionTappedThisRound) {
                        item {
                            FirstContractionButton(
                                onTap = { viewModel.logFirstContraction() }
                            )
                        }
                    }
                    // Show first contraction time after it's been tapped
                    if (state.apneaState == ApneaState.APNEA && state.firstContractionTappedThisRound) {
                        item {
                            FirstContractionConfirmation(
                                elapsedMs = state.firstContractionElapsedMs ?: 0L
                            )
                        }
                    }
                    item {
                        TableContractionSummaryCard(uiState = state)
                    }
                    // Song picker / connect prompt — shown when MUSIC is selected, session not active
                    if (state.audio == AudioSetting.MUSIC && state.apneaState == ApneaState.IDLE) {
                        item {
                            if (state.spotifyConnected) {
                                if (state.selectedSong != null) {
                                    SelectedSongBanner(track = state.selectedSong!!) {
                                        viewModel.clearSelectedSong()
                                    }
                                }
                                SongPickerButton(onClick = {
                                    viewModel.loadPreviousSongs()
                                    showSongPicker = true
                                })
                            } else {
                                SpotifyConnectPrompt(
                                    onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }
                                )
                            }
                        }
                    }
                    // Voice / vibration toggles — shown when session is not active
                    if (state.apneaState == ApneaState.IDLE || state.apneaState == ApneaState.COMPLETE) {
                        item {
                            VoiceVibrationToggles(
                                voiceEnabled = state.voiceEnabled,
                                vibrationEnabled = state.vibrationEnabled,
                                onVoiceToggle = { viewModel.setVoiceEnabled(it) },
                                onVibrationToggle = { viewModel.setVibrationEnabled(it) }
                            )
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
                                isComplete = state.currentRound > step.roundNumber,
                                isEditable = state.apneaState == ApneaState.IDLE,
                                onHoldChanged = { newSec ->
                                    viewModel.updateTableStep(step.roundNumber, newHoldMs = newSec * 1000L)
                                },
                                onBreathChanged = { newSec ->
                                    viewModel.updateTableStep(step.roundNumber, newBreathMs = newSec * 1000L)
                                }
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
            val displayName = when (apneaState) {
                ApneaState.APNEA -> "HOLD"
                ApneaState.VENTILATION -> "BREATH"
                else -> apneaState.name
            }
            Text(
                text = displayName,
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

/** Large "First Contraction" button shown during each hold phase. */
@Composable
private fun FirstContractionButton(onTap: () -> Unit) {
    Button(
        onClick = onTap,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF555555)
        )
    ) {
        Text(
            "First Contraction",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
    }
}

/** Confirmation shown after the first contraction button is tapped. */
@Composable
private fun FirstContractionConfirmation(elapsedMs: Long) {
    val secs = elapsedMs / 1000L
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "First contraction at ${secs}s ✓",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFCCCCCC)
            )
        }
    }
}

/** Contraction summary shown during VENTILATION phase (between holds). */
@Composable
private fun TableContractionSummaryCard(uiState: ApneaUiState) {
    if (uiState.apneaState != ApneaState.VENTILATION) return

    val firstMs = uiState.firstContractionElapsedMs
    val holdMs = uiState.lastHoldDurationMs

    val cruising = if (firstMs != null) formatTableMmSs(firstMs) else "—"
    val struggle = if (firstMs != null && holdMs > 0L) formatTableMmSs(holdMs - firstMs) else "—"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Cruising", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                Text(cruising, style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Struggle", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
                Text(struggle, style = MaterialTheme.typography.bodyMedium)
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
    isComplete: Boolean,
    isEditable: Boolean,
    onHoldChanged: (Long) -> Unit,
    onBreathChanged: (Long) -> Unit
) {
    val containerColor = when {
        isActive -> SurfaceVariant
        isComplete -> SurfaceDark.copy(alpha = 0.5f)
        else -> SurfaceDark
    }

    var editingHold by remember { mutableStateOf(false) }
    var editingBreath by remember { mutableStateOf(false) }
    var holdInput by remember(step.apneaDurationMs) {
        mutableStateOf((step.apneaDurationMs / 1000L).toString())
    }
    var breathInput by remember(step.ventilationDurationMs) {
        mutableStateOf((step.ventilationDurationMs / 1000L).toString())
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
                // Hold column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hold", style = MaterialTheme.typography.labelMedium)
                        InfoHelpBubble(
                            title = HOLD_HELP_TITLE,
                            content = HOLD_HELP_CONTENT
                        )
                    }
                    if (isEditable && editingHold) {
                        OutlinedTextField(
                            value = holdInput,
                            onValueChange = { holdInput = it },
                            modifier = Modifier.width(64.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Row {
                            TextButton(onClick = {
                                holdInput.toLongOrNull()?.let { onHoldChanged(it) }
                                editingHold = false
                            }) { Text("✓", color = TextPrimary) }
                            TextButton(onClick = {
                                holdInput = (step.apneaDurationMs / 1000L).toString()
                                editingHold = false
                            }) { Text("✗", color = TextSecondary) }
                        }
                    } else {
                        Text(
                            "${step.apneaDurationMs / 1000L}s",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) ApneaHold else TextPrimary,
                            modifier = if (isEditable) Modifier.let { mod ->
                                mod.then(
                                    Modifier.padding(4.dp)
                                )
                            } else Modifier
                        )
                        if (isEditable) {
                            TextButton(
                                onClick = { editingHold = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("edit", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }
                // Breath column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Breath", style = MaterialTheme.typography.labelMedium)
                        InfoHelpBubble(
                            title = BREATH_HELP_TITLE,
                            content = BREATH_HELP_CONTENT
                        )
                    }
                    if (isEditable && editingBreath) {
                        OutlinedTextField(
                            value = breathInput,
                            onValueChange = { breathInput = it },
                            modifier = Modifier.width(64.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Row {
                            TextButton(onClick = {
                                breathInput.toLongOrNull()?.let { onBreathChanged(it) }
                                editingBreath = false
                            }) { Text("✓", color = TextPrimary) }
                            TextButton(onClick = {
                                breathInput = (step.ventilationDurationMs / 1000L).toString()
                                editingBreath = false
                            }) { Text("✗", color = TextSecondary) }
                        }
                    } else {
                        Text(
                            "${step.ventilationDurationMs / 1000L}s",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) ApneaVentilation else TextSecondary
                        )
                        if (isEditable) {
                            TextButton(
                                onClick = { editingBreath = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("edit", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
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
    ApneaState.RECOVERY -> ApneaVentilation  // fallback — recovery no longer used in tables
    ApneaState.COMPLETE -> TextPrimary
}

private fun formatTableMmSs(ms: Long): String {
    val totalSecs = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}

private const val BREATH_HELP_TITLE = "Breath Phase"
private const val BREATH_HELP_CONTENT = """
Purpose: Recovery breathing between holds. Allows CO₂ to clear and O₂ to replenish.

In CO₂ Tables: Breath time decreases each round to build CO₂ tolerance.
Formula: B_n = B₁ - ((n-1) × ΔB)
• B₁ = Initial breath time (equals hold time)
• ΔB = (B₁ - B_min) / (N-1)
• n = Current round number

In O₂ Tables: Breath time is fixed (60s) to allow O₂ recovery.
"""

private const val HOLD_HELP_TITLE = "Hold Phase (Apnea)"
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
