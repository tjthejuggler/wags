package com.example.wags.ui.apnea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.model.WonkaConfig
import com.example.wags.domain.usecase.apnea.AdvancedApneaPhase
import com.example.wags.domain.usecase.apnea.AdvancedApneaState
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaScreen(
    navController: NavController,
    viewModel: ApneaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    // New personal best congratulations dialog
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
                title = { Text("Apnea Training", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Sticky Settings Header ────────────────────────────────────────
            Surface(
                color = BackgroundDark,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                            onLungVolumeChange = { viewModel.setLungVolume(it) },
                            onPrepTypeChange = { viewModel.setPrepType(it) },
                            onTimeOfDayChange = { viewModel.setTimeOfDay(it) }
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

                // ── Best Time (Free Hold) ─────────────────────────────────────
                val bestTimeOpen = state.openSection == ApneaSection.BEST_TIME
                CollapsibleCard(
                    title = "Best Time",
                    expanded = bestTimeOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.BEST_TIME) }
                ) {
                    FreeHoldContent(
                        freeHoldDurationMs = state.freeHoldDurationMs,
                        bestTimeMs = state.bestTimeForSettingsMs,
                        bestTimeRecordId = state.bestTimeForSettingsRecordId,
                        showTimer = state.showTimer,
                        onShowTimerChange = { viewModel.setShowTimer(it) },
                        onStartHold = {
                            navController.navigate(WagsRoutes.FREE_HOLD_ACTIVE)
                        },
                        onBestTimeClick = { recordId ->
                            navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                        }
                    )
                }

                // ── Table Training (config + O2/CO2 launch) ───────────────────
                val tableTrainingOpen = state.openSection == ApneaSection.TABLE_TRAINING
                CollapsibleCard(
                    title = "Table Training",
                    expanded = tableTrainingOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.TABLE_TRAINING) },
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
                    headerExtra = {
                        TableHelpIcon(title = PROGRESSIVE_O2_HELP_TITLE, text = PROGRESSIVE_O2_HELP_TEXT)
                    }
                ) {
                    InlineAdvancedSessionContent(
                        modality = TrainingModality.PROGRESSIVE_O2,
                        activeModality = state.activeModalitySession,
                        advancedState = state.advancedSessionState,
                        onStart = { viewModel.startAdvancedSession(TrainingModality.PROGRESSIVE_O2) },
                        onStop = { viewModel.stopAdvancedSession() },
                        onBreathTaken = { viewModel.signalBreathTaken() },
                        onFirstContraction = { viewModel.signalFirstContraction() }
                    )
                }

                // ── Min Breath ────────────────────────────────────────────────
                val minBreathOpen = state.openSection == ApneaSection.MIN_BREATH
                CollapsibleCard(
                    title = "Min Breath",
                    expanded = minBreathOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.MIN_BREATH) },
                    headerExtra = {
                        TableHelpIcon(title = MIN_BREATH_HELP_TITLE, text = MIN_BREATH_HELP_TEXT)
                    }
                ) {
                    InlineAdvancedSessionContent(
                        modality = TrainingModality.MIN_BREATH,
                        activeModality = state.activeModalitySession,
                        advancedState = state.advancedSessionState,
                        enabled = state.personalBestMs > 0L,
                        onStart = { viewModel.startAdvancedSession(TrainingModality.MIN_BREATH) },
                        onStop = { viewModel.stopAdvancedSession() },
                        onBreathTaken = { viewModel.signalBreathTaken() },
                        onFirstContraction = { viewModel.signalFirstContraction() }
                    )
                }

                // ── Wonka: Till Contraction ───────────────────────────────────
                val wonkaContractionOpen = state.openSection == ApneaSection.WONKA_CONTRACTION
                CollapsibleCard(
                    title = "Wonka: Till Contraction",
                    expanded = wonkaContractionOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.WONKA_CONTRACTION) },
                    headerExtra = {
                        TableHelpIcon(title = WONKA_HELP_TITLE, text = WONKA_HELP_TEXT)
                    }
                ) {
                    InlineAdvancedSessionContent(
                        modality = TrainingModality.WONKA_FIRST_CONTRACTION,
                        activeModality = state.activeModalitySession,
                        advancedState = state.advancedSessionState,
                        onStart = { viewModel.startAdvancedSession(TrainingModality.WONKA_FIRST_CONTRACTION) },
                        onStop = { viewModel.stopAdvancedSession() },
                        onBreathTaken = { viewModel.signalBreathTaken() },
                        onFirstContraction = { viewModel.signalFirstContraction() }
                    )
                }

                // ── Wonka: Endurance ──────────────────────────────────────────
                val wonkaEnduranceOpen = state.openSection == ApneaSection.WONKA_ENDURANCE
                CollapsibleCard(
                    title = "Wonka: Endurance",
                    expanded = wonkaEnduranceOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.WONKA_ENDURANCE) },
                    headerExtra = {
                        TableHelpIcon(title = WONKA_HELP_TITLE, text = WONKA_HELP_TEXT)
                    }
                ) {
                    InlineAdvancedSessionContent(
                        modality = TrainingModality.WONKA_ENDURANCE,
                        activeModality = state.activeModalitySession,
                        advancedState = state.advancedSessionState,
                        onStart = { viewModel.startAdvancedSession(TrainingModality.WONKA_ENDURANCE) },
                        onStop = { viewModel.stopAdvancedSession() },
                        onBreathTaken = { viewModel.signalBreathTaken() },
                        onFirstContraction = { viewModel.signalFirstContraction() }
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
                        Text("📊 View Session Analytics")
                    }
                }

                // ── Recent Records ────────────────────────────────────────────
                val recentOpen = state.openSection == ApneaSection.RECENT_RECORDS
                CollapsibleCard(
                    title = "Recent Records",
                    expanded = recentOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.RECENT_RECORDS) }
                ) {
                    RecentRecordsContent(
                        records = state.recentRecords,
                        onAllRecordsClick = {
                            navController.navigate(WagsRoutes.APNEA_ALL_RECORDS)
                        },
                        onRecordClick = { record ->
                            navController.navigate(WagsRoutes.apneaRecordDetail(record.recordId))
                        }
                    )
                }

                // ── Stats ─────────────────────────────────────────────────────
                val statsOpen = state.openSection == ApneaSection.STATS
                CollapsibleCard(
                    title = "Stats",
                    expanded = statsOpen,
                    onToggle = { viewModel.toggleSection(ApneaSection.STATS) }
                ) {
                    StatsContent(
                        stats = if (state.showAllStats) state.allStats else state.filteredStats,
                        showAll = state.showAllStats,
                        onToggleShowAll = { viewModel.toggleShowAllStats() },
                        lungVolume = state.selectedLungVolume,
                        prepType = state.prepType,
                        timeOfDay = state.timeOfDay,
                        onRecordClick = { recordId ->
                            navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// New Personal Best Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NewPersonalBestDialog(
    newPbMs: Long,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                "🏆 New Personal Best!",
                style = MaterialTheme.typography.headlineSmall,
                color = EcgCyan,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    formatMs(newPbMs),
                    style = MaterialTheme.typography.displaySmall,
                    color = EcgCyan,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Incredible work! You've set a new personal best. Keep pushing your limits!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = EcgCyan),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🎉 Awesome!", color = BackgroundDark, fontWeight = FontWeight.Bold)
            }
        }
    )
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
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = TextSecondary
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic collapsible card used for accordion sections
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
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
    onLungVolumeChange: (String) -> Unit,
    onPrepTypeChange: (PrepType) -> Unit,
    onTimeOfDayChange: (TimeOfDay) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Lung Volume", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("FULL", "PARTIAL", "EMPTY").forEach { volume ->
                FilterChip(
                    selected = selectedLungVolume == volume,
                    onClick = { onLungVolumeChange(volume) },
                    label = { Text(volume.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        Text("Prep Type", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrepType.entries.forEach { type ->
                FilterChip(
                    selected = prepType == type,
                    onClick = { onPrepTypeChange(type) },
                    label = { Text(type.displayName()) }
                )
            }
        }

        Text("Time of Day", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeOfDay.entries.forEach { tod ->
                FilterChip(
                    selected = timeOfDay == tod,
                    onClick = { onTimeOfDayChange(tod) },
                    label = { Text(tod.displayName()) }
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
    bestTimeRecordId: Long?,
    showTimer: Boolean,
    onShowTimerChange: (Boolean) -> Unit,
    onStartHold: () -> Unit,
    onBestTimeClick: (Long) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Show-timer toggle (persisted preference, used on the active-hold screen)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show timer", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Checkbox(checked = showTimer, onCheckedChange = onShowTimerChange)
        }

        // Personal best for current settings
        if (bestTimeMs > 0L) {
            Text(
                "🏆 ${formatMs(bestTimeMs)}",
                style = MaterialTheme.typography.headlineSmall,
                color = EcgCyan,
                fontWeight = FontWeight.Bold,
                modifier = if (bestTimeRecordId != null)
                    Modifier.clickable { onBestTimeClick(bestTimeRecordId) }
                else
                    Modifier
            )
        }

        // Last hold result (shown after returning from the active-hold screen)
        if (freeHoldDurationMs > 0L) {
            Text(
                "Last: ${formatMs(freeHoldDurationMs)}",
                style = MaterialTheme.typography.headlineMedium,
                color = EcgCyan
            )
        }

        Button(
            onClick = onStartHold,
            colors = ButtonDefaults.buttonColors(containerColor = ButtonSuccess, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start Hold") }
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

    LaunchedEffect(bestTimeForSettingsMs) {
        if (bestTimeForSettingsMs > 0L) {
            pbInput = (bestTimeForSettingsMs / 1000L).toString()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (personalBestMs > 0L) {
            Text(
                "Personal Best: ${personalBestMs / 1000L}s",
                style = MaterialTheme.typography.bodyLarge,
                color = EcgCyan
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
                    label = { Text(label) }
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
                    label = { Text(label) }
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
// Inline Advanced Session Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InlineAdvancedSessionContent(
    modality: TrainingModality,
    activeModality: TrainingModality?,
    advancedState: AdvancedApneaState,
    enabled: Boolean = true,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBreathTaken: () -> Unit,
    onFirstContraction: () -> Unit
) {
    val isThisActive = activeModality == modality
    val anotherActive = activeModality != null && activeModality != modality

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!enabled) {
            Text(
                "Set a Personal Best in Table Training to enable this.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        if (anotherActive) {
            Text(
                "Another session is currently active. Stop it first.",
                style = MaterialTheme.typography.bodySmall,
                color = ReadinessOrange
            )
        }

        if (!isThisActive) {
            // Idle — show Start button
            Button(
                onClick = onStart,
                enabled = enabled && !anotherActive,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSuccess, contentColor = Color.White)
            ) { Text("Start Session") }
        } else {
            // Active session UI
            AdvancedSessionRunningContent(
                modality = modality,
                state = advancedState,
                onBreathTaken = onBreathTaken,
                onFirstContraction = onFirstContraction,
                onStop = onStop
            )
        }
    }
}

@Composable
private fun AdvancedSessionRunningContent(
    modality: TrainingModality,
    state: AdvancedApneaState,
    onBreathTaken: () -> Unit,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    // Round progress
    if (state.totalRounds > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Round ${state.currentRound} / ${state.totalRounds}",
                    style = MaterialTheme.typography.bodyMedium,
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

    // Phase timer card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                state.phase.displayLabel(),
                style = MaterialTheme.typography.titleMedium,
                color = state.phase.phaseColor()
            )
            when (state.phase) {
                AdvancedApneaPhase.WAITING_FOR_BREATH -> {
                    Text("Waiting for breath…", style = MaterialTheme.typography.headlineSmall, color = EcgCyan)
                }
                AdvancedApneaPhase.WONKA_CRUISING -> {
                    Text(formatMmSs(state.timerMs), style = MaterialTheme.typography.displayMedium, color = ApneaHold, fontWeight = FontWeight.Bold)
                    Text("Counting up — log first contraction", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                AdvancedApneaPhase.WONKA_ENDURANCE -> {
                    Text(formatMmSs(state.timerMs), style = MaterialTheme.typography.displayMedium, color = ApneaHold, fontWeight = FontWeight.Bold)
                    Text("Endurance — cruised ${formatMmSs(state.cruisingElapsedMs)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                AdvancedApneaPhase.COMPLETE -> {
                    Text("Session Complete! 🎉", style = MaterialTheme.typography.headlineSmall, color = ReadinessGreen)
                }
                AdvancedApneaPhase.IDLE -> Unit
                else -> {
                    Text(formatMmSs(state.timerMs), style = MaterialTheme.typography.displayMedium, color = state.phase.phaseColor(), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Action buttons
    when (state.phase) {
        AdvancedApneaPhase.WAITING_FOR_BREATH -> {
            Button(
                onClick = onBreathTaken,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSuccess, contentColor = Color.White)
            ) { Text("ONE BREATH TAKEN →", style = MaterialTheme.typography.titleMedium) }
        }
        AdvancedApneaPhase.WONKA_CRUISING -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onFirstContraction() }) },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onFirstContraction,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonDanger, contentColor = Color.White)
                ) { Text("LOG CONTRACTION", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "or double-tap anywhere",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
        AdvancedApneaPhase.COMPLETE -> {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) { Text("Done") }
            return
        }
        else -> Unit
    }

    // Stop button (always shown while session is running, except COMPLETE which has Done)
    if (state.phase != AdvancedApneaPhase.COMPLETE && state.phase != AdvancedApneaPhase.IDLE) {
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) { Text("Stop Session") }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recent Records Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentRecordsContent(
    records: List<ApneaRecordEntity>,
    onAllRecordsClick: () -> Unit,
    onRecordClick: (ApneaRecordEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // "All Records" button always at the top
        OutlinedButton(
            onClick = onAllRecordsClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("📋 All Records", style = MaterialTheme.typography.labelLarge)
        }

        if (records.isEmpty()) {
            Text(
                "No records yet. Complete a session to see it here.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            records.take(10).forEach { record ->
                RecentRecordRow(record = record, onClick = { onRecordClick(record) })
            }
        }
    }
}

@Composable
private fun RecentRecordRow(
    record: ApneaRecordEntity,
    onClick: () -> Unit
) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM d  HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(formatMs(record.durationMs), style = MaterialTheme.typography.bodyLarge, color = EcgCyan, fontWeight = FontWeight.SemiBold)
                Text(dateStr, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(record.tableType ?: "Free Hold", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
        }
    }
    HorizontalDivider(color = SurfaceVariant.copy(alpha = 0.5f))
}

// ─────────────────────────────────────────────────────────────────────────────
// Stats Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsContent(
    stats: ApneaStats,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    lungVolume: String,
    prepType: com.example.wags.domain.model.PrepType,
    timeOfDay: com.example.wags.domain.model.TimeOfDay,
    onRecordClick: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ── All-settings toggle ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (showAll) "All settings" else "Current settings",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showAll) EcgCyan else TextSecondary
                )
                if (!showAll) {
                    Text(
                        "${lungVolume.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${prepType.displayName()}  ·  ${timeOfDay.displayName()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Switch(
                    checked = showAll,
                    onCheckedChange = { onToggleShowAll() },
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        HorizontalDivider(color = SurfaceVariant)

        // ── Activity counts ───────────────────────────────────────────────────
        Text(
            "Activity Counts",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = EcgCyan
        )
        val activities = listOf(
            "Free Hold"           to stats.freeHoldCount,
            "O₂ Table"            to stats.o2TableCount,
            "CO₂ Table"           to stats.co2TableCount,
            "Progressive O₂"      to stats.progressiveO2Count,
            "Min Breath"          to stats.minBreathCount,
            "Wonka: Contraction"  to stats.wonkaContractionCount,
            "Wonka: Endurance"    to stats.wonkaEnduranceCount,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            activities.forEach { (label, count) ->
                StatsRow(label = label, value = count.toString())
            }
        }

        HorizontalDivider(color = SurfaceVariant)

        // ── Overall HR / SpO2 extremes ────────────────────────────────────────
        Text(
            "Overall Session Extremes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = EcgCyan
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExtremeRow(
                label = "Highest HR",
                value = stats.maxHrEver?.let { "%.0f bpm".format(it) } ?: "—",
                recordId = stats.maxHrEverRecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Lowest HR",
                value = stats.minHrEver?.let { "%.0f bpm".format(it) } ?: "—",
                recordId = stats.minHrEverRecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Lowest SpO₂",
                value = stats.lowestSpO2Ever?.let { "$it%" } ?: "—",
                recordId = stats.lowestSpO2EverRecordId,
                onRecordClick = onRecordClick
            )
        }

        HorizontalDivider(color = SurfaceVariant)

        // ── Session-start extremes ────────────────────────────────────────────
        Text(
            "Session Start Extremes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = EcgCyan
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExtremeRow(
                label = "Highest HR at start",
                value = stats.maxStartHr?.let { "$it bpm" } ?: "—",
                recordId = stats.maxStartHrRecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Lowest HR at start",
                value = stats.minStartHr?.let { "$it bpm" } ?: "—",
                recordId = stats.minStartHrRecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Highest SpO₂ at start",
                value = stats.maxStartSpO2?.let { "$it%" } ?: "—",
                recordId = stats.maxStartSpO2RecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Lowest SpO₂ at start",
                value = stats.minStartSpO2?.let { "$it%" } ?: "—",
                recordId = stats.minStartSpO2RecordId,
                onRecordClick = onRecordClick
            )
        }

        HorizontalDivider(color = SurfaceVariant)

        // ── Session-end extremes ──────────────────────────────────────────────
        Text(
            "Session End Extremes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = EcgCyan
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExtremeRow(
                label = "Highest HR at end",
                value = stats.maxEndHr?.let { "$it bpm" } ?: "—",
                recordId = stats.maxEndHrRecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Lowest HR at end",
                value = stats.minEndHr?.let { "$it bpm" } ?: "—",
                recordId = stats.minEndHrRecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Highest SpO₂ at end",
                value = stats.maxEndSpO2?.let { "$it%" } ?: "—",
                recordId = stats.maxEndSpO2RecordId,
                onRecordClick = onRecordClick
            )
            ExtremeRow(
                label = "Lowest SpO₂ at end",
                value = stats.minEndSpO2?.let { "$it%" } ?: "—",
                recordId = stats.minEndSpO2RecordId,
                onRecordClick = onRecordClick
            )
        }
    }
}

/**
 * A stat row for an extreme value. When [recordId] is non-null the row is
 * tappable and shows a `›` chevron; tapping navigates to that record's detail.
 */
@Composable
private fun ExtremeRow(
    label: String,
    value: String,
    recordId: Long?,
    onRecordClick: (Long) -> Unit,
) {
    val clickable = recordId != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) Modifier.clickable { onRecordClick(recordId!!) }
                else Modifier
            ),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (clickable) EcgCyan else Color.White,
                    fontWeight = FontWeight.Medium
                )
                if (clickable) {
                    Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}.${centis.toString().padStart(2, '0')}s"
}

private fun formatMmSs(ms: Long): String {
    val totalSecs = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%02d:%02d".format(mins, secs)
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

private fun AdvancedApneaPhase.phaseColor(): Color = when (this) {
    AdvancedApneaPhase.IDLE               -> TextSecondary
    AdvancedApneaPhase.VENTILATION        -> ApneaVentilation
    AdvancedApneaPhase.APNEA              -> ApneaHold
    AdvancedApneaPhase.WAITING_FOR_BREATH -> EcgCyan
    AdvancedApneaPhase.WONKA_CRUISING     -> ApneaHold
    AdvancedApneaPhase.WONKA_ENDURANCE    -> ReadinessOrange
    AdvancedApneaPhase.RECOVERY           -> ApneaRecovery
    AdvancedApneaPhase.COMPLETE           -> ReadinessGreen
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
