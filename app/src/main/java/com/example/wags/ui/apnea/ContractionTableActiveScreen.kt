package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.usecase.apnea.ContractionTableMode
import com.example.wags.domain.usecase.apnea.ContractionTablePhase
import com.example.wags.domain.usecase.apnea.ContractionTableRoundResult
import com.example.wags.ui.apnea.pip.ContractionTablePipContent
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.common.pip.PipSessionHost
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

// ── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun ContractionTableActiveScreen(
    navController: NavController,
    viewModel: ContractionTableViewModel = hiltViewModel()
) {
    PipSessionHost(
        pipEnabled = true, // always eligible: covers pre-start, active, and result phases
        pipContent = { ContractionTablePipContent(viewModel = viewModel) },
        fullContent = { ContractionTableActiveScreenContent(navController, viewModel) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractionTableActiveScreenContent(
    navController: NavController,
    viewModel: ContractionTableViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session = state.sessionState
    val phase = session.phase
    val isActive = phase == ContractionTablePhase.BREATHE ||
            phase == ContractionTablePhase.CRUISE ||
            phase == ContractionTablePhase.STRUGGLE

    // ── Guards ───────────────────────────────────────────────────────────
    SessionBackHandler(enabled = isActive) {
        viewModel.cancelSession()
        navController.popBackStack(WagsRoutes.CONTRACTION_TABLE, inclusive = false)
    }
    KeepScreenOn(enabled = isActive || phase == ContractionTablePhase.COMPLETE)

    // ── Auto-start ──────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (!state.isSessionActive && phase == ContractionTablePhase.IDLE) {
            viewModel.startSession()
        }
    }

    // ── Wall-clock session start for total session time ─────────────────
    val sessionStartMs = remember { System.currentTimeMillis() }

    // ── PB celebration dialog ──────────────────────────────────────────
    state.newPersonalBest?.let { pbResult ->
        NewPersonalBestDialog(
            newPbMs = pbResult.durationMs,
            categoryDescription = pbResult.description,
            category = pbResult.category,
            onDismiss = { viewModel.dismissNewPersonalBest() }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.mode == ContractionTableMode.TILL_CONTRACTION) "Till Contraction"
                        else "Contraction Count"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isActive) viewModel.cancelSession()
                        navController.popBackStack(WagsRoutes.CONTRACTION_TABLE, inclusive = false)
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
        when (phase) {
            ContractionTablePhase.IDLE -> IdleContent(Modifier.padding(padding))
            ContractionTablePhase.BREATHE,
            ContractionTablePhase.CRUISE,
            ContractionTablePhase.STRUGGLE -> ActiveContent(
                modifier = Modifier.padding(padding),
                state = state,
                viewModel = viewModel
            )
            ContractionTablePhase.COMPLETE -> CompleteContent(
                modifier = Modifier.padding(padding),
                state = state,
                sessionStartMs = sessionStartMs,
                onViewDetails = { sessionId ->
                    viewModel.onSessionNavigated()
                    navController.navigate(WagsRoutes.contractionTableDetail(sessionId)) {
                        popUpTo(WagsRoutes.CONTRACTION_TABLE_ACTIVE) { inclusive = true }
                    }
                },
                onDone = {
                    navController.popBackStack(WagsRoutes.CONTRACTION_TABLE, inclusive = false)
                }
            )
        }
    }
}

// ── IDLE — brief "Starting…" ────────────────────────────────────────────────

@Composable
private fun IdleContent(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Starting…",
            style = MaterialTheme.typography.headlineMedium,
            color = TextSecondary
        )
    }
}

// ── BREATHE / CRUISE / STRUGGLE — active drill ──────────────────────────────

