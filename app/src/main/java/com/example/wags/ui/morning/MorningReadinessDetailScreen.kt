package com.example.wags.ui.morning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReadinessDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: MorningReadinessDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Morning Readiness Detail") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        val reading = uiState.reading
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
            uiState.notFound || reading == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Reading not found.", color = TextSecondary)
                }
            }
            else -> {
                MorningReadinessDetailContent(
                    reading = reading,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun MorningReadinessDetailContent(
    reading: MorningReadinessEntity,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(reading.timestamp).atZone(zone)
    val dateLabel = dateTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
    val timeLabel = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))

    val scoreColor = when (reading.readinessColor) {
        "GREEN" -> ReadinessGreen
        "RED"   -> ReadinessRed
        else    -> ReadinessOrange
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header: date/time + score ─────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(dateLabel, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(timeLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = reading.readinessScore.toString(),
                        style = MaterialTheme.typography.displayLarge,
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
                            reading.readinessColor,
                            style = MaterialTheme.typography.labelLarge,
                            color = scoreColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── Supine HRV ────────────────────────────────────────────────────────
        DetailSection(title = "Supine HRV") {
            DetailRow("RMSSD", "${String.format("%.1f", reading.supineRmssdMs)} ms")
            DetailRow("HRV Score (ln×20)", (reading.supineLnRmssd * 20).toInt().toString())
            DetailRow("SDNN", "${String.format("%.1f", reading.supineSdnnMs)} ms")
            DetailRow("Resting HR", "${reading.supineRhr} bpm")
        }

        // ── Standing HRV ──────────────────────────────────────────────────────
        DetailSection(title = "Standing HRV") {
            DetailRow("RMSSD", "${String.format("%.1f", reading.standingRmssdMs)} ms")
            DetailRow("HRV Score (ln×20)", (reading.standingLnRmssd * 20).toInt().toString())
            DetailRow("SDNN", "${String.format("%.1f", reading.standingSdnnMs)} ms")
            DetailRow("Peak Stand HR", "${reading.peakStandHr} bpm")
        }

        // ── Orthostatic Response ──────────────────────────────────────────────
        DetailSection(title = "Orthostatic Response") {
            reading.thirtyFifteenRatio?.let {
                DetailRow("30:15 Ratio", String.format("%.3f", it))
            } ?: DetailRow("30:15 Ratio", "—")
            reading.ohrrAt20sPercent?.let {
                DetailRow("OHRR at 20s", "${String.format("%.1f", it)} %")
            }
            reading.ohrrAt60sPercent?.let {
                DetailRow("OHRR at 60s", "${String.format("%.1f", it)} %")
            }
        }

        // ── Respiratory ───────────────────────────────────────────────────────
        reading.respiratoryRateBpm?.let { rr ->
            DetailSection(title = "Respiratory") {
                DetailRow("Respiratory Rate", "${String.format("%.1f", rr)} brpm")
                if (reading.slowBreathingFlagged) {
                    Text(
                        "⚠ Slow breathing detected — score adjusted",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessOrange
                    )
                }
            }
        }

        // ── Hooper Wellness Index ─────────────────────────────────────────────
        reading.hooperTotal?.let { total ->
            DetailSection(title = "Hooper Wellness Index") {
                DetailRow("Total", "${String.format("%.0f", total)} / 20")
                reading.hooperSleep?.let    { DetailRow("Sleep Quality",   "$it / 5") }
                reading.hooperFatigue?.let  { DetailRow("Fatigue",         "$it / 5") }
                reading.hooperSoreness?.let { DetailRow("Muscle Soreness", "$it / 5") }
                reading.hooperStress?.let   { DetailRow("Stress",          "$it / 5") }
            }
        }

        // ── Score Breakdown ───────────────────────────────────────────────────
        DetailSection(title = "Score Breakdown") {
            DetailRow("HRV Base Score", reading.hrvBaseScore.toString())
            DetailRow("Ortho Multiplier", String.format("%.2f", reading.orthoMultiplier))
            if (reading.cvPenaltyApplied) {
                Text(
                    "⚠ CV penalty applied (high HRV variability)",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadinessOrange
                )
            }
            if (reading.rhrLimiterApplied) {
                Text(
                    "⚠ RHR limiter applied (elevated resting HR)",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadinessOrange
                )
            }
        }

        // ── Data Quality ──────────────────────────────────────────────────────
        DetailSection(title = "Data Quality") {
            DetailRow("HR Device", reading.hrDeviceId ?: "None recorded")
            DetailRow("Artifact % (Supine)",   "${String.format("%.1f", reading.artifactPercentSupine)} %")
            DetailRow("Artifact % (Standing)", "${String.format("%.1f", reading.artifactPercentStanding)} %")
        }
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
private fun DetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = EcgCyan,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
