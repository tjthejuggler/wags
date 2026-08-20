package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.LungVolume
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ContractionTableMode
import com.example.wags.ui.apnea.forecast.RecordForecastSummary
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Contraction Tables Setup Screen (Till Contraction / Contraction Count)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractionTableScreen(
    navController: NavController,
    viewModel: ContractionTableViewModel = hiltViewModel(),
    eucapnicConfigViewModel: EucapnicConfigViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pastConfigurations by eucapnicConfigViewModel.pastConfigurations.collectAsStateWithLifecycle()
    val timeDimension by viewModel.timeDimension.collectAsStateWithLifecycle()
    val eucapnicConfig by eucapnicConfigViewModel.config.collectAsStateWithLifecycle()

    // Seed-or-mirror the eucapnic config (EucapnicConfigViewModel is the
    // persisted app-wide source of truth).
    LaunchedEffect(state.prepType, eucapnicConfig, state.eucapnicConfig) {
        if (state.prepType != PrepType.EUCAPNIC_DIAPHRAGMATIC.name) return@LaunchedEffect
        val screenConfig = state.eucapnicConfig
        when {
            screenConfig == null && eucapnicConfig != null ->
                viewModel.updateEucapnicConfig(eucapnicConfig)
            screenConfig != null && screenConfig != eucapnicConfig ->
                eucapnicConfigViewModel.updateConfig(screenConfig)
        }
    }

    // Consume the "eucapnic prep completed" result set by EucapnicPacerScreen
    // when the prep finishes and it pops back here. The handle key is cleared
    // immediately so a later return to this screen doesn't resurrect the flag;
    // the START button switches to START HOLD until the table is started.
    LaunchedEffect(Unit) {
        val handle = navController.currentBackStackEntry?.savedStateHandle
        if (handle?.get<Boolean>("eucapnic_prep_completed") == true) {
            handle["eucapnic_prep_completed"] = false
            viewModel.setEucapnicPrepCompleted(true)
        }
    }

    // Reset filters to current settings every time this screen is entered
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.resetFilters()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadSessionHistory()
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Contraction Tables") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text(
                            "←",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    TableHelpIcon(title = "Contraction Tables", text = CONTRACTION_TABLES_HELP_TEXT)
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2, onClick = { navController.navigate(WagsRoutes.SETTINGS) })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Dialog state
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showSongPicker by remember { mutableStateOf(false) }
        var showFilterDialog by remember { mutableStateOf(false) }
        var showEucapnicSettingsDialog by remember { mutableStateOf(false) }
        var showPastConfigurationsDialog by remember { mutableStateOf(false) }
        var showSaveConfigurationDialog by remember { mutableStateOf(false) }
        var saveConfigurationName by remember { mutableStateOf("") }
        var showPastTableConfigsDialog by remember { mutableStateOf(false) }

        // Empty-lung safety warning — shown once per screen entry, before any drill starts
        var showEmptyLungWarning by remember { mutableStateOf(false) }
        var emptyLungWarningShown by remember { mutableStateOf(false) }
        LaunchedEffect(state.lungVolume) {
            if (state.lungVolume == LungVolume.EMPTY.name && !emptyLungWarningShown) {
                emptyLungWarningShown = true
                showEmptyLungWarning = true
            }
        }

        if (showEmptyLungWarning) {
            EmptyLungWarningDialog(onDismiss = { showEmptyLungWarning = false })
        }

        if (showFilterDialog) {
            ProgressiveO2FilterDialog(
                byHour = timeDimension == com.example.wags.domain.model.TimeDimension.BY_HOUR,
                filterLungVolume = state.filterLungVolume,
                filterPrepType = state.filterPrepType,
                filterTimeOfDay = state.filterTimeOfDay,
                filterPosture = state.filterPosture,
                filterAudio = state.filterAudio,
                onLungVolumeChange = { viewModel.setFilterLungVolume(it) },
                onPrepTypeChange = { viewModel.setFilterPrepType(it) },
                onTimeOfDayChange = { viewModel.setFilterTimeOfDay(it) },
                onPostureChange = { viewModel.setFilterPosture(it) },
                onAudioChange = { viewModel.setFilterAudio(it) },
                onReset = { viewModel.resetFilters() },
                onDismiss = { showFilterDialog = false }
            )
        }

        if (showSettingsDialog) {
            FreeHoldSettingsDialog(
                byHour = timeDimension == com.example.wags.domain.model.TimeDimension.BY_HOUR,
                lungVolume = state.lungVolume,
                prepType = state.prepType,
                timeOfDay = state.timeOfDay,
                posture = state.posture,
                audio = state.audio,
                resonancePrepLocked = state.resonancePrepLocked,
                onLungVolumeChange = { viewModel.setLungVolume(it) },
                onPrepTypeChange = { viewModel.setPrepType(it) },
                onTimeOfDayChange = { viewModel.setTimeOfDay(it) },
                onPostureChange = { viewModel.setPosture(it) },
                onAudioChange = { viewModel.setAudio(it) },
                onDismiss = { showSettingsDialog = false }
            )
        }

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
                onDismiss = { showEucapnicSettingsDialog = false },
                pastConfigurations = pastConfigurations,
                onPastConfigurationsClick = { showPastConfigurationsDialog = true }
            )
        }

        if (showPastConfigurationsDialog) {
            PastConfigurationsDialog(
                configurations = pastConfigurations,
                onConfigurationSelected = { entity ->
                    eucapnicConfigViewModel.loadConfiguration(entity)
                    val config = com.example.wags.domain.model.EucapnicConfig(
                        prepDurationSec = entity.prepDurationSec,
                        breathsPerMin = entity.breathsPerMin,
                        inhaleSec = entity.inhaleSec,
                        topPauseSec = entity.topPauseSec,
                        exhaleSec = entity.exhaleSec,
                        bottomPauseSec = entity.bottomPauseSec,
                        breathDepthPercent = entity.breathDepthPercent
                    )
                    viewModel.updateEucapnicConfig(config)
                    showPastConfigurationsDialog = false
                },
                onSaveCurrentClick = {
                    showPastConfigurationsDialog = false
                    showSaveConfigurationDialog = true
                },
                onDismiss = { showPastConfigurationsDialog = false }
            )
        }

        if (showPastTableConfigsDialog) {
            PastTableConfigsDialog(
                configs = state.pastConfigs,
                onSelect = { config ->
                    viewModel.applyPastConfig(config)
                    showPastTableConfigsDialog = false
                },
                onDismiss = { showPastTableConfigsDialog = false }
            )
        }

        if (showSaveConfigurationDialog) {
            AlertDialog(
                onDismissRequest = { showSaveConfigurationDialog = false },
                title = { Text("Save Configuration") },
                text = {
                    TextField(
                        value = saveConfigurationName,
                        onValueChange = { saveConfigurationName = it },
                        label = { Text("Configuration name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.eucapnicConfig?.let {
                                eucapnicConfigViewModel.saveConfiguration(saveConfigurationName)
                            }
                            showSaveConfigurationDialog = false
                            saveConfigurationName = ""
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveConfigurationDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. Settings summary banner — clickable to open settings popup
            ApneaSettingsSummaryBanner(
                lungVolume = state.lungVolume,
                prepType   = state.prepType,
                timeOfDay  = state.timeOfDay,
                posture    = state.posture,
                audio      = state.audio,
                onClick    = { showSettingsDialog = true }
            )

            // 0b. Song picker / connect prompt — shown when MUSIC mode
            if (state.isMusicMode) {
                if (state.spotifyConnected) {
                    if (state.selectedSongs.isNotEmpty()) {
                        SelectedSongBanner(tracks = state.selectedSongs) {
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

            // 0c. Guided audio picker — shown when GUIDED mode
            var showGuidedPicker by remember { mutableStateOf(false) }
            if (state.isGuidedMode) {
                if (state.guidedSelectedName.isNotBlank()) {
                    SelectedGuidedAudioBanner(name = state.guidedSelectedName)
                }
                GuidedAudioPickerButton(onClick = { showGuidedPicker = true })
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

            // 0f. Eucapnic Diaphragmatic Breathing settings
            if (state.prepType == PrepType.EUCAPNIC_DIAPHRAGMATIC.name && state.eucapnicConfig != null) {
                EucapnicSettingsButton(
                    config = state.eucapnicConfig!!,
                    onClick = { showEucapnicSettingsDialog = true }
                )
            }

            // 0e. Record-breaking forecast
            RecordForecastSummary(
                forecast = state.recordForecast,
                showAutoSet = false
            )

            // 1. Explanation card (mode-aware)
            ExplanationCard(mode = state.mode)

            // 2. Mode selector
            ModeSelector(
                selectedMode = state.mode,
                onModeChange = { viewModel.setMode(it) }
            )

            // 2b. Table configuration
            TableConfigSection(
                state = state,
                onSetRounds = { viewModel.setRounds(it) },
                onSetRestStart = { viewModel.setRestStartSec(it) },
                onSetRestEnd = { viewModel.setRestEndSec(it) },
                onSetTarget = { viewModel.setContractionTarget(it) },
                onPastConfigsClick = { showPastTableConfigsDialog = true }
            )

            // 2c. Hyperventilation advisory — hyperventilation delays contractions
            //     and can push the hold deep into hypoxia before any warning sign.
            if (state.isHyperPrep) {
                HyperPrepAdvisoryCard()
            }

            // 3. Voice / vibration toggles
            VoiceVibrationToggles(
                voiceEnabled = state.voiceEnabled,
                vibrationEnabled = state.vibrationEnabled,
                onVoiceToggle = { viewModel.setVoiceEnabled(it) },
                onVibrationToggle = { viewModel.setVibrationEnabled(it) }
            )

            // 4. Start button
            val isEucapnicPrep = state.prepType == PrepType.EUCAPNIC_DIAPHRAGMATIC.name &&
                    state.eucapnicConfig != null

            Button(
                onClick = {
                    if (isEucapnicPrep) {
                        if (state.eucapnicPrepCompleted) {
                            // Eucapnic prep already done — start the table. Consume
                            // the flag so the next table begins with a fresh prep.
                            viewModel.setEucapnicPrepCompleted(false)
                            navController.navigate(WagsRoutes.CONTRACTION_TABLE_ACTIVE)
                        } else {
                            val config = state.eucapnicConfig!!
                            navController.navigate(
                                WagsRoutes.eucapnicPacer(
                                    lungVolume = state.lungVolume,
                                    timeOfDay = state.timeOfDay,
                                    posture = state.posture,
                                    audio = state.audio,
                                    sessionType = state.mode.tableType(),
                                    prepDurationSec = config.prepDurationSec,
                                    breathsPerMin = config.breathsPerMin,
                                    inhaleSec = config.inhaleSec,
                                    topPauseSec = config.topPauseSec,
                                    exhaleSec = config.exhaleSec,
                                    bottomPauseSec = config.bottomPauseSec,
                                    breathDepthPercent = config.breathDepthPercent
                                )
                            )
                        }
                    } else {
                        navController.navigate(WagsRoutes.CONTRACTION_TABLE_ACTIVE)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text(
                    when {
                        isEucapnicPrep && state.eucapnicPrepCompleted -> "START HOLD"
                        isEucapnicPrep -> "START EUCAPNIC"
                        else -> "Start"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 4b. Personal Bests button
            OutlinedButton(
                onClick = {
                    navController.navigate(
                        WagsRoutes.personalBests(
                            drillType = state.mode.tableType(),
                            drillParamValue = if (state.mode == ContractionTableMode.CONTRACTION_COUNT) state.contractionTarget else null
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TextSecondary)
            ) {
                Text(
                    "🏆  Personal Bests",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
            }

            // 5. Past sessions
            val isFiltered = state.filterLungVolume.isNotEmpty()
                    || state.filterPrepType.isNotEmpty()
                    || state.filterTimeOfDay.isNotEmpty()
                    || state.filterPosture.isNotEmpty()
                    || state.filterAudio.isNotEmpty()
            PastSessionsSection(
                history = state.pastSessions,
                filterSummary = buildContractionTableFilterSummary(state),
                isFiltered = isFiltered,
                onViewSessionDetail = { recordId ->
                    navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                },
                onFilterClick = { showFilterDialog = true },
                onClearAllFilters = { viewModel.clearAllFilters() }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Explanation Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExplanationCard(mode: ContractionTableMode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = if (mode == ContractionTableMode.TILL_CONTRACTION) {
                "Till Contraction builds CO₂ tolerance and interoceptive awareness. " +
                    "Hold until your diaphragm contracts for the first time, then breathe. " +
                    "The easy phase before that first contraction (your \"cruise\") is the " +
                    "metric — watch how it decays across rounds as CO₂ accumulates."
            } else {
                "Contraction Count builds struggle-phase endurance. After your first " +
                    "diaphragmatic contraction, keep holding and tap the button for each " +
                    "further contraction until you reach the target. The target self-scales " +
                    "the difficulty — no fixed durations needed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode Selector
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeSelector(
    selectedMode: ContractionTableMode,
    onModeChange: (ContractionTableMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModeCard(
            modifier = Modifier.weight(1f),
            title = "Till Contraction",
            subtitle = "Hold until the first contraction",
            selected = selectedMode == ContractionTableMode.TILL_CONTRACTION,
            onClick = { onModeChange(ContractionTableMode.TILL_CONTRACTION) }
        )
        ModeCard(
            modifier = Modifier.weight(1f),
            title = "Contraction Count",
            subtitle = "Hold for N contractions",
            selected = selectedMode == ContractionTableMode.CONTRACTION_COUNT,
            onClick = { onModeChange(ContractionTableMode.CONTRACTION_COUNT) }
        )
    }
}

@Composable
private fun ModeCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SurfaceVariant.copy(alpha = 0.5f) else SurfaceDark)
            .then(
                if (selected) Modifier.border(
                    width = 1.dp,
                    color = ButtonPrimary,
                    shape = RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) TextPrimary else TextSecondary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Table Configuration
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TableConfigSection(
    state: ContractionTableUiState,
    onSetRounds: (Int) -> Unit,
    onSetRestStart: (Int) -> Unit,
    onSetRestEnd: (Int) -> Unit,
    onSetTarget: (Int) -> Unit,
    onPastConfigsClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Table Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            ConfigStepper(
                label = "Rounds",
                valueText = "${state.rounds}",
                onDecrement = { onSetRounds(state.rounds - 1) },
                onIncrement = { onSetRounds(state.rounds + 1) },
                canDecrement = state.rounds > ContractionTableViewModel.MIN_ROUNDS,
                canIncrement = state.rounds < ContractionTableViewModel.MAX_ROUNDS
            )

            ConfigStepper(
                label = "First rest",
                valueText = "${state.restStartSec}s",
                onDecrement = { onSetRestStart(state.restStartSec - 15) },
                onIncrement = { onSetRestStart(state.restStartSec + 15) },
                canDecrement = state.restStartSec > ContractionTableViewModel.MIN_REST_SEC,
                canIncrement = state.restStartSec < ContractionTableViewModel.MAX_REST_SEC
            )

            ConfigStepper(
                label = "Final rest",
                valueText = "${state.restEndSec}s",
                onDecrement = { onSetRestEnd(state.restEndSec - 15) },
                onIncrement = { onSetRestEnd(state.restEndSec + 15) },
                canDecrement = state.restEndSec > ContractionTableViewModel.MIN_REST_SEC,
                canIncrement = state.restEndSec < ContractionTableViewModel.MAX_REST_SEC
            )

            if (state.mode == ContractionTableMode.CONTRACTION_COUNT) {
                ConfigStepper(
                    label = "Contractions per hold",
                    valueText = "${state.contractionTarget}",
                    onDecrement = { onSetTarget(state.contractionTarget - 1) },
                    onIncrement = { onSetTarget(state.contractionTarget + 1) },
                    canDecrement = state.contractionTarget > ContractionTableViewModel.MIN_TARGET,
                    canIncrement = state.contractionTarget < ContractionTableViewModel.MAX_TARGET
                )
            }

            // Past configurations — quick restore of a setup used before
            if (state.pastConfigs.isNotEmpty()) {
                OutlinedButton(
                    onClick = onPastConfigsClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TextSecondary)
                ) {
                    Text(
                        "🕘  Past Configurations",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                }
            }

            // Rest schedule preview
            Text(
                text = if (state.restStartSec == state.restEndSec) {
                    "Fixed ${state.restStartSec}s rest between every round"
                } else {
                    "Rest decreases ${state.restStartSec}s → ${state.restEndSec}s across ${state.rounds} rounds"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Personal best for the current config + settings.
            // Till Contraction's record metric is the average hold across the
            // table's holds — keep the label explicit about that.
            state.personalBestCurrentSettingsMs?.let { best ->
                Text(
                    text = if (state.mode == ContractionTableMode.TILL_CONTRACTION) {
                        "Record · avg hold (these settings): ${formatMs(best)}"
                    } else {
                        "Best (these settings): ${formatMs(best)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun ConfigStepper(
    label: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onDecrement,
                enabled = canDecrement,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled
                ),
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 64.dp)
            )
            FilledTonalButton(
                onClick = onIncrement,
                enabled = canIncrement,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled
                ),
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hyper prep advisory
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HyperPrepAdvisoryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠️", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Hyperventilation prep delays your contractions and suppresses the " +
                    "urge to breathe — with contraction-driven tables you may slide deeper " +
                    "into hypoxia than you realise. Keep counts conservative and never " +
                    "hyperventilate before empty-lung holds.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Past Sessions
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PastSessionsSection(
    history: List<ContractionTableHistoryEntry>,
    filterSummary: String,
    isFiltered: Boolean = false,
    onViewSessionDetail: (Long) -> Unit,
    onFilterClick: () -> Unit = {},
    onClearAllFilters: () -> Unit = {}
) {
    // Header row with title + filter buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Past Sessions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isFiltered) {
                OutlinedButton(
                    onClick = onClearAllFilters,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, ButtonPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ButtonPrimary)
                ) {
                    Text(
                        text = "All",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
            OutlinedButton(
                onClick = onFilterClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, TextSecondary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text(
                    text = filterSummary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (history.isEmpty()) {
        Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            history.take(15).forEach { item ->
                val dateFormat = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .clickable { onViewSessionDetail(item.recordId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (item.mode == ContractionTableMode.TILL_CONTRACTION) "Till Contraction" else "Contraction Count",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = buildString {
                                append(item.configLabel)
                                if (item.partial) append("  ·  partial")
                                append("  ·  ").append(dateFormat.format(Date(item.timestamp)))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (item.mode == ContractionTableMode.TILL_CONTRACTION) {
                                "Best cruise ${formatMs(item.bestCruiseMs ?: 0L)}"
                            } else {
                                "Total ${formatMs(item.totalHoldMs)}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Build a short label describing the current filter combination. */
fun buildContractionTableFilterSummary(state: ContractionTableUiState): String {
    val parts = mutableListOf<String>()
    if (state.filterLungVolume.isNotEmpty()) parts.add(
        when (state.filterLungVolume) {
            "PARTIAL" -> "Half"
            else -> state.filterLungVolume.lowercase().replaceFirstChar { it.uppercase() }
        }
    )
    if (state.filterPrepType.isNotEmpty()) parts.add(
        runCatching { PrepType.valueOf(state.filterPrepType).displayName() }
            .getOrDefault(state.filterPrepType)
    )
    if (state.filterTimeOfDay.isNotEmpty()) parts.add(
        runCatching { TimeOfDay.valueOf(state.filterTimeOfDay).displayName() }
            .getOrDefault(state.filterTimeOfDay)
    )
    if (state.filterPosture.isNotEmpty()) parts.add(
        runCatching { Posture.valueOf(state.filterPosture).displayName() }
            .getOrDefault(state.filterPosture)
    )
    if (state.filterAudio.isNotEmpty()) parts.add(
        runCatching { AudioSetting.valueOf(state.filterAudio).displayName() }
            .getOrDefault(state.filterAudio)
    )
    return if (parts.isEmpty()) "All Sessions" else parts.joinToString(" · ")
}

// ─────────────────────────────────────────────────────────────────────────────
// Past Configurations dialog (table setups used in past sessions)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Modal dialog listing every distinct table configuration the user has trained
 * with, most recent first. Tapping a card applies that configuration to the
 * current setup (switching mode when needed) and closes the dialog.
 */
@Composable
private fun PastTableConfigsDialog(
    configs: List<ContractionTablePastConfig>,
    onSelect: (ContractionTablePastConfig) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = MaterialTheme.shapes.large,
            color = SurfaceDark,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ─────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Past Configurations",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = TextSecondary)
                    }
                }

                // ── Configuration list ──────────────────────────────────────
                if (configs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No past configurations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = configs,
                            key = { "${it.mode.name}|${it.rounds}|${it.restStartSec}|${it.restEndSec}|${it.contractionTarget}" }
                        ) { config ->
                            PastTableConfigCard(
                                config = config,
                                onClick = { onSelect(config) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PastTableConfigCard(
    config: ContractionTablePastConfig,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = if (config.mode == ContractionTableMode.TILL_CONTRACTION) "Till Contraction" else "Contraction Count",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = config.summary,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Used ${config.useCount}×  ·  last ${dateFormat.format(Date(config.lastUsedMs))}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }
    }
}
