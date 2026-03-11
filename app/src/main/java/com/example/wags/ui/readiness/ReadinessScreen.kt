package com.example.wags.ui.readiness

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.HrvMetrics
import com.example.wags.domain.model.ReadinessInterpretation
import com.example.wags.domain.model.ReadinessScore
import com.example.wags.ui.realtime.TachogramView
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessScreen(
    navController: NavController,
    viewModel: ReadinessViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // In production, deviceId comes from a shared ViewModel or saved preference
    val deviceId = "PLACEHOLDER_H10_ID"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("HRV Readiness") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.sessionState) {
                ReadinessSessionState.IDLE -> IdleContent(
                    onStart = { viewModel.startSession(deviceId, 120L) }
                )
                ReadinessSessionState.RECORDING -> RecordingContent(
                    state = state,
                    onCancel = { viewModel.cancelSession() }
                )
                ReadinessSessionState.PROCESSING -> ProcessingContent()
                ReadinessSessionState.COMPLETE -> CompleteContent(
                    state = state,
                    onReset = { viewModel.reset() }
                )
                ReadinessSessionState.ERROR -> ErrorContent(
                    message = state.errorMessage ?: "Unknown error",
                    onReset = { viewModel.reset() }
                )
            }
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("HRV Readiness", style = MaterialTheme.typography.headlineMedium)
        Text("2-minute resting HRV measurement", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Ensure Polar H10 is connected and worn correctly.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start 2-Minute Session")
        }
    }
}

@Composable
private fun RecordingContent(state: ReadinessUiState, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Recording...", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
        Text("${state.remainingSeconds}s remaining", style = MaterialTheme.typography.headlineLarge)
        LinearProgressIndicator(
            progress = {
                1f - (state.remainingSeconds.toFloat() / state.sessionDurationSeconds.toFloat())
            },
            modifier = Modifier.fillMaxWidth(),
            color = EcgCyan
        )
        state.liveRmssd?.let {
            Text(
                "Live RMSSD: ${String.format("%.1f", it)} ms",
                style = MaterialTheme.typography.bodyLarge,
                color = EcgCyan
            )
        }
        Text(
            "RR intervals collected: ${state.rrCount}",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ProcessingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = EcgCyan)
        Text("Analyzing HRV...", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CompleteContent(state: ReadinessUiState, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.readinessScore?.let { score ->
            ReadinessScoreDisplay(score = score)
        }
        state.hrvMetrics?.let { metrics ->
            HrvMetricsCard(metrics = metrics)
        }
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("New Session")
        }
    }
}

@Composable
private fun ReadinessScoreDisplay(score: ReadinessScore) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Readiness Score", style = MaterialTheme.typography.titleLarge)
            Text(
                text = score.score.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = when {
                    score.score >= 80 -> ReadinessGreen
                    score.score >= 60 -> ReadinessOrange
                    else -> ReadinessRed
                }
            )
            Text(
                text = score.interpretation.name,
                style = MaterialTheme.typography.labelLarge,
                color = interpretationColor(score.interpretation)
            )
            Text(
                text = "z-score: ${String.format("%.2f", score.zScore)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun HrvMetricsCard(metrics: HrvMetrics) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("HRV Metrics", style = MaterialTheme.typography.titleLarge)
            HrvMetricRow("RMSSD", "${String.format("%.1f", metrics.rmssdMs)} ms")
            HrvMetricRow("ln(RMSSD)", String.format("%.3f", metrics.lnRmssd))
            HrvMetricRow("SDNN", "${String.format("%.1f", metrics.sdnnMs)} ms")
            HrvMetricRow("pNN50", "${String.format("%.1f", metrics.pnn50Percent)} %")
            HrvMetricRow("Samples", metrics.sampleCount.toString())
        }
    }
}

@Composable
private fun HrvMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = EcgCyan)
    }
}

@Composable
private fun ErrorContent(message: String, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Error", style = MaterialTheme.typography.headlineMedium, color = ReadinessRed)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Try Again")
        }
    }
}

private fun interpretationColor(interpretation: ReadinessInterpretation) = when (interpretation) {
    ReadinessInterpretation.OPTIMAL -> ReadinessGreen
    ReadinessInterpretation.ELEVATED -> ReadinessBlue
    ReadinessInterpretation.REDUCED -> ReadinessOrange
    ReadinessInterpretation.LOW -> ReadinessRed
    ReadinessInterpretation.OVERREACHING -> ReadinessRed
}
