package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaTableScreen(
    navController: NavController,
    tableType: String,
    viewModel: ApneaViewModel = hiltViewModel(),
    eucapnicConfigViewModel: EucapnicConfigViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val effectiveTod by viewModel.effectiveTod.collectAsStateWithLifecycle()
    val parsedType = runCatching { ApneaTableType.valueOf(tableType) }.getOrDefault(ApneaTableType.O2)
    val eucapnicConfig by eucapnicConfigViewModel.config.collectAsStateWithLifecycle()

    // Seed-or-mirror the eucapnic config (EucapnicConfigViewModel is the
    // persisted app-wide source of truth). Seeds this screen's ViewModel when
    // it has no config yet; mirrors dialog edits back so they persist and are
    // shared across screens. The old unconditional push reset the user's
    // config to the default on every recomposition (e.g. returning from the
    // eucapnic pacer).
    LaunchedEffect(state.prepType, eucapnicConfig, state.eucapnicConfig) {
        if (state.prepType != PrepType.EUCAPNIC_DIAPHRAGMATIC) return@LaunchedEffect
        val screenConfig = state.eucapnicConfig
        when {
            screenConfig == null && eucapnicConfig != null ->
                viewModel.updateEucapnicConfig(eucapnicConfig)
            screenConfig != null && screenConfig != eucapnicConfig ->
                eucapnicConfigViewModel.updateConfig(screenConfig)
        }
    }

    val isActive = state.apneaState != ApneaState.IDLE && state.apneaState != ApneaState.COMPLETE

    // Keep screen on during COMPLETE too so the user can review results
    val keepScreenOn = isActive || state.apneaState == ApneaState.COMPLETE

    SessionBackHandler(enabled = isActive) {
        viewModel.cancelTableSession()
        // Pop back to the advanced apnea screen (main screen for tables)
        navController.popBackStack("advanced_apnea", inclusive = false)
    }
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
                    IconButton(onClick = {
                        if (isActive) viewModel.cancelTableSession()
                        // Pop back to the advanced apnea screen (main screen for tables)
                        navController.popBackStack("advanced_apnea", inclusive = false)
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    val hasSensorData = LiveSensorActionsNav(navController)
                    if (!hasSensorData) {
                        IconButton(onClick = { navController.navigate(WagsRoutes.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }
        // Guided audio picker dialog state
        var showGuidedPicker by remember { mutableStateOf(false) }
        // Eucapnic settings dialog state
        var showEucapnicSettingsDialog by remember { mutableStateOf(false) }

        if (showSongPicker) {
            SongPickerDialog(
                songs = state.previousSongs,
                isLoading = state.loadingSongs,
                selectedSongs = state.selectedSongs,
                loadingSelectedSong = state.loadingSelectedSong,
                onSongSelected = { track -> viewModel.selectSong(track) },
                onRefresh = { viewModel.loadPreviousSongs(forceRefresh = true) },
                onDismiss = { showSongPicker = false }
            )
        }

        // Ask the user to confirm the song actually loaded in Spotify (with retry)
        viewModel.preloadConfirm.collectAsState().value?.let { req ->
            SpotifyPreloadConfirmDialog(
                title = req.title,
                artist = req.artist,
                attempt = req.attempt,
                onConfirmed = { viewModel.confirmPreloadLoaded() },
                onRetry = { viewModel.retryPreload() }
            )
        }

        if (showGuidedPicker) {
            LaunchedEffect(Unit) { viewModel.loadGuidedCompletionStatuses() }
            GuidedAudioPickerDialog(
                audios = state.guidedAudios,
                selectedId = state.guidedSelectedId,
                completionStatuses = state.guidedCompletionStatuses,
                onSelect = { audio -> viewModel.selectGuidedAudio(audio) },
                onAddNew = { uri, name, url -> viewModel.addGuidedAudio(uri, name, url) },
                onDelete = { audio -> viewModel.deleteGuidedAudio(audio) },
                onDismiss = { showGuidedPicker = false }
            )
        }

        // Eucapnic settings dialog
        if (showEucapnicSettingsDialog && state.eucapnicConfig != null) {
            EucapnicSettingsDialog(
                config = state.eucapnicConfig!!,
                onPrepDurationChange = { duration ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(prepDurationSec = duration))
                },
                onBpmChange = { bpm ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(breathsPerMin = bpm))
                },
                onInhaleChange = { inhale ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(inhaleSec = inhale))
                },
                onTopPauseChange = { topPause ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(topPauseSec = topPause))
                },
                onExhaleChange = { exhale ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(exhaleSec = exhale))
                },
                onBottomPauseChange = { bottomPause ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(bottomPauseSec = bottomPause))
                },
                onBreathDepthChange = { depth ->
                    viewModel.updateEucapnicConfig(state.eucapnicConfig!!.copy(breathDepthPercent = depth))
                },
                onDismiss = { showEucapnicSettingsDialog = false }
            )
        }

        if (state.personalBestMs <= 0L) {
            NoPbContent(modifier = Modifier.padding(padding))
        } else {
            // State for table steps scroll position
            val tableStepsListState = rememberLazyListState()
            
            // Auto-scroll to active step when currentRound changes
            LaunchedEffect(state.currentRound, state.apneaState) {
                state.currentTable?.let { table ->
                    val activeIndex = table.steps.indexOfFirst {
                        it.roundNumber == state.currentRound
                    }
                    if (activeIndex >= 0 && state.apneaState != ApneaState.IDLE) {
                        tableStepsListState.animateScrollToItem(activeIndex)
                    }
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Settings summary — pinned below the top bar so it stays visible
                // while scrolling (label only; settings are chosen on the main apnea screen)
                ApneaSettingsSummaryBanner(
                    lungVolume = state.selectedLungVolume,
                    prepType   = state.prepType.name,
                    // Dimension-aware bucket: hour number in BY_HOUR mode, Morning/Day/Night otherwise.
                    timeOfDay  = effectiveTod,
                    posture    = state.posture.name,
                    audio      = state.audio.name
                )

                // Upper content section - scrolls separately. During an active
                // session it gets the lion's share of the screen (big countdown
                // + first-contraction button) while the table steps shrink.
                LazyColumn(
                    modifier = Modifier
                        .weight(if (isActive) 0.7f else 1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Live device reading — shown while configuring so the user
                    // can verify the sensor is streaming before starting
                    if (state.apneaState == ApneaState.IDLE) {
                        item {
                            TableLiveReadingBanner(
                                liveHr = state.liveHr,
                                liveSpO2 = state.liveSpO2
                            )
                        }
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
                    // End-of-session summary — shown once the table is complete
                    if (state.apneaState == ApneaState.COMPLETE) {
                        item {
                            TableSessionSummaryCard(uiState = state)
                        }
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
                    // Selected-song banner doubles as the picker trigger, so banner and
                    // choose-button are never visible at the same time.
                    if (state.audio == AudioSetting.MUSIC && state.apneaState == ApneaState.IDLE) {
                        item {
                            if (state.spotifyConnected) {
                                if (state.selectedSongs.isNotEmpty()) {
                                    SelectedSongBanner(
                                        tracks = state.selectedSongs,
                                        onClear = { viewModel.clearSelectedSong() },
                                        onClick = {
                                            viewModel.loadPreviousSongs()
                                            showSongPicker = true
                                        }
                                    )
                                } else {
                                    SongPickerButton(onClick = {
                                        viewModel.loadPreviousSongs()
                                        showSongPicker = true
                                    })
                                }
                            } else {
                                SpotifyConnectPrompt(
                                    onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }
                                )
                            }
                        }
                    }
                    // Guided audio picker — shown when GUIDED is selected, session not active
                    // Selected-audio banner doubles as the picker trigger.
                    if (state.audio == AudioSetting.GUIDED && state.apneaState == ApneaState.IDLE) {
                        item {
                            if (state.guidedSelectedName.isNotBlank()) {
                                SelectedGuidedAudioBanner(
                                    name = state.guidedSelectedName,
                                    onClick = { showGuidedPicker = true }
                                )
                            } else {
                                GuidedAudioPickerButton(onClick = {
                                    showGuidedPicker = true
                                })
                            }
                        }
                    }
                    // Voice / vibration toggles — shown while configuring (pre-start)
                    if (state.apneaState == ApneaState.IDLE) {
                        item {
                            VoiceVibrationToggles(
                                voiceEnabled = state.voiceEnabled,
                                vibrationEnabled = state.vibrationEnabled,
                                onVoiceToggle = { viewModel.setVoiceEnabled(it) },
                                onVibrationToggle = { viewModel.setVibrationEnabled(it) }
                            )
                        }
                    }
                    // Eucapnic Diaphragmatic Breathing settings — shown when prep is EUCAPNIC_DIAPHRAGMATIC and session is not active
                    if (state.apneaState == ApneaState.IDLE &&
                        state.prepType == PrepType.EUCAPNIC_DIAPHRAGMATIC &&
                        state.eucapnicConfig != null) {
                        item {
                            EucapnicSettingsButton(
                                config = state.eucapnicConfig!!,
                                onClick = { showEucapnicSettingsDialog = true }
                            )
                        }
                    }
                    item {
                        SessionControlRow(
                            apneaState = state.apneaState,
                            onStart = {
                                if (state.prepType == PrepType.EUCAPNIC_DIAPHRAGMATIC && state.eucapnicConfig != null) {
                                    // Navigate to eucapnic pacer screen with the current config
                                    val config = state.eucapnicConfig!!
                                    navController.navigate(
                                        WagsRoutes.eucapnicPacer(
                                            lungVolume = state.selectedLungVolume,
                                            timeOfDay = state.timeOfDay.name,
                                            posture = state.posture.name,
                                            audio = state.audio.name,
                                            sessionType = "TABLE_${parsedType.name}",
                                            prepDurationSec = config.prepDurationSec,
                                            breathsPerMin = config.breathsPerMin,
                                            inhaleSec = config.inhaleSec,
                                            topPauseSec = config.topPauseSec,
                                            exhaleSec = config.exhaleSec,
                                            bottomPauseSec = config.bottomPauseSec,
                                            breathDepthPercent = config.breathDepthPercent
                                        )
                                    )
                                } else {
                                    viewModel.loadTable(parsedType)
                                    viewModel.startTableSession()
                                }
                            },
                            onStop = { viewModel.stopTableSession() },
                            onDone = {
                                // End everything (audio, state machine → IDLE) and leave
                                viewModel.stopTableSession()
                                navController.popBackStack("advanced_apnea", inclusive = false)
                            }
                        )
                    }
                }
                
                // Table steps section - separate scrolling with auto-scroll.
                // Shrinks to a compact "upcoming" strip while the session runs.
                state.currentTable?.let { table ->
                    Column(
                        modifier = Modifier
                            .weight(if (isActive) 0.3f else 1f)
                            .fillMaxWidth()
                    ) {
                        Text(
                            if (isActive) "Upcoming"
                            else "Table Steps (PB: ${table.personalBestMs / 1000L}s)",
                            style = if (isActive) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp)
                        )
                        LazyColumn(
                            state = tableStepsListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(
                                if (isActive) 4.dp else 8.dp
                            )
                        ) {
                            itemsIndexed(table.steps) { index, step ->
                                TableStepRow(
                                    step = step,
                                    isActive = state.apneaState != ApneaState.IDLE &&
                                            state.currentRound == step.roundNumber,
                                    isComplete = state.currentRound > step.roundNumber,
                                    isEditable = state.apneaState == ApneaState.IDLE,
                                    compact = isActive || state.apneaState == ApneaState.COMPLETE,
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
            .height(128.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF555555)
        )
    ) {
        Text(
            "First Contraction",
            style = MaterialTheme.typography.headlineMedium,
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
    onStop: () -> Unit,
    onDone: () -> Unit = {}
) {
    when (apneaState) {
        ApneaState.COMPLETE -> {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("Done")
            }
        }
        ApneaState.IDLE -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("Start Session")
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
    compact: Boolean = false,
    onHoldChanged: (Long) -> Unit,
    onBreathChanged: (Long) -> Unit
) {
    val containerColor = when {
        isActive -> SurfaceVariant
        isComplete -> SurfaceDark.copy(alpha = 0.5f)
        else -> SurfaceDark
    }

    // Compact single-line row used while the session runs / after it completes —
    // keeps the upcoming holds/breaths list visually secondary to the timer
    // and the first-contraction button.
    if (compact) {
        Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "R${step.roundNumber}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isComplete) TextDisabled else TextSecondary
                )
                Text(
                    "Hold ${step.apneaDurationMs / 1000L}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isActive -> ApneaHold
                        isComplete -> TextDisabled
                        else -> TextPrimary
                    }
                )
                Text(
                    "Breath ${step.ventilationDurationMs / 1000L}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isActive -> ApneaVentilation
                        isComplete -> TextDisabled
                        else -> TextSecondary
                    }
                )
                if (isActive) {
                    Text("▶", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                } else if (isComplete) {
                    Text("✓", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                }
            }
        }
        return
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

/** End-of-table summary shown in place of the old "Restart" flow. */
@Composable
private fun TableSessionSummaryCard(uiState: ApneaUiState) {
    val table = uiState.currentTable ?: return
    val steps = table.steps
    val totalHoldMs = steps.sumOf { it.apneaDurationMs }
    val longestHoldMs = steps.maxOf { it.apneaDurationMs }
    val totalSessionMs = steps.sumOf { it.apneaDurationMs + it.ventilationDurationMs }
    val fcMap = uiState.roundFirstContractions
    val avgCruisingMs = fcMap.values.takeIf { it.isNotEmpty() }?.average()
    val avgStruggleMs = fcMap.entries
        .mapNotNull { (round, fc) ->
            steps.firstOrNull { it.roundNumber == round }
                ?.takeIf { it.apneaDurationMs > fc }
                ?.let { it.apneaDurationMs - fc }
        }
        .takeIf { it.isNotEmpty() }?.average()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "${table.type.name} Table Complete",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            HorizontalDivider(color = SurfaceDark)
            SummaryStatRow("Rounds completed", "${uiState.currentRound} / ${uiState.totalRounds}")
            SummaryStatRow("Total hold time", formatTableMmSs(totalHoldMs))
            SummaryStatRow("Longest hold", formatTableMmSs(longestHoldMs))
            SummaryStatRow("Total session time", formatTableMmSs(totalSessionMs))
            avgCruisingMs?.let {
                SummaryStatRow("Avg cruising (→ 1st contraction)", formatTableMmSs(it.toLong()))
            }
            avgStruggleMs?.let {
                SummaryStatRow("Avg struggle (contraction → end)", formatTableMmSs(it.toLong()))
            }
            if (fcMap.isNotEmpty()) {
                Text(
                    "First contractions",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    fcMap.toSortedMap().entries.joinToString("   ") { (round, ms) ->
                        "R$round ${formatTableMmSs(ms)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun SummaryStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
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
