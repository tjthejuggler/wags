package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import kotlinx.coroutines.delay
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

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Apnea Training") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 1. Global Settings (top) ───────────────────────────────────
            ApneaSettingsSection(
                selectedLungVolume = state.selectedLungVolume,
                prepType = state.prepType,
                onLungVolumeChange = { viewModel.setLungVolume(it) },
                onPrepTypeChange = { viewModel.setPrepType(it) }
            )

            HorizontalDivider(color = SurfaceVariant)

            // ── 2. Free Hold / Best Time ───────────────────────────────────
            FreeHoldSection(
                freeHoldActive = state.freeHoldActive,
                freeHoldDurationMs = state.freeHoldDurationMs,
                bestTimeMs = state.bestTimeForSettingsMs,
                showTimer = state.showTimer,
                onShowTimerChange = { viewModel.setShowTimer(it) },
                onStart = { viewModel.startFreeHold(deviceId) },
                onStop = { viewModel.stopFreeHold() }
            )

            HorizontalDivider(color = SurfaceVariant)

            // ── 3. Table Training & Advanced ──────────────────────────────
            PersonalBestSection(
                personalBestMs = state.personalBestMs,
                selectedLength = state.selectedLength,
                selectedDifficulty = state.selectedDifficulty,
                onSetPersonalBest = { viewModel.setPersonalBest(it) },
                onLengthSelected = { viewModel.setLength(it) },
                onDifficultySelected = { viewModel.setDifficulty(it) },
                onNavigateO2 = { navController.navigate(WagsRoutes.apneaTable("O2")) },
                onNavigateCo2 = { navController.navigate(WagsRoutes.apneaTable("CO2")) },
                onNavigateAdvanced = { modality ->
                    navController.navigate(
                        WagsRoutes.advancedApnea(modality.name, state.selectedLength.name)
                    )
                },
                onNavigateAnalytics = {
                    navController.navigate(WagsRoutes.SESSION_ANALYTICS_HISTORY)
                },
                onNavigateHistory = {
                    navController.navigate(
                        WagsRoutes.apneaHistory(state.selectedLungVolume, state.prepType.name)
                    )
                }
            )

            HorizontalDivider(color = SurfaceVariant)

            // ── 4. Recent Records (filtered by current settings) ──────────
            if (state.recentRecords.isNotEmpty()) {
                RecentRecordsSection(
                    records = state.recentRecords,
                    onRecordClick = { record ->
                        navController.navigate(WagsRoutes.apneaRecordDetail(record.recordId))
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Section (global for all features)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ApneaSettingsSection(
    selectedLungVolume: String,
    prepType: PrepType,
    onLungVolumeChange: (String) -> Unit,
    onPrepTypeChange: (PrepType) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            // Lung Volume — 3-chip toggle
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

            // Prep Type — 3-chip toggle (No Prep / Resonance / Hyper)
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
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Free Hold / Best Time Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeHoldSection(
    freeHoldActive: Boolean,
    freeHoldDurationMs: Long,
    bestTimeMs: Long,
    showTimer: Boolean,
    onShowTimerChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // Live elapsed timer — ticks every 100ms while hold is active
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val holdStartTime = remember { mutableLongStateOf(0L) }

    LaunchedEffect(freeHoldActive) {
        if (freeHoldActive) {
            holdStartTime.longValue = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - holdStartTime.longValue
                delay(100L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row: "Best Time" title + "Show timer" checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Best Time", style = MaterialTheme.typography.titleLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Show timer", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Checkbox(
                        checked = showTimer,
                        onCheckedChange = onShowTimerChange
                    )
                }
            }

            // Always show best time for current settings
            if (bestTimeMs > 0L) {
                Text(
                    "🏆 ${formatMs(bestTimeMs)}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = EcgCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            if (freeHoldActive) {
                // Live timer — only shown when showTimer is checked
                if (showTimer) {
                    Text(
                        formatMs(elapsedMs),
                        style = MaterialTheme.typography.displayLarge,
                        color = ApneaHold,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "HOLD",
                        style = MaterialTheme.typography.displayLarge,
                        color = ApneaHold
                    )
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonDanger,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Release") }
            } else {
                if (freeHoldDurationMs > 0L) {
                    Text(
                        "Last: ${formatMs(freeHoldDurationMs)}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = EcgCyan
                    )
                }
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonSuccess,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start Hold") }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}.${centis.toString().padStart(2, '0')}s"
}

// ─────────────────────────────────────────────────────────────────────────────
// Table Training Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PersonalBestSection(
    personalBestMs: Long,
    selectedLength: TableLength,
    selectedDifficulty: TableDifficulty,
    onSetPersonalBest: (Long) -> Unit,
    onLengthSelected: (TableLength) -> Unit,
    onDifficultySelected: (TableDifficulty) -> Unit,
    onNavigateO2: () -> Unit,
    onNavigateCo2: () -> Unit,
    onNavigateAdvanced: (TrainingModality) -> Unit,
    onNavigateAnalytics: () -> Unit,
    onNavigateHistory: () -> Unit
) {
    var pbInput by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row with History button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Table Training", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(
                    onClick = onNavigateHistory,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("📋 History", style = MaterialTheme.typography.labelMedium)
                }
            }

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

            // Session Length selector
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

            // Difficulty selector
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

            // Standard table buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onNavigateO2,
                    enabled = personalBestMs > 0L,
                    modifier = Modifier.weight(1f)
                ) { Text("O2 Table") }
                TableHelpIcon(title = O2_HELP_TITLE, text = O2_HELP_TEXT)

                Button(
                    onClick = onNavigateCo2,
                    enabled = personalBestMs > 0L,
                    modifier = Modifier.weight(1f)
                ) { Text("CO2 Table") }
                TableHelpIcon(title = CO2_HELP_TITLE, text = CO2_HELP_TEXT)
            }

            HorizontalDivider(color = SurfaceVariant)
            Text("Advanced Modalities", style = MaterialTheme.typography.titleMedium)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onNavigateAdvanced(TrainingModality.PROGRESSIVE_O2) },
                    modifier = Modifier.weight(1f)
                ) { Text("Progressive O₂") }
                TableHelpIcon(title = PROGRESSIVE_O2_HELP_TITLE, text = PROGRESSIVE_O2_HELP_TEXT)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onNavigateAdvanced(TrainingModality.MIN_BREATH) },
                    enabled = personalBestMs > 0L,
                    modifier = Modifier.weight(1f)
                ) { Text("Min Breath") }
                TableHelpIcon(title = MIN_BREATH_HELP_TITLE, text = MIN_BREATH_HELP_TEXT)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onNavigateAdvanced(TrainingModality.WONKA_FIRST_CONTRACTION) },
                    modifier = Modifier.weight(1f)
                ) { Text("Wonka: Till Contraction") }
                TableHelpIcon(title = WONKA_HELP_TITLE, text = WONKA_HELP_TEXT)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onNavigateAdvanced(TrainingModality.WONKA_ENDURANCE) },
                    modifier = Modifier.weight(1f)
                ) { Text("Wonka: Endurance") }
                TableHelpIcon(title = WONKA_HELP_TITLE, text = WONKA_HELP_TEXT)
            }

            HorizontalDivider(color = SurfaceVariant)

            OutlinedButton(
                onClick = onNavigateAnalytics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📊 View Session Analytics")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recent Records Section (settings-filtered, clickable)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentRecordsSection(
    records: List<ApneaRecordEntity>,
    onRecordClick: (ApneaRecordEntity) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent Records", style = MaterialTheme.typography.titleLarge)
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    formatMs(record.durationMs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = EcgCyan,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    record.tableType ?: "Free Hold",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary
                )
            }
        }
    }
    HorizontalDivider(color = SurfaceVariant.copy(alpha = 0.5f))
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

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
