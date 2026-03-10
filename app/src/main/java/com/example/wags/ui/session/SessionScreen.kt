package com.example.wags.ui.session

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
import com.example.wags.domain.model.SessionType
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    navController: NavController,
    sessionType: String,
    viewModel: SessionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"
    val parsedType = runCatching { SessionType.valueOf(sessionType) }.getOrDefault(SessionType.MEDITATION)

    // Set session type when screen first loads (only if idle)
    LaunchedEffect(parsedType) {
        viewModel.setSessionType(parsedType)
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(parsedType.name.lowercase().replaceFirstChar { it.uppercase() }) },
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
                SessionState.IDLE -> IdleContent(
                    sessionType = parsedType,
                    sonificationEnabled = state.sonificationEnabled,
                    onSonificationToggle = { viewModel.setSonificationEnabled(!state.sonificationEnabled) },
                    onStart = { viewModel.startSession(deviceId) }
                )
                SessionState.ACTIVE -> ActiveContent(
                    state = state,
                    onStop = { viewModel.stopSession() }
                )
                SessionState.PROCESSING -> ProcessingContent()
                SessionState.COMPLETE -> CompleteContent(
                    state = state,
                    onReset = { viewModel.reset() }
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    sessionType: SessionType,
    sonificationEnabled: Boolean,
    onSonificationToggle: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            sessionType.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            "HR-tracked session with analytics",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("HR Sonification", style = MaterialTheme.typography.bodyLarge)
                    Text("Auditory heartbeat feedback", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = sonificationEnabled, onCheckedChange = { onSonificationToggle() })
            }
        }

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start Session")
        }
    }
}

@Composable
private fun ActiveContent(state: SessionUiState, onStop: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Session Active", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)

        val elapsed = state.elapsedSeconds
        val minutes = elapsed / 60L
        val seconds = elapsed % 60L
        Text(
            "${minutes}:${String.format("%02d", seconds)}",
            style = MaterialTheme.typography.displayLarge,
            color = EcgCyan
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveMetricCard(
                label = "Heart Rate",
                value = state.currentHrBpm?.let { "${String.format("%.0f", it)} BPM" } ?: "—",
                modifier = Modifier.weight(1f)
            )
            LiveMetricCard(
                label = "RMSSD",
                value = state.currentRmssd?.let { "${String.format("%.1f", it)} ms" } ?: "—",
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text("Stop Session")
        }
    }
}

@Composable
private fun LiveMetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
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
        Text("Analyzing session...", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CompleteContent(state: SessionUiState, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Session Complete", style = MaterialTheme.typography.headlineMedium, color = ReadinessGreen)

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Analytics", style = MaterialTheme.typography.titleLarge)
                state.avgHrBpm?.let {
                    AnalyticsRow("Avg HR", "${String.format("%.1f", it)} BPM")
                }
                state.hrSlopeBpmPerMin?.let {
                    val sign = if (it >= 0) "+" else ""
                    AnalyticsRow("HR Slope", "$sign${String.format("%.2f", it)} BPM/min")
                }
                state.startRmssdMs?.let {
                    AnalyticsRow("Start RMSSD", "${String.format("%.1f", it)} ms")
                }
                state.endRmssdMs?.let {
                    AnalyticsRow("End RMSSD", "${String.format("%.1f", it)} ms")
                }
                state.lnRmssdSlope?.let {
                    val sign = if (it >= 0) "+" else ""
                    AnalyticsRow("ln(RMSSD) Slope", "$sign${String.format("%.4f", it)}")
                }
            }
        }

        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("New Session")
        }
    }
}

@Composable
private fun AnalyticsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = EcgCyan)
    }
}
