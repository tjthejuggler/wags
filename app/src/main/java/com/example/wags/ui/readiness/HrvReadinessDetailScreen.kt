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
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Help text constants ───────────────────────────────────────────────────────

private const val HELP_READINESS_SCORE_TITLE = "Readiness Score"
private const val HELP_READINESS_SCORE_TEXT =
    "A 0–100 score reflecting your Autonomic Nervous System's recovery state, derived from your HRV compared to your personal 14-day baseline.\n\n" +
    "• 80–100 (GREEN) — Well recovered. Train hard or as planned.\n" +
    "• 60–79 (YELLOW) — Moderate readiness. Consider reducing intensity.\n" +
    "• 0–59 (RED) — Poor recovery. Prioritise rest and recovery.\n\n" +
    "This score is based on a Z-score comparison of today's ln(RMSSD) against your rolling baseline."

private const val HELP_RMSSD_TITLE = "RMSSD (Root Mean Square of Successive Differences)"
private const val HELP_RMSSD_TEXT =
    "The gold-standard HRV metric for daily recovery tracking.\n\n" +
    "It measures the millisecond-level variation between consecutive heartbeats — a direct window into your parasympathetic ('rest and digest') nervous system.\n\n" +
    "• Higher RMSSD → more parasympathetic activity → better recovery.\n" +
    "• Typical healthy adult range: 20–80 ms (highly individual).\n" +
    "• Track your personal trend rather than comparing to population norms."

private const val HELP_LN_RMSSD_TITLE = "ln(RMSSD)"
private const val HELP_LN_RMSSD_TEXT =
    "The natural logarithm of RMSSD.\n\n" +
    "Raw RMSSD values are not normally distributed — small changes at low values are more meaningful than the same change at high values. Taking the log compresses the scale and makes the distribution more symmetric, which is required for valid Z-score comparisons against your baseline.\n\n" +
    "This is the value used internally to compute your readiness score."

private const val HELP_SDNN_TITLE = "SDNN (Standard Deviation of NN Intervals)"
private const val HELP_SDNN_TEXT =
    "SDNN captures overall heart rate variability across the entire recording window, reflecting both sympathetic and parasympathetic activity.\n\n" +
    "• Healthy adults at rest: 40–100 ms.\n" +
    "• Lower than your baseline → increased stress load or poor recovery.\n" +
    "• RMSSD is more sensitive to short-term parasympathetic changes; SDNN gives a broader picture."

private const val HELP_HF_POWER_TITLE = "HF Power (High-Frequency Power)"
private const val HELP_HF_POWER_TEXT =
    "The power spectral density of the HRV signal in the high-frequency band (0.15–0.40 Hz), measured in ms².\n\n" +
    "HF power corresponds to the respiratory frequency and is a direct marker of vagal (parasympathetic) tone.\n\n" +
    "• Higher HF power → stronger parasympathetic activity → better recovery.\n" +
    "• Computed via FFT on the resampled RR interval series.\n" +
    "• More sensitive to breathing rate than RMSSD; both metrics together give a complete picture."

private const val HELP_RHR_TITLE = "Resting Heart Rate (RHR)"
private const val HELP_RHR_TEXT =
    "Your heart rate during the HRV recording — a simple but powerful recovery indicator.\n\n" +
    "• A rate 5–10 bpm above your personal baseline often signals incomplete recovery, illness, or dehydration.\n" +
    "• Endurance athletes may have RHR as low as 35–45 bpm.\n" +
    "• Used as a secondary signal: if today's RHR is significantly elevated, the readiness score may be capped."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvReadinessDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: HrvReadinessDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Navigate back automatically once the record has been deleted
    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onNavigateBack()
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = SurfaceVariant,
            title = {
                Text(
                    "Delete Reading?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "This HRV readiness reading will be permanently deleted and cannot be recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteReading()
                }) {
                    Text("Delete", color = TextSecondary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("HRV Readiness Detail") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    if (uiState.reading != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Text(
                                    "🗑",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary
                                )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            uiState.isDeleted -> {
                // Brief blank while LaunchedEffect fires the back navigation
                Box(modifier = Modifier.fillMaxSize().padding(padding))
            }
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TextSecondary)
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

    val scoreColor = TextPrimary
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
                    Spacer(Modifier.width(4.dp))
                    InfoHelpBubble(
                        title   = HELP_READINESS_SCORE_TITLE,
                        content = HELP_READINESS_SCORE_TEXT,
                        iconTint = scoreColor
                    )
                    Spacer(Modifier.width(8.dp))
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
            HrvDetailRowWithHelp("RMSSD", "${String.format("%.1f", reading.rawRmssdMs)} ms",
                HELP_RMSSD_TITLE, HELP_RMSSD_TEXT)
            HrvDetailRowWithHelp("ln(RMSSD)", String.format("%.3f", reading.lnRmssd),
                HELP_LN_RMSSD_TITLE, HELP_LN_RMSSD_TEXT)
            HrvDetailRowWithHelp("SDNN", "${String.format("%.1f", reading.sdnnMs)} ms",
                HELP_SDNN_TITLE, HELP_SDNN_TEXT)
            HrvDetailRowWithHelp("HF Power", "${String.format("%.2f", reading.hfPowerMs2)} ms²",
                HELP_HF_POWER_TITLE, HELP_HF_POWER_TEXT)
        }

        // ── Cardiovascular ────────────────────────────────────────────────────
        HrvDetailSection(title = "Cardiovascular") {
            HrvDetailRowWithHelp("Resting HR", "${String.format("%.0f", reading.restingHrBpm)} bpm",
                HELP_RHR_TITLE, HELP_RHR_TEXT)
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
                color = TextPrimary,
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

/** A detail row with a small info-bubble icon next to the label. */
@Composable
private fun HrvDetailRowWithHelp(
    label: String,
    value: String,
    helpTitle: String,
    helpText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            InfoHelpBubble(
                title   = helpTitle,
                content = helpText,
                iconTint = TextSecondary
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}
