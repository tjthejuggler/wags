package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.LungVolume
import com.example.wags.domain.model.PrepType
import com.example.wags.ui.apnea.forecast.RecordForecastSummary
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Progressive O₂ Setup Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveO2Screen(
    navController: NavController,
    viewModel: ProgressiveO2ViewModel = hiltViewModel(),
    eucapnicConfigViewModel: EucapnicConfigViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pastConfigurations by eucapnicConfigViewModel.pastConfigurations.collectAsStateWithLifecycle()
    val timeDimension by viewModel.timeDimension.collectAsStateWithLifecycle()
    val effectiveTod by viewModel.effectiveTod.collectAsStateWithLifecycle()
    val eucapnicConfig by eucapnicConfigViewModel.config.collectAsStateWithLifecycle()

    // Seed-or-mirror the eucapnic config (EucapnicConfigViewModel is the
    // persisted app-wide source of truth). Seeds this screen's ViewModel when
    // it has no config yet; mirrors dialog edits back so they persist and are
    // shared across screens. The old unconditional push reset the user's
    // config to the default on every recomposition (e.g. returning from the
    // eucapnic pacer).
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
    // the START button switches to START HOLD until the session is started.
    LaunchedEffect(Unit) {
        val handle = navController.currentBackStackEntry?.savedStateHandle
        if (handle?.get<Boolean>("eucapnic_prep_completed") == true) {
            handle["eucapnic_prep_completed"] = false
            viewModel.setEucapnicPrepCompleted(true)
        }
    }

    // Sync the history filters to the current settings on entry, but keep the
    // user's filter edits when returning from a record detail screen unchanged
    // (re-sync only happens when the "settings to be used" actually changed).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncFiltersOnResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadBreathPeriodHistory()
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Progressive O\u2082") },
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
                    TableHelpIcon(title = "Progressive O\u2082", text = PROGRESSIVE_O2_HELP_TEXT)
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2, onClick = { navController.navigate(WagsRoutes.SETTINGS) })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Settings edit dialog state
        var showSettingsDialog by remember { mutableStateOf(false) }
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }
        // Filter dialog state
        var showFilterDialog by remember { mutableStateOf(false) }
        // Eucapnic settings dialog state
        var showEucapnicSettingsDialog by remember { mutableStateOf(false) }
        var showPastConfigurationsDialog by remember { mutableStateOf(false) }
        var showSaveConfigurationDialog by remember { mutableStateOf(false) }
        var saveConfigurationName by remember { mutableStateOf("") }

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
                onDismiss = { showEucapnicSettingsDialog = false },
                pastConfigurations = pastConfigurations,
                onPastConfigurationsClick = { showPastConfigurationsDialog = true }
            )
        }

        // Past configurations dialog
        if (showPastConfigurationsDialog) {
            PastConfigurationsDialog(
                configurations = pastConfigurations,
                onConfigurationSelected = { entity ->
                    // Load the configuration in both ViewModels
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
                    viewModel.loadEucapnicConfiguration(config)
                    showPastConfigurationsDialog = false
                },
                onSaveCurrentClick = {
                    showPastConfigurationsDialog = false
                    showSaveConfigurationDialog = true
                },
                onDismiss = { showPastConfigurationsDialog = false }
            )
        }

        // Save configuration dialog
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
                            state.eucapnicConfig?.let { config ->
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
        ) {
            // 0. Settings summary banner — pinned below the top bar so it stays
            // visible while scrolling. Clickable to open the settings popup, but
            // only while no session is running (plain label during a session).
            ApneaSettingsSummaryBanner(
                lungVolume = state.lungVolume,
                prepType   = state.prepType,
                // Dimension-aware bucket: hour number in BY_HOUR mode, Morning/Day/Night otherwise.
                timeOfDay  = effectiveTod,
                posture    = state.posture,
                audio      = state.audio,
                onClick    = if (!state.isSessionActive) {{ showSettingsDialog = true }} else null
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

            // 0b. Song picker / connect prompt — shown when MUSIC mode
            // Selected-song banner doubles as the picker trigger, so banner and
            // choose-button are never visible at the same time.
            if (state.isMusicMode) {
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

            // 0c. Guided audio picker — shown when GUIDED mode
            // Selected-audio banner doubles as the picker trigger.
            var showGuidedPicker by remember { mutableStateOf(false) }
            if (state.isGuidedMode) {
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

            // 0d. Guided hyperventilation section — shown when prep is HYPER
            var showGuidedHyperEditSheet by remember { mutableStateOf(false) }
            if (state.isHyperPrep) {
                GuidedHyperSection(
                    enabled = state.guidedHyperEnabled,
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec,
                    showStartMp3WithHyper = state.isGuidedMode,
                    startMp3WithHyper = state.startMp3WithHyper,
                    onEnabledChange = { viewModel.setGuidedHyperEnabled(it) },
                    onStartMp3WithHyperChange = { viewModel.setStartMp3WithHyper(it) },
                    onEditClick = { showGuidedHyperEditSheet = true }
                )
            }

            if (showGuidedHyperEditSheet) {
                GuidedHyperEditSheet(
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec,
                    onRelaxedExhaleChange = { viewModel.setGuidedRelaxedExhaleSec(it) },
                    onPurgeExhaleChange = { viewModel.setGuidedPurgeExhaleSec(it) },
                    onTransitionChange = { viewModel.setGuidedTransitionSec(it) },
                    onDismiss = { showGuidedHyperEditSheet = false }
                )
            }

            // Guided hyperventilation countdown dialog
            if (state.showGuidedCountdown) {
                GuidedHyperCountdownDialog(
                    phases = GuidedHyperPhases(
                        relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                        purgeExhaleSec = state.guidedPurgeExhaleSec,
                        transitionSec = state.guidedTransitionSec
                    ),
                    onComplete = { viewModel.onGuidedCountdownComplete() },
                    onCancel = { viewModel.onGuidedCountdownCancelled() }
                )
            }

            // 0f. Eucapnic Diaphragmatic Breathing settings — shown when prep is EUCAPNIC_DIAPHRAGMATIC
            if (state.prepType == PrepType.EUCAPNIC_DIAPHRAGMATIC.name && state.eucapnicConfig != null) {
                EucapnicSettingsButton(
                    config = state.eucapnicConfig!!,
                    onClick = { showEucapnicSettingsDialog = true }
                )
            }

            // 0e. Record-breaking forecast
            RecordForecastSummary(
                forecast = state.recordForecast
            )

            // 1. Explanation card
            ExplanationCard()

            // 2. Breath period input
            BreathPeriodSection(
                breathPeriodSec = state.breathPeriodSec,
                onSetBreathPeriod = { viewModel.setBreathPeriod(it) }
            )

            // 2b. Voice / vibration toggles
            VoiceVibrationToggles(
                voiceEnabled = state.voiceEnabled,
                vibrationEnabled = state.vibrationEnabled,
                onVoiceToggle = { viewModel.setVoiceEnabled(it) },
                onVibrationToggle = { viewModel.setVibrationEnabled(it) }
            )

            // 3. Start button — triggers guided countdown if applicable
            val useGuidedStart = state.isHyperPrep
                    && state.guidedHyperEnabled
                    && !state.guidedCountdownComplete

            val isEucapnicPrep = state.prepType == PrepType.EUCAPNIC_DIAPHRAGMATIC.name &&
                    state.eucapnicConfig != null

            Button(
                onClick = {
                    if (isEucapnicPrep) {
                        if (state.eucapnicPrepCompleted) {
                            // Eucapnic prep already done — start the session. Consume
                            // the flag so the next session begins with a fresh prep.
                            viewModel.setEucapnicPrepCompleted(false)
                            navController.navigate(WagsRoutes.PROGRESSIVE_O2_ACTIVE)
                        } else {
                            // Navigate to eucapnic pacer screen with the current config
                            val config = state.eucapnicConfig!!
                            navController.navigate(
                                WagsRoutes.eucapnicPacer(
                                    lungVolume = state.lungVolume,
                                    timeOfDay = state.timeOfDay,
                                    posture = state.posture,
                                    audio = state.audio,
                                    sessionType = "PROGRESSIVE_O2",
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
                    } else if (useGuidedStart) {
                        viewModel.showGuidedCountdown()
                    } else {
                        navController.navigate(WagsRoutes.PROGRESSIVE_O2_ACTIVE)
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

            // 3b. Personal Bests button
            OutlinedButton(
                onClick = {
                    navController.navigate(
                        WagsRoutes.personalBests(
                            drillType = "PROGRESSIVE_O2",
                            drillParamValue = state.breathPeriodSec
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

            // 4. Breath period history
            val isFiltered = state.filterLungVolume.isNotEmpty()
                    || state.filterPrepType.isNotEmpty()
                    || state.filterTimeOfDay.isNotEmpty()
                    || state.filterPosture.isNotEmpty()
                    || state.filterAudio.isNotEmpty()
            BreathPeriodHistorySection(
                history = state.pastBreathPeriods,
                currentBreathPeriodSec = state.breathPeriodSec,
                filterSummary = buildProgressiveO2FilterSummary(state),
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
}

// ─────────────────────────────────────────────────────────────────────────────
// Explanation Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = "Progressive O\u2082 is an endless breath-hold drill that builds oxygen " +
                    "tolerance. Each round increases the hold by 15 seconds: " +
                    "15s \u2192 30s \u2192 45s \u2192 60s \u2192 \u2026 " +
                    "The drill continues until you stop it. Set your breathing period " +
                    "below and tap Start when ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Breath Period Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BreathPeriodSection(
    breathPeriodSec: Int,
    onSetBreathPeriod: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Breathing Period",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalButton(
                onClick = {
                    val newVal = (breathPeriodSec - 5).coerceIn(15, 180)
                    onSetBreathPeriod(newVal)
                },
                enabled = breathPeriodSec > 15,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled
                ),
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = "${breathPeriodSec}s",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 80.dp)
            )
            Spacer(modifier = Modifier.width(24.dp))
            FilledTonalButton(
                onClick = {
                    val newVal = (breathPeriodSec + 5).coerceIn(15, 180)
                    onSetBreathPeriod(newVal)
                },
                enabled = breathPeriodSec < 180,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled
                ),
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Breath Period History Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BreathPeriodHistorySection(
    history: List<BreathPeriodHistory>,
    currentBreathPeriodSec: Int,
    filterSummary: String,
    isFiltered: Boolean = false,
    onViewSessionDetail: (Long) -> Unit,
    onFilterClick: () -> Unit = {},
    onClearAllFilters: () -> Unit = {}
) {
    // Header: title on its own line, filter buttons on the line below —
    // gives the filter summary the full row width to display completely.
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Past Breath Periods",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quick "All" chip — single tap to clear all filters
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
                modifier = Modifier.weight(1f),
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
            history.forEach { item ->
                val isSelected = item.breathPeriodSec == currentBreathPeriodSec
                val bgColor = if (isSelected) SurfaceVariant.copy(alpha = 0.5f) else SurfaceDark
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .clickable { onViewSessionDetail(item.maxHoldRecordId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.breathPeriodSec}s breath",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = TextPrimary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Total hold: ${formatSeconds(item.maxHoldReachedSec)}",
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

/** Formats total seconds as "M:SS" (e.g. 120 → "2:00", 75 → "1:15"). */
private fun formatSeconds(totalSec: Int): String {
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
