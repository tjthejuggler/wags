package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableStep
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.usecase.apnea.ApneaState
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaTableScreen(
    navController: NavController,
    tableType: String,
    viewModel: ApneaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"
    val parsedType = runCatching { ApneaTableType.valueOf(tableType) }.getOrDefault(ApneaTableType.O2)

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
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (state.personalBestMs <= 0L) {
            NoPbContent(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SessionStatusCard(
                        apneaState = state.apneaState,
                        currentRound = state.currentRound,
                        totalRounds = state.totalRounds,
                        remainingSeconds = state.remainingSeconds
                    )
                }
                item {
                    SessionControlRow(
                        apneaState = state.apneaState,
                        onStart = {
                            viewModel.loadTable(parsedType)
                            viewModel.startTableSession(deviceId)
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
                            isComplete = state.currentRound > step.roundNumber
                        )
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
            Text(
                text = apneaState.name,
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
                    color = ReadinessGreen)
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
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("Stop Session")
            }
        }
    }
}

@Composable
private fun TableStepRow(
    step: ApneaTableStep,
    isActive: Boolean,
    isComplete: Boolean
) {
    val containerColor = when {
        isActive -> SurfaceVariant
        isComplete -> SurfaceDark.copy(alpha = 0.5f)
        else -> SurfaceDark
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Hold", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${step.apneaDurationMs / 1000L}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isActive) ApneaHold else EcgCyan
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Breathe", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${step.ventilationDurationMs / 1000L}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isActive) ApneaVentilation else TextSecondary
                    )
                }
            }
            if (isActive) {
                Text("▶", style = MaterialTheme.typography.titleLarge, color = EcgCyan)
            } else if (isComplete) {
                Text("✓", style = MaterialTheme.typography.titleLarge, color = ReadinessGreen)
            }
        }
    }
}

private fun apneaStateColor(state: ApneaState) = when (state) {
    ApneaState.IDLE -> TextSecondary
    ApneaState.VENTILATION -> ApneaVentilation
    ApneaState.APNEA -> ApneaHold
    ApneaState.RECOVERY -> ApneaRecovery
    ApneaState.COMPLETE -> ReadinessGreen
}
