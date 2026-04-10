package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.usecase.apnea.MinBreathHoldResult
import com.example.wags.domain.usecase.apnea.MinBreathPhase
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinBreathActiveScreen(
    navController: NavController,
    viewModel: MinBreathViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val phase = state.sessionState.phase
    val isActive = phase == MinBreathPhase.HOLD || phase == MinBreathPhase.BREATHING

    SessionBackHandler(enabled = isActive) {
        viewModel.stopSession()
        navController.popBackStack()
    }
    KeepScreenOn(enabled = isActive || phase == MinBreathPhase.COMPLETE)

    LaunchedEffect(Unit) {
        if (!state.isSessionActive) viewModel.startSession()
    }

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
                title = { Text("Min Breath") },
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
            MinBreathPhase.IDLE -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Starting…", style = MaterialTheme.typography.headlineMedium, color = TextSecondary) }

            MinBreathPhase.HOLD -> HoldContent(
                modifier = Modifier.padding(padding), state = state,
                onContraction = { viewModel.markContraction() },
                onBreath = { viewModel.switchToBreathing() },
                onStop = { viewModel.stopSession() }
            )
            MinBreathPhase.BREATHING -> BreathingContent(
                modifier = Modifier.padding(padding), state = state,
                onHold = { viewModel.switchToHolding() },
                onStop = { viewModel.stopSession() }
            )
            MinBreathPhase.COMPLETE -> CompleteContent(
                modifier = Modifier.padding(padding), state = state,
                onViewDetails = { recordId ->
                    navController.navigate(WagsRoutes.apneaRecordDetail(recordId)) {
                        popUpTo("min_breath_active") { inclusive = true }
                    }
                    viewModel.onSessionNavigated()
                },
                onDone = { navController.popBackStack() }
            )
        }
    }
}

// ── HOLD phase ──────────────────────────────────────────────────────────────

