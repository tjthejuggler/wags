package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.usecase.apnea.ProgressiveO2Phase
import com.example.wags.domain.usecase.apnea.ProgressiveO2RoundResult
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

// ── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveO2ActiveScreen(
    navController: NavController,
    viewModel: ProgressiveO2ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session = state.sessionState
    val phase = session.phase

    val isActive = phase == ProgressiveO2Phase.HOLD || phase == ProgressiveO2Phase.BREATHING

    // ── Guards ───────────────────────────────────────────────────────────
    SessionBackHandler(enabled = isActive) {
        viewModel.stopSession()
        navController.popBackStack()
    }
    KeepScreenOn(enabled = isActive || phase == ProgressiveO2Phase.COMPLETE)

    // ── Auto-start ──────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (!state.isSessionActive) {
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
                title = { Text("Progressive O₂") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isActive) viewModel.stopSession()
                        navController.popBackStack()
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when (phase) {
            ProgressiveO2Phase.IDLE -> IdleContent(Modifier.padding(padding))
            ProgressiveO2Phase.HOLD,
            ProgressiveO2Phase.BREATHING -> ActiveContent(
                modifier = Modifier.padding(padding),
                state = state,
                onFirstContraction = { viewModel.logFirstContraction() },
                onStop = { viewModel.stopSession() }
            )
            ProgressiveO2Phase.COMPLETE -> CompleteContent(
                modifier = Modifier.padding(padding),
                state = state,
                sessionStartMs = sessionStartMs,
                onViewDetails = { recordId ->
                    navController.navigate(WagsRoutes.apneaRecordDetail(recordId)) {
                        popUpTo(WagsRoutes.PROGRESSIVE_O2_ACTIVE) { inclusive = true }
                    }
                },
                onDone = { navController.popBackStack() }
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

// ── HOLD / BREATHING — active drill ─────────────────────────────────────────

@Composable
private fun ActiveContent(
    modifier: Modifier,
    state: ProgressiveO2UiState,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    val session = state.sessionState
    val phase = session.phase
    val phaseLabel = if (phase == ProgressiveO2Phase.HOLD) "HOLD" else "BREATHE"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Phase label ─────────────────────────────────────────────────
        Text(
            text = phaseLabel,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(Modifier.height(4.dp))

        // ── Round indicator ─────────────────────────────────────────────
        Text(
            text = "Round ${session.currentRound}",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary
        )

        // ── Target / next hold info ─────────────────────────────────────
        if (phase == ProgressiveO2Phase.HOLD) {
            Text(
                text = "Target: ${formatMmSs(session.holdDurationMs)}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
        } else {
            val nextHoldMs = (session.currentRound + 1) * 15_000L
            Text(
                text = "Next hold: ${formatMmSs(nextHoldMs)}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Giant countdown timer ───────────────────────────────────────
        Text(
            text = formatMmSs(session.timerMs),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        // ── Total hold time ─────────────────────────────────────────────
        Text(
            text = "Total hold: ${formatMmSs(session.totalHoldTimeMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        // ── Live HR / SpO₂ ──────────────────────────────────────────────
        LiveVitals(hr = state.liveHr, spo2 = state.liveSpO2)

        Spacer(Modifier.weight(1f))

        // ── Large "First Contraction" button during HOLD phase ──────────
        if (phase == ProgressiveO2Phase.HOLD) {
            val alreadyLogged = state.sessionState.firstContractionMs != null
            Button(
                onClick = onFirstContraction,
                enabled = !alreadyLogged,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (alreadyLogged) SurfaceVariant else ButtonPrimary,
                    disabledContainerColor = SurfaceVariant
                )
            ) {
                Text(
                    text = if (alreadyLogged) "✓ Contraction Logged" else "First Contraction",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (alreadyLogged) TextSecondary else TextPrimary
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Stop button ─────────────────────────────────────────────────
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            border = BorderStroke(2.dp, TextSecondary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("Stop Drill", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── COMPLETE — session summary ──────────────────────────────────────────────

@Composable
private fun CompleteContent(
    modifier: Modifier,
    state: ProgressiveO2UiState,
    sessionStartMs: Long,
    onViewDetails: (Long) -> Unit,
    onDone: () -> Unit
) {
    val session = state.sessionState
    val rounds = session.roundResults
    val completedRounds = rounds.count { it.completed }
    val maxCompletedHoldMs = rounds.filter { it.completed }.maxOfOrNull { it.targetHoldMs } ?: 0L
    val totalSessionMs = System.currentTimeMillis() - sessionStartMs

    // Telemetry aggregates from the ViewModel's live values (last known)
    val hr = state.liveHr
    val spo2 = state.liveSpO2

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            "Session Complete",
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
                SummaryRow("Rounds completed", "$completedRounds")
                SummaryRow("Max hold reached", formatMmSs(maxCompletedHoldMs))
                SummaryRow("Total hold time", formatMmSs(session.totalHoldTimeMs))
                SummaryRow("Total session time", formatMmSs(totalSessionMs))
                if (spo2 != null) {
                    SummaryRow("SpO₂", "$spo2%")
                }
                if (hr != null) {
                    SummaryRow("HR", "$hr bpm")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Completed rounds list ───────────────────────────────────────
        CompletedRoundsList(rounds = rounds)

        Spacer(Modifier.weight(1f))

        // ── Action buttons ──────────────────────────────────────────────
        if (state.completedRecordId != null) {
            Button(
                onClick = { onViewDetails(state.completedRecordId) },
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

// ── Shared sub-composables ──────────────────────────────────────────────────

@Composable
private fun LiveVitals(hr: Int?, spo2: Int?) {
    if (hr == null && spo2 == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        if (hr != null) {
            Text("❤️ $hr bpm", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        if (spo2 != null) {
            Text("SpO₂ $spo2%", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

@Composable
private fun CompletedRoundsList(rounds: List<ProgressiveO2RoundResult>) {
    if (rounds.isEmpty()) return

    val sortedRounds = rounds.sortedByDescending { it.roundNumber }

    Text(
        "Completed Rounds",
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(sortedRounds, key = { it.roundNumber }) { round ->
            RoundRow(round)
        }
    }
}

@Composable
private fun RoundRow(round: ProgressiveO2RoundResult) {
    val icon = if (round.completed) "✓" else "✗"
    val iconColor = if (round.completed) TextPrimary else TextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Round ${round.roundNumber}: ${formatMmSs(round.targetHoldMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            icon,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = iconColor
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}

// ── Formatting helper ───────────────────────────────────────────────────────

private fun formatMmSs(ms: Long): String {
    val totalSecs = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}
