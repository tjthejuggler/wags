package com.example.wags.ui.meditation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationSessionDetailScreen(
    navController: NavController,
    viewModel: MeditationSessionDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Session Detail") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = EcgCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = EcgCyan)
                }
            }
            state.session == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Session not found.", color = TextSecondary)
                }
            }
            else -> {
                val session = state.session!!
                val audio = state.audio
                val zone = ZoneId.systemDefault()
                val dateLabel = Instant.ofEpochMilli(session.timestamp).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy  ·  h:mm a"))
                val durationMin = session.durationMs / 60_000L
                val durationSec = (session.durationMs % 60_000L) / 1_000L
                val audioName = audio?.let {
                    if (it.isNone) "Silent Meditation" else it.fileName
                } ?: "Unknown"

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Date / time header ─────────────────────────────────
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    // ── Overview card ──────────────────────────────────────
                    DetailCard(title = "Overview") {
                        DetailRow("Audio", audioName)
                        DetailRow("Duration", "${durationMin}m ${durationSec}s")
                        DetailRow("Monitor", session.monitorId ?: "None")
                        // Source URL if available
                        audio?.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            DetailRow("Source URL", url)
                        }
                    }

                    // ── HR analytics card ──────────────────────────────────
                    if (session.avgHrBpm != null) {
                        DetailCard(title = "Heart Rate Analytics") {
                            session.avgHrBpm.let {
                                DetailRow("Avg HR", "${String.format("%.1f", it)} BPM")
                            }
                            session.hrSlopeBpmPerMin?.let {
                                val sign = if (it >= 0) "+" else ""
                                DetailRow("HR Slope", "$sign${String.format("%.2f", it)} BPM/min")
                            }
                        }
                    }

                    // ── HRV analytics card ─────────────────────────────────
                    if (session.startRmssdMs != null || session.endRmssdMs != null || session.lnRmssdSlope != null) {
                        DetailCard(title = "HRV Analytics") {
                            session.startRmssdMs?.let {
                                DetailRow("Start RMSSD", "${String.format("%.1f", it)} ms")
                            }
                            session.endRmssdMs?.let {
                                DetailRow("End RMSSD", "${String.format("%.1f", it)} ms")
                            }
                            // Delta RMSSD
                            if (session.startRmssdMs != null && session.endRmssdMs != null) {
                                val delta = session.endRmssdMs - session.startRmssdMs
                                val sign = if (delta >= 0) "+" else ""
                                val color = if (delta >= 0) ReadinessGreen else ReadinessOrange
                                DetailRow(
                                    label = "RMSSD Change",
                                    value = "$sign${String.format("%.1f", delta)} ms",
                                    valueColor = color
                                )
                            }
                            session.lnRmssdSlope?.let {
                                val sign = if (it >= 0) "+" else ""
                                val color = if (it >= 0) ReadinessGreen else ReadinessOrange
                                DetailRow(
                                    label = "ln(RMSSD) Slope",
                                    value = "$sign${String.format("%.4f", it)}",
                                    valueColor = color
                                )
                            }
                        }
                    }

                    // ── No HR data note ────────────────────────────────────
                    if (session.avgHrBpm == null) {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                            Text(
                                "No HR data was recorded for this session. Connect a heart rate monitor to capture analytics.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Reusable detail card ───────────────────────────────────────────────────────

@Composable
private fun DetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(color = SurfaceVariant)
            content()
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = EcgCyan
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1.5f)
        )
    }
}
