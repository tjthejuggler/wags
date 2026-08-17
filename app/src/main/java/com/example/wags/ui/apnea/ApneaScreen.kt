package com.example.wags.ui.apnea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.spotify.TrackInfo
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.domain.usecase.apnea.forecast.RecordForecast
import com.example.wags.ui.apnea.forecast.RecordForecastSummary
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.common.LockPortrait
import com.example.wags.ui.common.StatsAndSensorActionsNav
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaScreen(
    navController: NavController,
    viewModel: ApneaViewModel = hiltViewModel(),
    eucapnicConfigViewModel: EucapnicConfigViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pastConfigurations by eucapnicConfigViewModel.pastConfigurations.collectAsStateWithLifecycle()

    LockPortrait()

    // Re-read drill params (breath period, session duration) every time this screen is shown
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDrillParams()
                viewModel.refreshForecast()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // New personal best congratulations dialog
    state.newPersonalBest?.let { pbResult ->
        NewPersonalBestDialog(
            newPbMs = pbResult.durationMs,
            categoryDescription = pbResult.description,
            category = pbResult.category,
            onDismiss = { viewModel.dismissNewPersonalBest() }
        )
    }

    // Past Configurations dialog for Eucapnic Diaphragmatic Breathing
    if (state.showPastConfigurationsDialog) {
        var showSaveNameDialog by remember { mutableStateOf(false) }
        var saveName by remember { mutableStateOf("") }

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
                viewModel.hidePastConfigurationsDialog()
            },
            onSaveCurrentClick = {
                showSaveNameDialog = true
            },
            onDismiss = { viewModel.hidePastConfigurationsDialog() }
        )

        // Save name dialog
        if (showSaveNameDialog) {
            AlertDialog(
                onDismissRequest = { showSaveNameDialog = false },
                title = { Text("Save Configuration") },
                text = {
                    TextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Configuration name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.eucapnicConfig?.let { config ->
                                eucapnicConfigViewModel.saveConfiguration(saveName)
                            }
                            showSaveNameDialog = false
                            saveName = ""
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveNameDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Apnea Training", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    StatsAndSensorActionsNav(
                        onStatsClick = {
                            navController.navigate(
                                WagsRoutes.apneaHistory(
                                    lungVolume = state.selectedLungVolume,
                                    prepType   = state.prepType.name,
                                    timeOfDay  = state.timeOfDay.name,
                                    posture    = state.posture.name,
                                    audio      = state.audio.name
                                )
                            )
                        },
                        navController = navController,
                        liveHr = state.liveHr,
                        liveSpO2 = state.liveSpO2
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Advice Banner ───────────────────────────────────────────────────
            AdviceBanner(section = AdviceSection.APNEA)

            // ── Sticky Settings Header ────────────────────────────────────────
            Surface(
                color = BackgroundDark,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    CollapsibleSectionHeader(
                        title = "Settings",
                        expanded = state.settingsExpanded,
                        onToggle = { viewModel.toggleSettings() }
                    )
                    AnimatedVisibility(
                        visible = state.settingsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        ApneaSettingsContent(
                            selectedLungVolume = state.selectedLungVolume,
                            prepType = state.prepType,
                            timeOfDay = state.timeOfDay,
                            posture = state.posture,
                            audio = state.audio,
                            lastUsedPerSetting = state.lastUsedPerSetting,
                            lastUsedPerCombo = state.lastUsedPerCombo,
                            hyperRemainingLockDays = state.hyperRemainingLockDays,
                            resonancePrepLocked = state.resonancePrepLocked,
                            onLungVolumeChange = { viewModel.setLungVolume(it) },
                            onPrepTypeChange = { viewModel.setPrepType(it) },
                            onTimeOfDayChange = { viewModel.setTimeOfDay(it) },
                            onPostureChange = { viewModel.setPosture(it) },
                            onAudioChange = { viewModel.setAudio(it) }
                        )
                    }
                    HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // ── Scrollable accordion body ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Day-granularity "now" — recomputed per composition is fine for whole-day badges
                val badgeNowMs = remember { System.currentTimeMillis() }
                fun sectionBadges(section: ApneaSection): SectionCornerBadges {
                    val info = state.sectionLastUse[section]
                    return SectionCornerBadges(
                        daysSinceAny = HyperLockManager.daysSinceUsed(info?.anySettingsMs, badgeNowMs),
                        daysSinceCombo = HyperLockManager.daysSinceUsed(info?.currentSettingsMs, badgeNowMs)
                    )
                }

                // ── Free Hold ─────────────────────────────────────────────────
                val bestTimeOpen = state.openSection == ApneaSection.BEST_TIME
                CollapsibleCard(
                    title = "Free Hold",
                    expanded = bestTimeOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.BEST_TIME) },
                    headerBadges = sectionBadges(ApneaSection.BEST_TIME)
                ) {
                    FreeHoldContent(
                        freeHoldDurationMs    = state.freeHoldDurationMs,
                        bestTimeMs            = state.bestTimeForSettingsMs,
                        lastTimeMs            = state.lastFreeHoldForSettingsMs,
                        bestTimeRecordId      = state.bestTimeForSettingsRecordId,
                        lastTimeRecordId      = state.lastFreeHoldForSettingsRecordId,
                        bestTimeTrophyCategory = state.bestTimeTrophyCategory,
                        recordForecast         = state.recordForecast,
                        onAutoSet              = { viewModel.autoSetBestSettings() },
                        onStartHold = {
                            // Always navigate to FreeHoldActiveScreen
                            // Eucapnic config button will be shown there when EUCAPNIC_DIAPHRAGMATIC is selected
                            navController.navigate(
                                WagsRoutes.freeHoldActive(
                                    lungVolume = state.selectedLungVolume,
                                    prepType   = state.prepType.name,
                                    timeOfDay  = state.timeOfDay.name,
                                    posture    = state.posture.name,
                                    showTimer  = state.showTimer,
                                    audio      = state.audio.name
                                )
                            )
                        },
                        onBestTimeClick = { recordId ->
                            navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                        },
                        onLastTimeClick = { recordId ->
                            navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                        },
                        onTrophyClick = {
                            navController.navigate(WagsRoutes.personalBests())
                        }
                    )
                }

                // ── Table Training (config + O2/CO2 launch) ───────────────────
                val tableTrainingOpen = state.openSection == ApneaSection.TABLE_TRAINING
                CollapsibleCard(
                    title = "Table Training",
                    expanded = tableTrainingOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.TABLE_TRAINING) },
                    headerBadges = sectionBadges(ApneaSection.TABLE_TRAINING),
                    headerExtra = {
                        TableHelpIcon(
                            title = TABLE_TRAINING_HELP_TITLE,
                            text = TABLE_TRAINING_HELP_TEXT
                        )
                    }
                ) {
                    TableTrainingConfigContent(
                        personalBestMs = state.personalBestMs,
                        bestTimeForSettingsMs = state.bestTimeForSettingsMs,
                        selectedLength = state.selectedLength,
                        selectedDifficulty = state.selectedDifficulty,
                        onSetPersonalBest = { viewModel.setPersonalBest(it) },
                        onLengthSelected = { viewModel.setLength(it) },
                        onDifficultySelected = { viewModel.setDifficulty(it) },
                        onNavigateO2 = { navController.navigate(WagsRoutes.apneaTable("O2")) },
                        onNavigateCo2 = { navController.navigate(WagsRoutes.apneaTable("CO2")) }
                    )
                }

                // ── Progressive O2 ────────────────────────────────────────────
                val progO2Open = state.openSection == ApneaSection.PROGRESSIVE_O2
                CollapsibleCard(
                    title = "Progressive O₂",
                    expanded = progO2Open,
                    onToggle = { viewModel.toggleSection(ApneaSection.PROGRESSIVE_O2) },
                    headerBadges = sectionBadges(ApneaSection.PROGRESSIVE_O2),
                    headerExtra = {
                        TableHelpIcon(title = PROGRESSIVE_O2_HELP_TITLE, text = PROGRESSIVE_O2_HELP_TEXT)
                    }
                ) {
                    DrillSectionContent(
                        bestTimeMs = state.progO2BestMs,
                        trophyCategory = state.progO2TrophyCategory,
                        paramLabel = "${state.progO2BreathPeriodSec}s breath period",
                        description = "Endless breath-hold drill: 15s → 30s → 45s → … with a configurable breathing period between holds.",
                        buttonLabel = "Open Progressive O₂",
                        recordForecast = state.progO2RecordForecast,
                        onOpenDrill = { navController.navigate(WagsRoutes.PROGRESSIVE_O2) },
                        onTrophyClick = {
                            navController.navigate(
                                WagsRoutes.personalBests(
                                    drillType = "PROGRESSIVE_O2",
                                    drillParamValue = state.progO2BreathPeriodSec
                                )
                            )
                        }
                    )
                }

                // ── Min Breath ────────────────────────────────────────────────
                val minBreathOpen = state.openSection == ApneaSection.MIN_BREATH
                CollapsibleCard(
                    title = "Min Breath",
                    expanded = minBreathOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.MIN_BREATH) },
                    headerBadges = sectionBadges(ApneaSection.MIN_BREATH),
                    headerExtra = {
                        TableHelpIcon(title = MIN_BREATH_HELP_TITLE, text = MIN_BREATH_HELP_TEXT)
                    }
                ) {
                    DrillSectionContent(
                        bestTimeMs = state.minBreathBestMs,
                        trophyCategory = state.minBreathTrophyCategory,
                        paramLabel = "${state.minBreathSessionDurationSec / 60.0}min session",
                        description = "Choose a session duration, then minimize your breathing time. " +
                            "You control when to hold and when to breathe.",
                        buttonLabel = "Open Min Breath",
                        buttonColor = ButtonPrimary,
                        recordForecast = state.minBreathRecordForecast,
                        onOpenDrill = { navController.navigate(WagsRoutes.MIN_BREATH) },
                        onTrophyClick = {
                            navController.navigate(
                                WagsRoutes.personalBests(
                                    drillType = "MIN_BREATH",
                                    drillParamValue = state.minBreathSessionDurationSec
                                )
                            )
                        }
                    )
                }

                // ── Contraction Tables ────────────────────────────────────────
                val contractionTablesOpen = state.openSection == ApneaSection.CONTRACTION_TABLES
                CollapsibleCard(
                    title = "Contraction Tables",
                    expanded = contractionTablesOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.CONTRACTION_TABLES) },
                    headerBadges = sectionBadges(ApneaSection.CONTRACTION_TABLES),
                    headerExtra = {
                        TableHelpIcon(title = CONTRACTION_TABLES_HELP_TITLE, text = CONTRACTION_TABLES_HELP_TEXT)
                    }
                ) {
                    DrillSectionContent(
                        bestTimeMs = state.contractionTableBestMs,
                        trophyCategory = state.contractionTableTrophyCategory,
                        paramLabel = "Till Contraction · Contraction Count",
                        description = "Contraction-driven tables: hold until your first contraction, or for a " +
                            "target number of contractions, with decreasing rest between rounds.",
                        buttonLabel = "Open Contraction Tables",
                        recordForecast = null,
                        onOpenDrill = { navController.navigate(WagsRoutes.CONTRACTION_TABLE) },
                        onTrophyClick = {
                            navController.navigate(
                                WagsRoutes.personalBests(drillType = "WONKA_FIRST_CONTRACTION")
                            )
                        }
                    )
                }

                // ── Session Analytics ─────────────────────────────────────────
                val analyticsOpen = state.openSection == ApneaSection.SESSION_ANALYTICS
                CollapsibleCard(
                    title = "Session Analytics",
                    expanded = analyticsOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.SESSION_ANALYTICS) }
                ) {
                    OutlinedButton(
                        onClick = { navController.navigate(WagsRoutes.SESSION_ANALYTICS_HISTORY) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📊 View Session Analytics", modifier = Modifier.grayscale())
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// New Personal Best Dialog — with confetti celebration
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun NewPersonalBestDialog(
    newPbMs: Long,
    categoryDescription: String = "",
    category: PersonalBestCategory = PersonalBestCategory.EXACT,
    onDismiss: () -> Unit
) {
    val trophies = category.trophyEmojis()

    val headline = when (category) {
        PersonalBestCategory.GLOBAL -> "New All-Time Personal Best!"
        else                        -> "New Personal Best!"
    }

    val subtitle = when (category) {
        PersonalBestCategory.GLOBAL      -> "Best across all settings!"
        PersonalBestCategory.ONE_SETTING -> "Best for $categoryDescription (any other settings)"
        else                             -> "Best for $categoryDescription"
    }

    val confettiCount = when (category) {
        PersonalBestCategory.GLOBAL         -> 80
        PersonalBestCategory.ONE_SETTING    -> 60
        PersonalBestCategory.TWO_SETTINGS   -> 55
        PersonalBestCategory.THREE_SETTINGS -> 50
        PersonalBestCategory.FOUR_SETTINGS  -> 47
        PersonalBestCategory.EXACT          -> 45
    }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        playApneaPbSound(context, category)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Semi-transparent scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundDark.copy(alpha = 0.85f))
                    .clickable(onClick = onDismiss)
            )

            // Confetti rains over the entire screen
            ConfettiOverlay(
                modifier = Modifier.fillMaxSize(),
                particleCount = confettiCount,
                durationMs = 3_500
            )

            // Card content with scale-in entrance
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = tween(durationMillis = 350)
                ) + fadeIn(animationSpec = tween(durationMillis = 350))
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Trophies matching the number of settings broken
                        Text(
                            trophies,
                            style = MaterialTheme.typography.displayMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.grayscale()
                        )
                        Text(
                            headline,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            formatMs(newPbMs),
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Congratulations! You've beaten your previous record. Keep it up!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🎉 Awesome!", color = TextPrimary, fontWeight = FontWeight.Bold,
                                modifier = Modifier.grayscale())
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Collapsible section header (used for the sticky settings bar)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic collapsible card used for accordion sections
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Corner badges for an accordion section header. Null day counts mean the
 * session type has never been done under that constraint → rendered as ∞.
 */
private data class SectionCornerBadges(
    val daysSinceAny: Int?,
    val daysSinceCombo: Int?
)

/** Tiny bordered number badge used in the corners of section headers. */
@Composable
private fun CornerBadge(text: String) {
    Text(
        text = text,
        fontSize = 9.sp,
        lineHeight = 10.sp,
        color = TextPrimary,
        modifier = Modifier
            .border(1.dp, TextSecondary, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    headerBadges: SectionCornerBadges? = null,
    headerExtra: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    headerExtra()
                    // Upper badge = days since this session type was done (any settings);
                    // lower badge = days since it was done with the exact current settings.
                    headerBadges?.let {
                        Column(horizontalAlignment = Alignment.End) {
                            CornerBadge(it.daysSinceAny?.toString() ?: "∞")
                            CornerBadge(it.daysSinceCombo?.toString() ?: "∞")
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = TextSecondary
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Content (inside the sticky header)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ApneaSettingsContent(
    selectedLungVolume: String,
    prepType: PrepType,
    timeOfDay: TimeOfDay,
    posture: Posture,
    audio: AudioSetting,
    lastUsedPerSetting: Map<String, Map<String, Long>>,
    lastUsedPerCombo: Map<String, Map<String, Long>>,
    hyperRemainingLockDays: Int,
    /** True when no resonance breathing session ended within the last ~5 minutes. */
    resonancePrepLocked: Boolean,
    onLungVolumeChange: (String) -> Unit,
    onPrepTypeChange: (PrepType) -> Unit,
    onTimeOfDayChange: (TimeOfDay) -> Unit,
    onPostureChange: (Posture) -> Unit,
    onAudioChange: (AudioSetting) -> Unit
) {
    // Day-granularity "now" — recomputed per composition is fine for whole-day badges
    val nowMs = remember { System.currentTimeMillis() }

    fun daysSince(settingKey: String, settingValue: String): Int? =
        HyperLockManager.daysSinceUsed(lastUsedPerSetting[settingKey]?.get(settingValue), nowMs)

    /** Days since this value was used combined with the currently selected values of all other categories. */
    fun comboDaysSince(settingKey: String, settingValue: String): Int? =
        HyperLockManager.daysSinceUsed(lastUsedPerCombo[settingKey]?.get(settingValue), nowMs)

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Lung Volume", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("FULL", "PARTIAL", "EMPTY").forEach { volume ->
                SettingChip(
                    selected = selectedLungVolume == volume,
                    onClick = { onLungVolumeChange(volume) },
                    label = volume.displayLungVolume(),
                    daysSinceUsed = daysSince("lungVolume", volume),
                    daysSinceCombo = comboDaysSince("lungVolume", volume)
                )
            }
        }

        Text("Prep Type", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrepType.entries.forEach { type ->
                SettingChip(
                    selected = prepType == type,
                    onClick = { onPrepTypeChange(type) },
                    label = type.shortDisplayName(),
                    daysSinceUsed = daysSince("prepType", type.name),
                    daysSinceCombo = comboDaysSince("prepType", type.name),
                    // Lock overlay only on the HYPER chip (centered)
                    hyperRemainingLockDays = if (type == PrepType.HYPER) hyperRemainingLockDays else null,
                    // Staleness lock on the RESONANCE chip: no resonance breathing
                    // session ended within the last ~5 minutes.
                    locked = type == PrepType.RESONANCE && resonancePrepLocked
                )
            }
        }

        Text("Time of Day", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeOfDay.entries.forEach { tod ->
                SettingChip(
                    selected = timeOfDay == tod,
                    onClick = { onTimeOfDayChange(tod) },
                    label = tod.displayName(),
                    daysSinceUsed = daysSince("timeOfDay", tod.name),
                    daysSinceCombo = comboDaysSince("timeOfDay", tod.name)
                )
            }
        }

        Text("Posture", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Posture.entries.forEach { pos ->
                SettingChip(
                    selected = posture == pos,
                    onClick = { onPostureChange(pos) },
                    label = pos.displayName(),
                    daysSinceUsed = daysSince("posture", pos.name),
                    daysSinceCombo = comboDaysSince("posture", pos.name)
                )
            }
        }

        Text("Audio", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioSetting.entries.forEach { aud ->
                SettingChip(
                    selected = audio == aud,
                    onClick = { onAudioChange(aud) },
                    label = aud.displayName(),
                    daysSinceUsed = daysSince("audio", aud.name),
                    daysSinceCombo = comboDaysSince("audio", aud.name)
                )
            }
        }

    }
}

/**
 * A settings FilterChip with corner badges:
 *  - upper-right: days since this value was last used in any session
 *    (hidden when never used);
 *  - lower-right: days since this value was used combined with the currently
 *    selected values of all other setting categories (∞ when never);
 *  - centered, slightly transparent lock overlay while HYPER is time-locked
 *    (🔒 + remaining days) or RESONANCE is staleness-locked (🔒).
 */
@Composable
private fun SettingChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    daysSinceUsed: Int?,
    daysSinceCombo: Int? = null,
    hyperRemainingLockDays: Int? = null,
    locked: Boolean = false
) {
    Box {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.height(30.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = SurfaceVariant,
                selectedLabelColor = TextPrimary
            )
        )
        // Days-since-used badge — upper-right corner (hidden when never used)
        if (daysSinceUsed != null) {
            Text(
                text = "$daysSinceUsed",
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .border(1.dp, TextSecondary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        // Combo days badge — lower-right corner. Shows how many days since this
        // value was used together with the currently selected values of all the
        // other categories; ∞ when that combination has never been done.
        Text(
            text = daysSinceCombo?.toString() ?: "∞",
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = TextPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .border(1.dp, TextSecondary, RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )
        // Hyper lock — centered overlay, slightly transparent. Shown ONLY while
        // actually locked; nothing when unlocked (a tiny 🔓 emoji is illegible
        // at this size and reads as a closed padlock).
        if (hyperRemainingLockDays != null && hyperRemainingLockDays > 0) {
            Text(
                text = "🔒$hyperRemainingLockDays",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(0.5f)
            )
        }
        // Generic staleness lock (RESONANCE chip) — centered overlay, slightly
        // transparent. Clears the moment a fresh resonance session is saved.
        if (locked && (hyperRemainingLockDays == null || hyperRemainingLockDays <= 0)) {
            Text(
                text = "🔒",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(0.5f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Now Playing Banner — shown during active free hold when MUSIC is selected
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NowPlayingBanner(track: TrackInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🎵", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale())
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Free Hold Content  (summary card — no inline hold UI; hold runs on its own screen)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeHoldContent(
    freeHoldDurationMs: Long,
    bestTimeMs: Long,
    lastTimeMs: Long,
    bestTimeRecordId: Long?,
    lastTimeRecordId: Long?,
    bestTimeTrophyCategory: PersonalBestCategory?,
    recordForecast: RecordForecast? = null,
    onAutoSet: () -> Unit = {},
    onStartHold: () -> Unit,
    onBestTimeClick: (Long) -> Unit = {},
    onLastTimeClick: (Long) -> Unit = {},
    onTrophyClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Personal best for current settings — big trophies + big time
        if (bestTimeMs > 0L) {
            val trophies = bestTimeTrophyCategory?.trophyEmojis() ?: "🏆"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Trophies → navigate to Personal Bests screen
                    Text(
                        trophies,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.clickable { onTrophyClick() }.grayscale()
                    )
                    Text(" ", style = MaterialTheme.typography.headlineSmall)
                    // Duration → navigate to record detail
                    Text(
                        formatMs(bestTimeMs),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = if (bestTimeRecordId != null)
                            Modifier.clickable { onBestTimeClick(bestTimeRecordId) }
                        else
                            Modifier
                    )
                }
                // Last hold for these settings — smaller, beneath the best time; clickable → detail
                val displayLast = if (freeHoldDurationMs > 0L) freeHoldDurationMs else lastTimeMs
                if (displayLast > 0L) {
                    Text(
                        "last: ${formatMs(displayLast)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = if (lastTimeRecordId != null)
                            Modifier.clickable { onLastTimeClick(lastTimeRecordId) }
                        else
                            Modifier
                    )
                }
            }
        } else {
            // No best time yet — still show last hold if available; clickable → detail
            val displayLast = if (freeHoldDurationMs > 0L) freeHoldDurationMs else lastTimeMs
            if (displayLast > 0L) {
                Text(
                    "last: ${formatMs(displayLast)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = if (lastTimeRecordId != null)
                        Modifier.clickable { onLastTimeClick(lastTimeRecordId) }
                    else
                        Modifier
                )
            }
        }

        // ── Record-breaking forecast ──────────────────────────────────────────
        RecordForecastSummary(forecast = recordForecast, onAutoSet = onAutoSet)

        Button(
            onClick = onStartHold,
            colors = ButtonDefaults.buttonColors(containerColor = ButtonSuccess, contentColor = TextPrimary),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start Hold") }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Drill Section Content — reusable trophy + button for Progressive O₂, Min Breath, etc.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DrillSectionContent(
    bestTimeMs: Long,
    trophyCategory: PersonalBestCategory?,
    paramLabel: String = "",
    description: String,
    buttonLabel: String,
    buttonColor: Color = ButtonDefaults.buttonColors().containerColor,
    recordForecast: RecordForecast? = null,
    onOpenDrill: () -> Unit,
    onTrophyClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Param label (e.g. "60s breath period", "5min session")
        if (paramLabel.isNotEmpty()) {
            Text(
                paramLabel,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }

        // Trophy + best time (if any records exist)
        if (bestTimeMs > 0L) {
            val trophies = trophyCategory?.trophyEmojis() ?: "🏆"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    trophies,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.clickable { onTrophyClick() }.grayscale()
                )
                Text(" ", style = MaterialTheme.typography.headlineSmall)
                Text(
                    formatMs(bestTimeMs),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Record-breaking forecast ──────────────────────────────────────────
        RecordForecastSummary(forecast = recordForecast, showAutoSet = false)

        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Button(
            onClick = onOpenDrill,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
        ) {
            Text(buttonLabel)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Table Training Config Content (PB + length/difficulty)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TableTrainingConfigContent(
    personalBestMs: Long,
    bestTimeForSettingsMs: Long,
    selectedLength: TableLength,
    selectedDifficulty: TableDifficulty,
    onSetPersonalBest: (Long) -> Unit,
    onLengthSelected: (TableLength) -> Unit,
    onDifficultySelected: (TableDifficulty) -> Unit,
    onNavigateO2: () -> Unit,
    onNavigateCo2: () -> Unit
) {
    var pbInput by remember { mutableStateOf("") }

    // Auto-fill the text field from best free hold time
    LaunchedEffect(bestTimeForSettingsMs) {
        if (bestTimeForSettingsMs > 0L) {
            pbInput = (bestTimeForSettingsMs / 1000L).toString()
            // Also auto-set the PB if it hasn't been set yet
            if (personalBestMs <= 0L) {
                onSetPersonalBest(bestTimeForSettingsMs)
            }
        }
    }

    // Keep text field in sync when PB is set from elsewhere (e.g. auto-set from ViewModel)
    LaunchedEffect(personalBestMs) {
        if (personalBestMs > 0L && pbInput.isEmpty()) {
            pbInput = (personalBestMs / 1000L).toString()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (personalBestMs > 0L) {
            Text(
                "Personal Best: ${personalBestMs / 1000L}s",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = pbInput,
                onValueChange = { pbInput = it },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Personal Best (seconds)")
                        InfoHelpBubble(title = PB_HELP_TITLE, content = PB_HELP_CONTENT)
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                pbInput.toLongOrNull()?.let { onSetPersonalBest(it * 1000L) }
            }) { Text("Set") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Session Length", style = MaterialTheme.typography.bodyMedium)
            InfoHelpBubble(title = LENGTH_DIFFICULTY_HELP_TITLE, content = LENGTH_DIFFICULTY_HELP_CONTENT)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TableLength.SHORT  to "Short (4)",
                TableLength.MEDIUM to "Medium (8)",
                TableLength.LONG   to "Long (12)"
            ).forEach { (length, label) ->
                FilterChip(
                    selected = selectedLength == length,
                    onClick = { onLengthSelected(length) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SurfaceVariant,
                        selectedLabelColor = TextPrimary
                    )
                )
            }
        }

        Text("Difficulty", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TableDifficulty.EASY   to "Easy",
                TableDifficulty.MEDIUM to "Medium",
                TableDifficulty.HARD   to "Hard"
            ).forEach { (difficulty, label) ->
                FilterChip(
                    selected = selectedDifficulty == difficulty,
                    onClick = { onDifficultySelected(difficulty) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SurfaceVariant,
                        selectedLabelColor = TextPrimary
                    )
                )
            }
        }

        if (personalBestMs <= 0L) {
            Text(
                "Set a Personal Best above to enable the tables.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        HorizontalDivider(color = SurfaceVariant)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onNavigateO2,
                enabled = personalBestMs > 0L,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) { Text("Start O2 Table") }
            TableHelpIcon(title = O2_HELP_TITLE, text = O2_HELP_TEXT)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onNavigateCo2,
                enabled = personalBestMs > 0L,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) { Text("Start CO2 Table") }
            TableHelpIcon(title = CO2_HELP_TITLE, text = CO2_HELP_TEXT)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Maps the stored lung-volume key to a display label. "PARTIAL" → "Half"; others are title-cased. */
internal fun String.displayLungVolume(): String = when (this.uppercase()) {
    "PARTIAL" -> "Half"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}.${centis.toString().padStart(2, '0')}s"
}

@Composable
private fun TableHelpIcon(title: String, text: String) {
    var showDialog by remember { mutableStateOf(false) }

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
            text = { Text(text, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Got it") }
            }
        )
    }
}
