package com.example.wags.ui.readiness

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvReadinessDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: HrvReadinessDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("HRV Readiness Detail") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = EcgCyan)
                }
            }
            uiState.reading == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Session not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextDisabled,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                HrvDetailContent(
                    reading = uiState.reading!!,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun HrvDetailContent(
    reading: DailyReadingEntity,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(reading.timestamp).atZone(zone)
    val dateLabel = dateTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
    val timeLabel = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))

    val scoreColor = when {
        reading.readinessScore >= 80 -> ReadinessGreen
        reading.readinessScore >= 60 -> ReadinessOrange
        else                         -> ReadinessRed
    }
    val scoreLabel = when {
        reading.readinessScore >= 80 -> "GREEN"
        reading.readinessScore >= 60 -> "YELLOW"
        else                         -> "RED"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(dateLabel, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(timeLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                HorizontalDivider(color = SurfaceDark)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = reading.readinessScore.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = scoreColor
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Readiness Score",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            scoreLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = scoreColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── HRV Metrics ───────────────────────────────────────────────────────
        HrvDetailSection(title = "HRV Metrics") {
            HrvDetailRow("RMSSD", "${String.format("%.1f", reading.rawRmssdMs)} ms")
            HrvDetailRow("ln(RMSSD)", String.format("%.3f", reading.lnRmssd))
            HrvDetailRow("SDNN", "${String.format("%.1f", reading.sdnnMs)} ms")
            HrvDetailRow("HF Power", "${String.format("%.2f", reading.hfPowerMs2)} ms²")
        }

        // ── Cardiovascular ────────────────────────────────────────────────────
        HrvDetailSection(title = "Cardiovascular") {
            HrvDetailRow("Resting HR", "${String.format("%.0f", reading.restingHrBpm)} bpm")
        }

        // ── Recording ─────────────────────────────────────────────────────────
        HrvDetailSection(title = "Recording") {
            HrvDetailRow("HR Device", reading.hrDeviceId ?: "None recorded")
            HrvDetailRow("Session ID", reading.readingId.toString())
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Section wrapper ────────────────────────────────────────────────────────────

@Composable
private fun HrvDetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = EcgCyan,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

@Composable
private fun HrvDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}