@Composable
private fun HoldContent(
    modifier: Modifier, state: MinBreathUiState,
    onContraction: () -> Unit, onBreath: () -> Unit, onStop: () -> Unit
) {
    val session = state.sessionState
    Column(modifier = modifier.fillMaxSize()) {
        InfoBar(
            sessionRemainingMs = session.sessionRemainingMs,
            phaseLabel = "HOLD",
            phaseElapsedMs = session.currentPhaseElapsedMs,
            holdNumber = session.currentHoldNumber,
            totalHoldTimeMs = session.totalHoldTimeMs,
            totalBreathTimeMs = session.totalBreathTimeMs,
            hr = state.liveHr, spo2 = state.liveSpO2, onStop = onStop
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (session.currentHoldContractionMs == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onContraction,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant, contentColor = TextPrimary)
                    ) {
                        Text("First\nContraction", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = onBreath,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = TextPrimary)
                    ) {
                        Text("Breath", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            } else {
                Button(
                    onClick = onBreath,
                    modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = TextPrimary)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Breath", style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                        Text("⚡ ${formatMmSs(session.currentHoldContractionMs!!)}",
                            style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── BREATHING phase ─────────────────────────────────────────────────────────

@Composable
private fun BreathingContent(
    modifier: Modifier, state: MinBreathUiState,
    onHold: () -> Unit, onStop: () -> Unit
) {
    val session = state.sessionState
    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("BREATHING", style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Remaining: ${formatMmSs(session.sessionRemainingMs)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    SmallStopButton(onStop)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Breath: ${formatMmSsTenths(session.currentPhaseElapsedMs)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Total breath: ${formatMmSs(session.totalBreathTimeMs + session.currentPhaseElapsedMs)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onHold,
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant, contentColor = Color.White)
            ) {
                Text("HOLD", style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, fontSize = 48.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── COMPLETE phase ──────────────────────────────────────────────────────────

@Composable
private fun CompleteContent(
    modifier: Modifier, state: MinBreathUiState,
    onViewDetails: (Long) -> Unit, onDone: () -> Unit
) {
    val session = state.sessionState
    val holds = session.holdResults
    val totalMs = session.totalHoldTimeMs + session.totalBreathTimeMs
    val holdPct = if (totalMs > 0) session.totalHoldTimeMs.toDouble() / totalMs * 100.0 else 0.0
    val breathPct = if (totalMs > 0) session.totalBreathTimeMs.toDouble() / totalMs * 100.0 else 0.0
    val longestMs = holds.maxOfOrNull { it.holdDurationMs } ?: 0L
    val avgMs = if (holds.isNotEmpty()) holds.sumOf { it.holdDurationMs } / holds.size else 0L

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Session Complete", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val durMin = state.sessionDurationSec / 60
                val durSec = (state.sessionDurationSec % 60).toString().padStart(2, '0')
                SummaryRow("Session duration", "$durMin:$durSec")
                SummaryRow("Total hold time", "${formatMmSs(session.totalHoldTimeMs)} (${"%.1f".format(holdPct)}%)")
                SummaryRow("Total breath time", "${formatMmSs(session.totalBreathTimeMs)} (${"%.1f".format(breathPct)}%)")
                SummaryRow("Number of holds", "${holds.size}")
                SummaryRow("Longest hold", formatMmSs(longestMs))
                SummaryRow("Average hold", formatMmSs(avgMs))
                if (state.liveHr != null) SummaryRow("HR", "${state.liveHr} bpm")
                if (state.liveSpO2 != null) SummaryRow("Lowest SpO₂", "${state.liveSpO2}%")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (holds.isNotEmpty()) {
            Text("Completed Holds", style = MaterialTheme.typography.labelMedium,
                color = TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(holds.sortedByDescending { it.holdNumber }, key = { it.holdNumber }) { hold ->
                    HoldRow(hold)
                }
            }
        }
        Spacer(Modifier.weight(1f))

        if (state.completedRecordId != null) {
            Button(
                onClick = { onViewDetails(state.completedRecordId) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) { Text("View Details") }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onDone, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) { Text("Done") }
        Spacer(Modifier.height(8.dp))
    }
}

// ── Shared composables ──────────────────────────────────────────────────────

@Composable
private fun InfoBar(
    sessionRemainingMs: Long, phaseLabel: String, phaseElapsedMs: Long,
    holdNumber: Int, totalHoldTimeMs: Long, totalBreathTimeMs: Long,
    hr: Int?, spo2: Int?, onStop: () -> Unit
) {
    Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(phaseLabel, style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary, fontWeight = FontWeight.Bold)
                Text("Remaining: ${formatMmSs(sessionRemainingMs)}",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                SmallStopButton(onStop)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hold: ${formatMmSsTenths(phaseElapsedMs)}",
                    style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text("Hold #$holdNumber", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("H: ${formatMmSs(totalHoldTimeMs + phaseElapsedMs)}",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("B: ${formatMmSs(totalBreathTimeMs)}",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (hr != null) Text("❤️ $hr", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (spo2 != null) Text("SpO₂ $spo2%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun SmallStopButton(onStop: () -> Unit) {
    OutlinedButton(
        onClick = onStop,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        border = BorderStroke(1.dp, TextSecondary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
    ) { Text("Stop", style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun HoldRow(hold: MinBreathHoldResult) {
    val contraction = hold.firstContractionMs?.let { " ⚡ at ${formatMmSs(it)}" } ?: ""
    Text("Hold #${hold.holdNumber}: ${formatMmSs(hold.holdDurationMs)}$contraction",
        style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}

// ── Formatting helpers ──────────────────────────────────────────────────────

private fun formatMmSs(ms: Long): String {
    val t = (ms / 1000).toInt(); val m = t / 60; val s = t % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun formatMmSsTenths(ms: Long): String {
    val t = ms / 1000; val m = (t / 60).toInt(); val s = (t % 60).toInt()
    return "$m:${s.toString().padStart(2, '0')}.${((ms % 1000) / 100).toInt()}"
}