@Composable
private fun ActiveContent(
    modifier: Modifier,
    state: ContractionTableUiState,
    viewModel: ContractionTableViewModel
) {
    val session = state.sessionState
    val phase = session.phase

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Phase label ─────────────────────────────────────────────────
        val phaseLabel = when (phase) {
            ContractionTablePhase.BREATHE -> "BREATHE"
            ContractionTablePhase.CRUISE -> "CRUISING"
            ContractionTablePhase.STRUGGLE -> "STRUGGLE"
            else -> ""
        }
        Text(
            text = phaseLabel,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(Modifier.height(4.dp))

        // ── Round indicator ─────────────────────────────────────────────
        Text(
            text = "Round ${session.currentRound} of ${session.totalRounds}",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary
        )

        // ── Phase-specific hint ─────────────────────────────────────────
        val hint = when (phase) {
            ContractionTablePhase.BREATHE -> "Recover — breathe normally and relax"
            ContractionTablePhase.CRUISE ->
                if (state.mode == ContractionTableMode.TILL_CONTRACTION)
                    "Hold until your diaphragm contracts for the first time"
                else
                    "Hold — tap the button at your FIRST contraction"
            ContractionTablePhase.STRUGGLE ->
                "Keep holding — tap the button for each contraction"
            else -> ""
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // ── Giant timer ─────────────────────────────────────────────────
        Text(
            text = formatMs(session.timerMs),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // ── Secondary metrics ───────────────────────────────────────────
        when (phase) {
            ContractionTablePhase.STRUGGLE -> {
                // Frozen cruise time + contraction progress
                Text(
                    text = "Cruise ${formatMs(session.cruiseElapsedMs)} · " +
                            "Contractions ${session.contractionsInHold}/${session.contractionTarget}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
            ContractionTablePhase.CRUISE -> {
                Text(
                    text = "Total hold ${formatMs(session.realTimeTotalHoldTimeMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            else -> {}
        }

        // ── Voice/Vibration toggles ──────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            IconButton(
                onClick = { viewModel.setVoiceEnabled(!state.voiceEnabled) },
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = "🔊",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = if (!state.voiceEnabled) Modifier.grayscale() else Modifier,
                    color = if (state.voiceEnabled) TextPrimary else TextDisabled
                )
            }

            IconButton(
                onClick = { viewModel.setVibrationEnabled(!state.vibrationEnabled) },
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = "〰",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.vibrationEnabled) TextPrimary else TextDisabled
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Primary action button ───────────────────────────────────────
        when (phase) {
            ContractionTablePhase.CRUISE -> {
                Button(
                    onClick = { viewModel.logFirstContraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
                ) {
                    Text(
                        text = "First Contraction",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.endHoldEarly() },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, TextSecondary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("End Hold", style = MaterialTheme.typography.titleSmall)
                }
            }
            ContractionTablePhase.STRUGGLE -> {
                Button(
                    onClick = { viewModel.logContraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
                ) {
                    Text(
                        text = "Contraction  (${session.contractionsInHold}/${session.contractionTarget})",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.endHoldEarly() },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, TextSecondary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("End Hold", style = MaterialTheme.typography.titleSmall)
                }
            }
            else -> Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // ── Stop button ─────────────────────────────────────────────────
        OutlinedButton(
            onClick = { viewModel.stopSession() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            border = BorderStroke(2.dp, TextSecondary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("Stop Table", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ── COMPLETE — session summary ──────────────────────────────────────────────

@Composable
private fun CompleteContent(
    modifier: Modifier,
    state: ContractionTableUiState,
    sessionStartMs: Long,
    onViewDetails: (Long) -> Unit,
    onDone: () -> Unit
) {
    val session = state.sessionState
    val rounds = session.roundResults
    val completedRounds = rounds.count { it.completed }
    val sessionEndMs = remember { System.currentTimeMillis() }
    val totalSessionMs = sessionEndMs - sessionStartMs

    // Telemetry aggregates from the ViewModel's live values (last known)
    val hr = state.liveHr
    val spo2 = state.liveSpO2

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            "Table Complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(Modifier.height(24.dp))

        // ── Summary card ────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryRow("Rounds completed", "$completedRounds / ${rounds.size}")
                session.bestCruiseMs?.let { SummaryRow("Best cruise", formatMs(it)) }
                SummaryRow("Longest hold", formatMs(session.longestHoldMs))
                SummaryRow("Total hold time", formatMs(session.totalHoldTimeMs))
                SummaryRow("Total session time", formatMs(totalSessionMs))
                if (spo2 != null) {
                    SummaryRow("SpO₂", "$spo2%")
                }
                if (hr != null) {
                    SummaryRow("HR", "$hr bpm")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Round-by-round list ─────────────────────────────────────────
        RoundsList(rounds = rounds, mode = state.mode)

        Spacer(Modifier.height(16.dp))

        // ── Action buttons ──────────────────────────────────────────────
        if (state.completedSessionId != null) {
            Button(
                onClick = { onViewDetails(state.completedSessionId) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("View Details")
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("Done")
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

@Composable
private fun RoundsList(
    rounds: List<ContractionTableRoundResult>,
    mode: ContractionTableMode
) {
    if (rounds.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Rounds",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            rounds.sortedByDescending { it.roundNumber }.forEach { r ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = buildString {
                            append("R${r.roundNumber}")
                            if (r.endedEarly) append(" · ended early")
                            else if (r.completed) append(" ✓")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.completed) TextPrimary else TextSecondary
                    )
                    Text(
                        text = buildString {
                            r.cruiseMs?.let { append("cruise ${formatMs(it)}") }
                            if (mode == ContractionTableMode.CONTRACTION_COUNT) {
                                if (isNotEmpty()) append(" · ")
                                append("${r.contractionsLogged}c")
                            }
                            if (isNotEmpty()) append(" · ")
                            append(formatMs(r.totalHoldMs))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
