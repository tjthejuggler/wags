package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Table Training Setup Screen (normal O₂ / CO₂ tables)
//
// Reached from the "Table Training" card on the main apnea screen. Holds the
// table configuration (Personal Best, session length, difficulty) and the
// buttons that launch the O₂ / CO₂ table sessions — just like the other
// session-type setup screens.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableTrainingScreen(
    navController: NavController,
    viewModel: ApneaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val effectiveTod by viewModel.effectiveTod.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Table Training", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    TableHelpIcon(title = "Table Training", text = TABLE_TRAINING_HELP_TEXT)
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
            // Settings summary — pinned below the top bar so it stays visible
            // while scrolling; reflects the settings chosen on the main apnea screen
            ApneaSettingsSummaryBanner(
                lungVolume = state.selectedLungVolume,
                prepType   = state.prepType.name,
                // Dimension-aware bucket: hour number in BY_HOUR mode, Morning/Day/Night otherwise.
                timeOfDay  = effectiveTod,
                posture    = state.posture.name,
                audio      = state.audio.name
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Table Training Config Content (PB + length/difficulty + O2/CO2 launch)
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
