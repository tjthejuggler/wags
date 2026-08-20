package com.example.wags.ui.apnea

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeDimension
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
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
    val timeDimension by viewModel.timeDimension.collectAsStateWithLifecycle()

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
                    // Days since ANY session was done with the exact current settings
                    // combo (∞ when never). Shown next to the title when expanded and
                    // in the collapsed summary line.
                    val headerNowMs = remember { System.currentTimeMillis() }
                    val comboDaysSince = remember(
                        state.lastUsedPerCombo, state.selectedLungVolume, state.prepType,
                        state.timeOfDay, state.posture, state.audio
                    ) {
                        val selectedByKey = mapOf(
                            "lungVolume" to state.selectedLungVolume,
                            "prepType"   to state.prepType.name,
                            "timeOfDay"  to state.timeOfDay.name,
                            "posture"    to state.posture.name,
                            "audio"      to state.audio.name
                        )
                        // lastUsedPerCombo[key][selectedValue] is the last time a record
                        // matched that value AND the currently selected values of every
                        // other category — i.e. the exact current combination.
                        val comboLastMs = selectedByKey.entries.firstNotNullOfOrNull { (key, value) ->
                            state.lastUsedPerCombo[key]?.get(value)
                        }
                        HyperLockManager.daysSinceUsed(comboLastMs, headerNowMs)
                    }
                    CollapsibleSectionHeader(
                        title = "Settings",
                        expanded = state.settingsExpanded,
                        onToggle = { viewModel.toggleSettings() },
                        // The badge lives in the collapsed summary line while
                        // collapsed and moves up next to the label when
                        // expanded — never shown in both places at once.
                        comboDaysBadge = if (state.settingsExpanded) (comboDaysSince?.toString() ?: "∞") else null
                    )
                    // One-line settings summary — only visible while the section is
                    // collapsed; when expanded the full settings are shown instead.
                    AnimatedVisibility(
                        visible = !state.settingsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        ApneaSettingsSummaryBanner(
                            lungVolume = state.selectedLungVolume,
                            prepType   = state.prepType.name,
                            timeOfDay  = state.timeOfDay.name,
                            posture    = state.posture.name,
                            audio      = state.audio.name,
                            comboDaysSince = comboDaysSince?.toString() ?: "∞"
                        )
                    }
                    AnimatedVisibility(
                        visible = state.settingsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        ApneaSettingsContent(
                            byHour = timeDimension == TimeDimension.BY_HOUR,
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

            // ── Scrollable drill-card list ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(modifier = Modifier.height(2.dp))

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
                // The whole card acts as the start button → FreeHoldActiveScreen.
                DrillCard(
                    title = "Free Hold",
                    onClick = {
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
                    headerBadges = sectionBadges(ApneaSection.BEST_TIME),
                    headerAction = {
                        AutoSetMenuButton(
                            onEasiest = { viewModel.autoSetBestSettings() },
                            onRecord = { viewModel.autoSetRecordBest() }
                        )
                    }
                ) {
                    val flash by viewModel.flashMessage.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    LaunchedEffect(flash) {
                        flash?.let {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            viewModel.consumeFlashMessage()
                        }
                    }

                    FreeHoldContent(
                        freeHoldDurationMs    = state.freeHoldDurationMs,
                        bestTimeMs            = state.bestTimeForSettingsMs,
                        lastTimeMs            = state.lastFreeHoldForSettingsMs,
                        bestTimeRecordId      = state.bestTimeForSettingsRecordId,
                        lastTimeRecordId      = state.lastFreeHoldForSettingsRecordId,
                        bestTimeTrophyCategory = state.bestTimeTrophyCategory,
                        recordForecast         = state.recordForecast,
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

                // ── Progressive O₂ ────────────────────────────────────────────
                DrillCard(
                    title = "Progressive O₂",
                    onClick = { navController.navigate(WagsRoutes.PROGRESSIVE_O2) },
                    headerBadges = sectionBadges(ApneaSection.PROGRESSIVE_O2),
                    headerAction = {
                        AutoSetMenuButton(
                            onEasiest = { viewModel.autoSetEasiestDrill(ApneaSection.PROGRESSIVE_O2) },
                            onRecord = { viewModel.autoSetRecordDrill(ApneaSection.PROGRESSIVE_O2) }
                        )
                    }
                ) {
                    DrillSummaryContent(
                        bestTimeMs = state.progO2BestMs,
                        trophyCategory = state.progO2TrophyCategory,
                        paramLabel = "${state.progO2BreathPeriodSec}s breath period",
                        recordForecast = state.progO2RecordForecast,
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
                DrillCard(
                    title = "Min Breath",
                    onClick = { navController.navigate(WagsRoutes.MIN_BREATH) },
                    headerBadges = sectionBadges(ApneaSection.MIN_BREATH),
                    headerAction = {
                        AutoSetMenuButton(
                            onEasiest = { viewModel.autoSetEasiestDrill(ApneaSection.MIN_BREATH) },
                            onRecord = { viewModel.autoSetRecordDrill(ApneaSection.MIN_BREATH) }
                        )
                    }
                ) {
                    DrillSummaryContent(
                        bestTimeMs = state.minBreathBestMs,
                        trophyCategory = state.minBreathTrophyCategory,
                        paramLabel = "${state.minBreathSessionDurationSec / 60.0}min session",
                        recordForecast = state.minBreathRecordForecast,
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
                DrillCard(
                    title = "Contraction Tables",
                    onClick = { navController.navigate(WagsRoutes.CONTRACTION_TABLE) },
                    headerBadges = sectionBadges(ApneaSection.CONTRACTION_TABLES),
                    headerAction = {
                        // No forecast for contraction tables — "record" only.
                        AutoSetMenuButton(
                            onRecord = { viewModel.autoSetRecordDrill(ApneaSection.CONTRACTION_TABLES) }
                        )
                    }
                ) {
                    DrillSummaryContent(
                        bestTimeMs = state.contractionTableBestMs,
                        trophyCategory = state.contractionTableTrophyCategory,
                        paramLabel = "Till Contraction · Contraction Count",
                        recordForecast = null,
                        onTrophyClick = {
                            navController.navigate(
                                WagsRoutes.personalBests(drillType = "WONKA_FIRST_CONTRACTION")
                            )
                        }
                    )
                }

                // ── Table Training (normal O₂/CO₂ tables) ────────────────────
                // Whole card opens the dedicated table configuration screen.
                DrillCard(
                    title = "Table Training",
                    onClick = { navController.navigate(WagsRoutes.TABLE_TRAINING) },
                    headerBadges = sectionBadges(ApneaSection.TABLE_TRAINING)
                ) {
                    // Just name the two table types — the current PB/length/
                    // difficulty configuration lives on the Table Training screen.
                    Text(
                        "CO₂ and O₂",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
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
    comboDaysBadge: String? = null,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // Days since any session was done with the exact current settings
            // combo (∞ when never). Rendered only while expanded — while
            // collapsed the badge lives in the summary line instead.
            comboDaysBadge?.let { CornerBadge(text = it) }
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compact always-visible drill card. The whole card is clickable and acts as
// the start button for that session type; each session-type screen carries its
// own info button, and the days-since corner badges remain in the header.
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
private fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 9.sp,
        lineHeight = 10.sp,
        color = TextPrimary,
        modifier = modifier
            .border(1.dp, TextSecondary, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

@Composable
private fun DrillCard(
    title: String,
    onClick: () -> Unit,
    headerBadges: SectionCornerBadges? = null,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            // Thin grey outline (brightness pulse comes from the global BreathingOverlay)
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep the header clear of the corner badges that float
                        // to the right edge of the card.
                        .then(if (headerBadges != null) Modifier.padding(end = 20.dp) else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // Optional header action (e.g. the "auto set" menu) sits
                    // immediately left of the arrow, vertically aligned with the title.
                    headerAction?.invoke()
                    // Same trailing arrow as the main-screen navigation cards
                    Text("→", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                }
                content()
            }
        }

        // Days-since badges float in the card's top-right / bottom-right corners.
        // Upper badge = days since this session type was done (any settings);
        // lower badge = days since it was done with the exact current settings.
        headerBadges?.let {
            CornerBadge(
                text = it.daysSinceAny?.toString() ?: "∞",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 4.dp)
            )
            CornerBadge(
                text = it.daysSinceCombo?.toString() ?: "∞",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 3.dp, end = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Content (inside the sticky header)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ApneaSettingsContent(
    selectedLungVolume: String,
    /** True in By-the-Hour mode — the hour bucket is automatic, so the tod selector is hidden. */
    byHour: Boolean = false,
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

        // In By-the-Hour mode the bucket is automatic (derived from the session
        // start time) — the manual selector is hidden entirely.
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

        // ── Time of Day / Hour Bucket (kept last) ────────────────────────────
        if (!byHour) {
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
        } else {
            Text("Hour Bucket", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(
                "This session will use ${TimeBuckets.display(TimeBuckets.current())}h",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
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
    onBestTimeClick: (Long) -> Unit = {},
    onLastTimeClick: (Long) -> Unit = {},
    onTrophyClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Personal best for current settings — trophies + time (compact)
        if (bestTimeMs > 0L) {
            val trophies = bestTimeTrophyCategory?.trophyEmojis() ?: "🏆"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Trophies → navigate to Personal Bests screen
                    Text(
                        trophies,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { onTrophyClick() }.grayscale()
                    )
                    Text(" ", style = MaterialTheme.typography.titleMedium)
                    // Duration → navigate to record detail
                    Text(
                        formatMs(bestTimeMs),
                        style = MaterialTheme.typography.titleMedium,
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
                        style = MaterialTheme.typography.bodySmall,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = if (lastTimeRecordId != null)
                        Modifier.clickable { onLastTimeClick(lastTimeRecordId) }
                    else
                        Modifier
                )
            }
        }

        // ── Record-breaking forecast ─────────────────────────────────────────
        RecordForecastSummary(forecast = recordForecast)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Drill Summary Content — compact trophy row for Progressive O₂, Min Breath, etc.
// Each drill's explanation lives in its info popup; opening the drill is done
// by tapping the whole card.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DrillSummaryContent(
    bestTimeMs: Long,
    trophyCategory: PersonalBestCategory?,
    paramLabel: String = "",
    recordForecast: RecordForecast? = null,
    onTrophyClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Param label (e.g. "60s breath period", "5min session")
        if (paramLabel.isNotEmpty()) {
            Text(
                paramLabel,
                style = MaterialTheme.typography.labelSmall,
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
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { onTrophyClick() }.grayscale()
                )
                Text(" ", style = MaterialTheme.typography.titleMedium)
                Text(
                    formatMs(bestTimeMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Record-breaking forecast ──────────────────────────────────────────
        RecordForecastSummary(forecast = recordForecast)
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
internal fun TableHelpIcon(title: String, text: String) {
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
