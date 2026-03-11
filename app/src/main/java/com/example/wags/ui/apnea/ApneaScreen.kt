package com.example.wags.ui.apnea

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

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
            // Free Hold Section
            FreeHoldSection(
                freeHoldActive = state.freeHoldActive,
                freeHoldDurationMs = state.freeHoldDurationMs,
                onStart = { viewModel.startFreeHold(deviceId) },
                onStop = { viewModel.stopFreeHold() }
            )

            HorizontalDivider(color = SurfaceVariant)

            // Settings
            FreeHoldSettings(
                selectedLungVolume = state.selectedLungVolume,
                hyperventilationPrep = state.hyperventilationPrep,
                onLungVolumeChange = { viewModel.setLungVolume(it) },
                onHyperventilationChange = { viewModel.setHyperventilationPrep(it) }
            )

            HorizontalDivider(color = SurfaceVariant)

            // Personal Best & Table Navigation
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
                }
            )

            HorizontalDivider(color = SurfaceVariant)

            // Recent Records
            if (state.recentRecords.isNotEmpty()) {
                RecentRecordsSection(records = state.recentRecords)
            }
        }
    }
}

@Composable
private fun FreeHoldSection(
    freeHoldActive: Boolean,
    freeHoldDurationMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Free Hold", style = MaterialTheme.typography.titleLarge)

            if (freeHoldActive) {
                Text(
                    "HOLD",
                    style = MaterialTheme.typography.displayLarge,
                    color = ApneaHold
                )
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
                    val seconds = freeHoldDurationMs / 1000L
                    val millis = (freeHoldDurationMs % 1000L) / 10L
                    Text(
                        "Last: ${seconds}s ${millis}ms",
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

@Composable
private fun FreeHoldSettings(
    selectedLungVolume: String,
    hyperventilationPrep: Boolean,
    onLungVolumeChange: (String) -> Unit,
    onHyperventilationChange: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Lung Volume", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FULL", "PARTIAL", "EMPTY").forEach { volume ->
                    FilterChip(
                        selected = selectedLungVolume == volume,
                        onClick = { onLungVolumeChange(volume) },
                        label = { Text(volume) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hyperventilation Prep", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = hyperventilationPrep,
                    onCheckedChange = onHyperventilationChange
                )
            }
        }
    }
}

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
    onNavigateAnalytics: () -> Unit
) {
    var pbInput by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Table Training", style = MaterialTheme.typography.titleLarge)

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

            // Progressive O2
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

            // Min Breath
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

            // Wonka: Till Contraction
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

            // Wonka: Endurance
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

@Composable
private fun RecentRecordsSection(records: List<ApneaRecordEntity>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent Records", style = MaterialTheme.typography.titleLarge)
            records.take(5).forEach { record ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${record.durationMs / 1000L}s — ${record.lungVolume}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        record.tableType ?: "Free",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
