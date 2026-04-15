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
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    navController: NavController,
    sessionType: String,
    viewModel: SessionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val parsedType = runCatching { SessionType.valueOf(sessionType) }.getOrDefault(SessionType.MEDITATION)

    val isActive = state.sessionState == SessionState.ACTIVE ||
            state.sessionState == SessionState.PROCESSING

    // Keep screen on during COMPLETE too so the user can review results
    val keepScreenOn = isActive || state.sessionState == SessionState.COMPLETE

    SessionBackHandler(enabled = isActive) { navController.popBackStack() }
    KeepScreenOn(enabled = keepScreenOn)

    LaunchedEffect(parsedType) {
        viewModel.setSessionType(parsedType)
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        parsedType.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2, onClick = { navController.navigate(WagsRoutes.SETTINGS) })
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
                    hasHrMonitor = state.hasHrMonitor,
                    connectedDeviceId = state.connectedDeviceId,
                    sonificationEnabled = state.sonificationEnabled,
                    onSonificationToggle = { viewModel.setSonificationEnabled(!state.sonificationEnabled) },
                    onStart = { deviceId -> viewModel.startSession(deviceId) }
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
    hasHrMonitor: Boolean,
    connectedDeviceId: String?,
    sonificationEnabled: Boolean,
    onSonificationToggle: () -> Unit,
    onStart: (String?) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            sessionType.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.headlineMedium
        )

        MonitorStatusBanner(hasHrMonitor = hasHrMonitor, deviceId = connectedDeviceId)

        if (hasHrMonitor) {
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
        }

        Button(
            onClick = { onStart(connectedDeviceId) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasHrMonitor) "Start Session" else "Start Session (no HR monitor)")
        }
    }
}

@Composable
private fun MonitorStatusBanner(hasHrMonitor: Boolean, deviceId: String?) {
    val bgColor = if (hasHrMonitor) SurfaceDark else SurfaceVariant
    val dot = if (hasHrMonitor) "●" else "○"
    val dotColor = if (hasHrMonitor) TextPrimary else TextDisabled
    val text = if (hasHrMonitor) {
        "Monitor connected: ${deviceId ?: "Unknown"}"
    } else {
        "No HR monitor — session will be recorded without HR data"
    }

    Card(colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(dot, color = dotColor, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ActiveContent(state: SessionUiState, onStop: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Session Active", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)

        val elapsed = state.elapsedSeconds
        val minutes = elapsed / 60L
        val seconds = elapsed % 60L
        Text(
            "${minutes}:${String.format("%02d", seconds)}",
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimary
        )

        if (state.hasHrMonitor) {
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
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                Text(
                    "Timer only — no HR monitor connected",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
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
            Text(value, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        }
    }
}

@Composable
private fun ProcessingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = TextSecondary)
        Text("Analyzing session...", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CompleteContent(state: SessionUiState, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Session Complete", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Summary", style = MaterialTheme.typography.titleLarge)
                AnalyticsRow(
                    label = "Monitor",
                    value = state.monitorId ?: "None (no HR data)"
                )

                if (state.avgHrBpm != null) {
                    Divider(color = TextDisabled.copy(alpha = 0.3f))
                    Text("Analytics", style = MaterialTheme.typography.titleMedium)
                    AnalyticsRow("Avg HR", "${String.format("%.1f", state.avgHrBpm)} BPM")
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
                } else {
                    Text(
                        "Connect a monitor next time for full HR analytics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
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
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
    }
}
