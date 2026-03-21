package com.example.wags.ui.morning

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.db.entity.MorningReadinessTelemetryEntity
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Help text constants ───────────────────────────────────────────────────────

private const val HELP_READINESS_SCORE_TITLE = "Readiness Score"
private const val HELP_READINESS_SCORE_TEXT =
    "A 0–100 score summarising your Autonomic Nervous System's readiness to handle stress today.\n\n" +
    "• 70–100 (GREEN) — Well recovered. Train hard or as planned.\n" +
    "• 40–69 (YELLOW) — Moderate readiness. Consider reducing intensity.\n" +
    "• 0–39 (RED) — Poor recovery. Prioritise rest, sleep, and nutrition.\n\n" +
    "The score combines your resting HRV, how your heart responds to standing up (orthostatic response), and your subjective wellness (Hooper Index)."

private const val HELP_RMSSD_TITLE = "RMSSD (Root Mean Square of Successive Differences)"
private const val HELP_RMSSD_TEXT =
    "The gold-standard HRV metric for daily recovery tracking.\n\n" +
    "It measures the millisecond-level variation between consecutive heartbeats — a direct window into your parasympathetic ('rest and digest') nervous system.\n\n" +
    "• Higher RMSSD → more parasympathetic activity → better recovery.\n" +
    "• Typical healthy adult range: 20–80 ms (highly individual).\n" +
    "• Track your personal trend rather than comparing to population norms."

private const val HELP_HRV_SCORE_TITLE = "HRV Score (ln(RMSSD) × 20)"
private const val HELP_HRV_SCORE_TEXT =
    "Raw RMSSD values vary enormously between individuals (10 ms vs 80 ms can both be 'normal'). " +
    "Applying a natural logarithm compresses this range, and multiplying by 20 maps it to a 0–100 scale that is easier to track over time.\n\n" +
    "• Score 60–80 is typical for healthy adults.\n" +
    "• Focus on your personal day-to-day changes, not absolute numbers."

private const val HELP_SDNN_TITLE = "SDNN (Standard Deviation of NN Intervals)"
private const val HELP_SDNN_TEXT =
    "SDNN captures overall heart rate variability across the entire recording window, reflecting both sympathetic and parasympathetic activity.\n\n" +
    "• Healthy adults at rest: 40–100 ms.\n" +
    "• Lower than your baseline → increased stress load or poor recovery.\n" +
    "• RMSSD is more sensitive to short-term parasympathetic changes; SDNN gives a broader picture."

private const val HELP_RHR_TITLE = "Resting Heart Rate (RHR)"
private const val HELP_RHR_TEXT =
    "Your heart rate while lying still — a simple but powerful recovery indicator.\n\n" +
    "• A rate 5–10 bpm above your personal baseline often signals incomplete recovery, illness, or dehydration.\n" +
    "• Endurance athletes may have RHR as low as 35–45 bpm.\n" +
    "• The app applies a hard limiter: if today's RHR is >10 bpm or >2.5 standard deviations above your 90-day average, the readiness score is capped at 50."

private const val HELP_PEAK_STAND_HR_TITLE = "Peak Stand HR"
private const val HELP_PEAK_STAND_HR_TEXT =
    "The highest heart rate recorded during the first 30 seconds of standing.\n\n" +
    "When you stand, gravity pulls blood toward your legs. Your sympathetic nervous system fires to compensate, spiking your heart rate.\n\n" +
    "• A rise of 10–30 bpm above supine HR is normal.\n" +
    "• >30 bpm rise may indicate orthostatic stress, dehydration, or fatigue.\n" +
    "• <10 bpm rise can indicate high autonomic tone (very fit) or blunted response (overtraining).\n\n" +
    "Note: A single-beat spike immediately on standing is often a motion artifact from the chest strap shifting — the app filters these out."

private const val HELP_3015_TITLE = "30:15 Ratio"
private const val HELP_3015_TEXT =
    "A precise autonomic index of your vagal (parasympathetic) rebound after standing.\n\n" +
    "When you stand, your heart rate spikes (beat ~15), then your vagus nerve slows it back down (beat ~30). The ratio of the slowest recovery beat to the fastest spike beat measures how elastic your nervous system is.\n\n" +
    "• >1.15 (age <40) — Excellent vagal tone.\n" +
    "• 1.08–1.15 — Normal.\n" +
    "• <1.08 — Reduced vagal rebound; may indicate fatigue or overtraining.\n" +
    "• Thresholds are age-adjusted (older adults have naturally lower ratios)."

private const val HELP_OHRR_TITLE = "Orthostatic HR Recovery (OHRR)"
private const val HELP_OHRR_TEXT =
    "Measures how quickly your heart rate drops after the initial spike of standing, expressed as a percentage of the peak HR.\n\n" +
    "• OHRR at 20 s: fast initial recovery — driven by vagal reactivation.\n" +
    "• OHRR at 60 s: sustained recovery — reflects overall autonomic efficiency.\n\n" +
    "• >20% drop at 60 s → Good recovery.\n" +
    "• 10–20% → Borderline.\n" +
    "• <10% → Sluggish; may indicate dehydration, fatigue, or illness."

private const val HELP_ORTHO_MULTIPLIER_TITLE = "Orthostatic Multiplier"
private const val HELP_ORTHO_MULTIPLIER_TEXT =
    "A factor (0.80–1.05) applied to your HRV base score based on how well your autonomic nervous system handled the gravitational stress of standing.\n\n" +
    "• 1.05 — Both 30:15 ratio and OHRR are excellent (small bonus).\n" +
    "• 1.00 — Normal orthostatic response.\n" +
    "• 0.95 — Borderline response (mild penalty).\n" +
    "• 0.88 — One metric clearly abnormal.\n" +
    "• 0.80 — Both metrics abnormal (clear orthostatic stress)."

private const val HELP_HRV_BASE_TITLE = "HRV Base Score"
private const val HELP_HRV_BASE_TEXT =
    "Your today's HRV score (ln(RMSSD)×20) compared to your 30-day personal baseline, mapped to a 30–100 scale.\n\n" +
    "• Within your Smallest Worthwhile Change (SWC) band → 90–100.\n" +
    "• Above SWC but not extreme → capped at 75 (parasympathetic hyper-compensation).\n" +
    "• Below SWC → score drops proportionally toward 30.\n\n" +
    "This is the foundation of the readiness score before orthostatic and wellness adjustments."

private const val HELP_HOOPER_TITLE = "Hooper Wellness Index"
private const val HELP_HOOPER_TEXT =
    "A validated subjective wellness questionnaire used by elite sports scientists.\n\n" +
    "Four dimensions rated 1 (worst) to 5 (best): Sleep Quality, Fatigue, Muscle Soreness, and Stress. Total range: 4–20.\n\n" +
    "• ≥16 — Good subjective state.\n" +
    "• 11–15 — Moderate.\n" +
    "• ≤10 — Poor; the app applies a penalty if your HRV looks good but your body feels bad.\n\n" +
    "The Hooper Index acts as a 'sanity check' on the objective HRV data."

private const val HELP_ARTIFACT_TITLE = "Artifact Percentage"
private const val HELP_ARTIFACT_TEXT =
    "The percentage of RR intervals flagged as motion or noise artifacts and corrected before HRV analysis.\n\n" +
    "• <5% — Excellent signal quality.\n" +
    "• 5–15% — Acceptable; results are reliable.\n" +
    "• >15% — Poor signal; results may be less accurate. Ensure the H10 strap is wet and snug.\n\n" +
    "Artifacts are replaced using interpolation rather than discarded, so the HRV calculation remains valid at low artifact rates."

private const val HELP_STAND_MARKER_TITLE = "Stand Marker"
private const val HELP_STAND_MARKER_TEXT =
    "The orange dashed line marks the precise moment you stood up, detected from the H10 accelerometer.\n\n" +
    "Everything to the left is your supine (lying) phase; everything to the right is your standing phase.\n\n" +
    "The orthostatic response — the HR spike and subsequent recovery — is the key signal in the standing window."


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReadinessDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: MorningReadinessDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Pop back automatically once the record has been deleted
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    // Delete confirmation dialog
    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            containerColor   = SurfaceVariant,
            title = {
                Text(
                    "Delete this reading?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "This will permanently remove the morning readiness record and all associated telemetry data. This cannot be undone.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = TextSecondary,
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("Delete", color = ReadinessRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

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
                actions = {
                    // Only show delete when a reading is loaded
                    if (uiState.reading != null) {
                        IconButton(onClick = { viewModel.requestDelete() }) {
                            Text(
                                "🗑",
                                style = MaterialTheme.typography.titleMedium,
                                color = ReadinessRed
                            )
                        }
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
                    reading   = reading,
                    telemetry = uiState.telemetry,
                    modifier  = Modifier.padding(padding)
                )
            }
        }
    }
}

// ── Main content ──────────────────────────────────────────────────────────────

@Composable
private fun MorningReadinessDetailContent(
    reading: MorningReadinessEntity,
    telemetry: List<MorningReadinessTelemetryEntity>,
    modifier: Modifier = Modifier
) {
    val zone      = ZoneId.systemDefault()
    val dateTime  = Instant.ofEpochMilli(reading.timestamp).atZone(zone)
    val dateLabel = dateTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
    val timeLabel = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))

    val scoreColor = when (reading.readinessColor) {
        "GREEN" -> ReadinessGreen
        "RED"   -> ReadinessRed
        else    -> ReadinessOrange
    }

    // Pre-compute stand marker fraction (0..1 across the full telemetry timeline)
    val sessionStartMs = telemetry.firstOrNull()?.timestampMs
    val sessionEndMs   = telemetry.lastOrNull()?.timestampMs
    val standFraction: Float? = if (
        reading.standTimestampMs != null &&
        sessionStartMs != null &&
        sessionEndMs != null &&
        sessionEndMs > sessionStartMs
    ) {
        ((reading.standTimestampMs - sessionStartMs).toFloat() /
                (sessionEndMs - sessionStartMs).toFloat()).coerceIn(0f, 1f)
    } else null

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
                    Spacer(Modifier.width(4.dp))
                    InfoHelpBubble(
                        title = HELP_READINESS_SCORE_TITLE,
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
                            reading.readinessColor,
                            style = MaterialTheme.typography.labelLarge,
                            color = scoreColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── HR chart ──────────────────────────────────────────────────────────
        if (telemetry.isNotEmpty()) {
            TelemetryChartCard(
                title       = "Heart Rate",
                subtitle    = "bpm · supine → stand",
                telemetry   = telemetry,
                valueSelector = { it.hrBpm.toFloat() },
                lineColor   = EcgCyan,
                unit        = "bpm",
                standFraction = standFraction
            )
        }

        // ── HRV (rolling RMSSD) chart ─────────────────────────────────────────
        val hasHrv = telemetry.any { it.rollingRmssdMs > 0.0 }
        if (hasHrv) {
            TelemetryChartCard(
                title       = "Rolling HRV (RMSSD)",
                subtitle    = "ms · 20-beat sliding window",
                telemetry   = telemetry.filter { it.rollingRmssdMs > 0.0 },
                valueSelector = { it.rollingRmssdMs.toFloat() },
                lineColor   = ReadinessGreen,
                unit        = "ms",
                standFraction = standFraction
            )
        }

        // ── Orthostatic response stats ────────────────────────────────────────
        OrthostasisStatsCard(reading = reading, telemetry = telemetry)

        // ── Supine HRV ────────────────────────────────────────────────────────
        DetailSection(title = "Supine HRV") {
            DetailRowWithHelp("RMSSD", "${String.format("%.1f", reading.supineRmssdMs)} ms",
                HELP_RMSSD_TITLE, HELP_RMSSD_TEXT)
            DetailRowWithHelp("HRV Score (ln×20)", (reading.supineLnRmssd * 20).toInt().toString(),
                HELP_HRV_SCORE_TITLE, HELP_HRV_SCORE_TEXT)
            DetailRowWithHelp("SDNN", "${String.format("%.1f", reading.supineSdnnMs)} ms",
                HELP_SDNN_TITLE, HELP_SDNN_TEXT)
            DetailRowWithHelp("Resting HR", "${reading.supineRhr} bpm",
                HELP_RHR_TITLE, HELP_RHR_TEXT)
        }

        // ── Standing HRV — only shown when the user completed the standing phase ──
        if (reading.standingRmssdMs != null) {
            DetailSection(title = "Standing HRV") {
                DetailRowWithHelp("RMSSD", "${String.format("%.1f", reading.standingRmssdMs)} ms",
                    HELP_RMSSD_TITLE, HELP_RMSSD_TEXT)
                reading.standingLnRmssd?.let {
                    DetailRowWithHelp("HRV Score (ln×20)", (it * 20).toInt().toString(),
                        HELP_HRV_SCORE_TITLE, HELP_HRV_SCORE_TEXT)
                }
                reading.standingSdnnMs?.let {
                    DetailRowWithHelp("SDNN", "${String.format("%.1f", it)} ms",
                        HELP_SDNN_TITLE, HELP_SDNN_TEXT)
                }
                reading.peakStandHr?.let {
                    DetailRowWithHelp("Peak Stand HR", "$it bpm",
                        HELP_PEAK_STAND_HR_TITLE, HELP_PEAK_STAND_HR_TEXT)
                }
            }
        }

        // ── Orthostatic Response ──────────────────────────────────────────────
        DetailSection(title = "Orthostatic Response") {
            reading.thirtyFifteenRatio?.let {
                DetailRowWithHelp("30:15 Ratio", String.format("%.3f", it),
                    HELP_3015_TITLE, HELP_3015_TEXT)
            } ?: DetailRow("30:15 Ratio", "—")
            reading.ohrrAt20sPercent?.let {
                DetailRowWithHelp("OHRR at 20s", "${String.format("%.1f", it)} %",
                    HELP_OHRR_TITLE, HELP_OHRR_TEXT)
            }
            reading.ohrrAt60sPercent?.let {
                DetailRowWithHelp("OHRR at 60s", "${String.format("%.1f", it)} %",
                    HELP_OHRR_TITLE, HELP_OHRR_TEXT)
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
                DetailRowWithHelp("Total", "${String.format("%.1f", total)} / 20",
                    HELP_HOOPER_TITLE, HELP_HOOPER_TEXT)
                reading.hooperSleep?.let    { DetailRow("Sleep Quality",   String.format("%.1f", it)) }
                reading.hooperFatigue?.let  { DetailRow("Fatigue",         String.format("%.1f", it)) }
                reading.hooperSoreness?.let { DetailRow("Muscle Soreness", String.format("%.1f", it)) }
                reading.hooperStress?.let   { DetailRow("Stress",          String.format("%.1f", it)) }
            }
        }

        // ── Score Breakdown ───────────────────────────────────────────────────
        DetailSection(title = "Score Breakdown") {
            DetailRowWithHelp("HRV Base Score", reading.hrvBaseScore.toString(),
                HELP_HRV_BASE_TITLE, HELP_HRV_BASE_TEXT)
            DetailRowWithHelp("Ortho Multiplier", String.format("%.2f", reading.orthoMultiplier),
                HELP_ORTHO_MULTIPLIER_TITLE, HELP_ORTHO_MULTIPLIER_TEXT)
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
            DetailRowWithHelp("Artifact % (Supine)",   "${String.format("%.1f", reading.artifactPercentSupine)} %",
                HELP_ARTIFACT_TITLE, HELP_ARTIFACT_TEXT)
            DetailRowWithHelp("Artifact % (Standing)", "${String.format("%.1f", reading.artifactPercentStanding)} %",
                HELP_ARTIFACT_TITLE, HELP_ARTIFACT_TEXT)
            if (telemetry.isNotEmpty()) {
                DetailRow("Telemetry beats", telemetry.size.toString())
            }
        }
    }
}

// ── Telemetry chart card ──────────────────────────────────────────────────────

@Composable
private fun TelemetryChartCard(
    title: String,
    subtitle: String,
    telemetry: List<MorningReadinessTelemetryEntity>,
    valueSelector: (MorningReadinessTelemetryEntity) -> Float,
    lineColor: Color,
    unit: String,
    standFraction: Float?,
    modifier: Modifier = Modifier
) {
    val values = telemetry.map(valueSelector)
    val yMin   = (values.minOrNull() ?: 0f) * 0.92f
    val yMax   = (values.maxOrNull() ?: 1f) * 1.08f

    // Stats
    val avg    = values.average().toFloat()
    val minVal = values.minOrNull() ?: 0f
    val maxVal = values.maxOrNull() ?: 0f

    // Supine vs standing split values for the legend
    val supineValues   = telemetry.filter { it.phase == "SUPINE"   }.map(valueSelector)
    val standingValues = telemetry.filter { it.phase == "STANDING" }.map(valueSelector)
    val supineAvg   = supineValues.average().toFloat().takeIf { supineValues.isNotEmpty() }
    val standingAvg = standingValues.average().toFloat().takeIf { standingValues.isNotEmpty() }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = lineColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                // Min / Avg / Max chips
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat("min", "${minVal.toInt()} $unit", TextSecondary)
                    MiniStat("avg", "${avg.toInt()} $unit", lineColor)
                    MiniStat("max", "${maxVal.toInt()} $unit", TextSecondary)
                }
            }

            HorizontalDivider(color = SurfaceDark)

            // Chart
            TelemetryLineChart(
                values        = values,
                yMin          = yMin,
                yMax          = yMax,
                lineColor     = lineColor,
                standFraction = standFraction,
                modifier      = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )

            // Stand marker legend
            if (standFraction != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Canvas(modifier = Modifier.size(width = 16.dp, height = 2.dp)) {
                        drawLine(
                            color       = ReadinessOrange,
                            start       = Offset(0f, size.height / 2f),
                            end         = Offset(size.width, size.height / 2f),
                            strokeWidth = 3f
                        )
                    }
                    Text(
                        "Stand",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadinessOrange
                    )
                    InfoHelpBubble(
                        title   = HELP_STAND_MARKER_TITLE,
                        content = HELP_STAND_MARKER_TEXT,
                        iconTint = ReadinessOrange
                    )
                    if (supineAvg != null && standingAvg != null) {
                        Text(
                            "Supine avg ${supineAvg.toInt()} $unit  →  Stand avg ${standingAvg.toInt()} $unit",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

// ── Orthostatic stats card ────────────────────────────────────────────────────

@Composable
private fun OrthostasisStatsCard(
    reading: MorningReadinessEntity,
    telemetry: List<MorningReadinessTelemetryEntity>
) {
    // Only show if we have a stand timestamp and telemetry
    val standTs = reading.standTimestampMs ?: return
    if (telemetry.isEmpty()) return

    // Beats in the 30 s window before stand
    val windowBeforeMs = 30_000L
    val supineWindow = telemetry.filter { it.phase == "SUPINE" &&
            it.timestampMs >= (standTs - windowBeforeMs) }
    val supineWindowAvgHr = supineWindow.map { it.hrBpm }.average()
        .takeIf { supineWindow.isNotEmpty() && !it.isNaN() }

    // Peak HR in first 30 s after stand
    val windowAfterMs = 30_000L
    val standWindow = telemetry.filter { it.phase == "STANDING" &&
            it.timestampMs <= (standTs + windowAfterMs) }
    val peakHrAfterStand = standWindow.maxOfOrNull { it.hrBpm }

    // HR rise = peak stand HR − supine avg
    val hrRise = if (supineWindowAvgHr != null && peakHrAfterStand != null)
        peakHrAfterStand - supineWindowAvgHr.toInt() else null

    // HRV drop: avg rolling RMSSD 30 s before vs 30 s after stand
    val supineHrvWindow  = supineWindow.filter { it.rollingRmssdMs > 0.0 }
    val standHrvWindow   = standWindow.filter  { it.rollingRmssdMs > 0.0 }
    val supineHrvAvg = supineHrvWindow.map { it.rollingRmssdMs }.average()
        .takeIf { supineHrvWindow.isNotEmpty() && !it.isNaN() }
    val standHrvAvg  = standHrvWindow.map  { it.rollingRmssdMs }.average()
        .takeIf { standHrvWindow.isNotEmpty() && !it.isNaN() }
    val hrvDrop = if (supineHrvAvg != null && standHrvAvg != null)
        supineHrvAvg - standHrvAvg else null

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Orthostatic Response",
                style = MaterialTheme.typography.labelLarge,
                color = EcgCyan,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = SurfaceDark)

            // Big stat row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                supineWindowAvgHr?.let {
                    OrthoStatBox(
                        label = "Pre-stand HR",
                        value = "${it.toInt()} bpm",
                        color = EcgCyan
                    )
                }
                OrthoStatBox(
                    label = "Peak stand HR",
                    value = reading.peakStandHr?.let { "$it bpm" } ?: "—",
                    color = ReadinessOrange
                )
                hrRise?.let {
                    val riseColor = when {
                        it > 30 -> ReadinessRed
                        it > 20 -> ReadinessOrange
                        else    -> ReadinessGreen
                    }
                    OrthoStatBox(
                        label = "HR rise",
                        value = "+$it bpm",
                        color = riseColor
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Secondary stats
            reading.thirtyFifteenRatio?.let {
                DetailRow("30:15 Ratio", String.format("%.3f", it))
            }
            reading.ohrrAt20sPercent?.let {
                DetailRow("OHRR at 20 s", "${String.format("%.1f", it)} %")
            }
            reading.ohrrAt60sPercent?.let {
                DetailRow("OHRR at 60 s", "${String.format("%.1f", it)} %")
            }
            hrvDrop?.let {
                val dropLabel = if (it >= 0)
                    "−${String.format("%.1f", it)} ms (HRV suppressed by stand)"
                else
                    "+${String.format("%.1f", -it)} ms (HRV rose after stand)"
                DetailRow("HRV change at stand", dropLabel)
            }

            // Interpretation hint
            hrRise?.let { rise ->
                val hint = when {
                    rise > 30 -> "⚠ Large HR surge (>${rise} bpm) — possible orthostatic stress or dehydration."
                    rise in 10..30 -> "✓ Normal orthostatic HR response."
                    rise < 10 -> "ℹ Blunted HR response — may indicate high autonomic tone or fatigue."
                    else -> null
                }
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ── Canvas chart ──────────────────────────────────────────────────────────────

/**
 * Draws a time-series line chart for telemetry values.
 *
 * @param values        Y values in chronological order.
 * @param yMin / yMax   Y-axis bounds (pre-padded by caller).
 * @param lineColor     Stroke + fill tint colour.
 * @param standFraction 0..1 fraction along the X axis where the stand cue occurred, or null.
 */
@Composable
private fun TelemetryLineChart(
    values: List<Float>,
    yMin: Float,
    yMax: Float,
    lineColor: Color,
    standFraction: Float?,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Not enough data", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        return
    }

    val density        = LocalDensity.current
    val leftPadPx      = with(density) { 36.dp.toPx() }   // Y-axis labels
    val rightPadPx     = with(density) { 6.dp.toPx() }
    val topPadPx       = with(density) { 6.dp.toPx() }
    val bottomPadPx    = with(density) { 18.dp.toPx() }   // X-axis labels
    val labelTextSizePx = with(density) { 9.sp.toPx() }

    val yRange         = (yMax - yMin).coerceAtLeast(1f)
    val gridColor      = Color.White.copy(alpha = 0.07f)
    val labelArgb      = Color.White.copy(alpha = 0.45f).toArgb()
    val standColor     = ReadinessOrange

    Canvas(modifier = modifier) {
        val totalW   = size.width
        val totalH   = size.height
        val plotLeft  = leftPadPx
        val plotRight = totalW - rightPadPx
        val plotTop   = topPadPx
        val plotBot   = totalH - bottomPadPx
        val plotW     = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH     = (plotBot - plotTop).coerceAtLeast(1f)

        fun xOf(i: Int)   = plotLeft + i.toFloat() / (values.size - 1).toFloat() * plotW
        fun yOf(v: Float) = plotTop + plotH * (1f - ((v - yMin) / yRange).coerceIn(0f, 1f))

        // ── Horizontal grid lines ─────────────────────────────────────────────
        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { frac ->
            val gy = plotTop + plotH * (1f - frac)
            drawLine(gridColor, Offset(plotLeft, gy), Offset(plotRight, gy), strokeWidth = 1f)
        }

        // ── Stand-cue vertical marker ─────────────────────────────────────────
        if (standFraction != null) {
            val sx = plotLeft + standFraction * plotW
            // Dashed-style: draw short segments
            val dashLen = with(density) { 5.dp.toPx() }
            val gapLen  = with(density) { 3.dp.toPx() }
            var y = plotTop
            while (y < plotBot) {
                val segEnd = (y + dashLen).coerceAtMost(plotBot)
                drawLine(
                    color       = standColor.copy(alpha = 0.85f),
                    start       = Offset(sx, y),
                    end         = Offset(sx, segEnd),
                    strokeWidth = 2f
                )
                y += dashLen + gapLen
            }
            // Small "STAND" label at top of marker
            drawContext.canvas.nativeCanvas.drawText(
                "STAND",
                sx + with(density) { 3.dp.toPx() },
                plotTop + labelTextSizePx + with(density) { 2.dp.toPx() },
                Paint().apply {
                    isAntiAlias = true
                    textSize    = labelTextSizePx
                    color       = standColor.copy(alpha = 0.9f).toArgb()
                    textAlign   = Paint.Align.LEFT
                }
            )
        }

        // ── Fill path ─────────────────────────────────────────────────────────
        val fillPath = Path().apply {
            moveTo(xOf(0), plotBot)
            lineTo(xOf(0), yOf(values[0]))
            for (i in 1 until values.size) lineTo(xOf(i), yOf(values[i]))
            lineTo(xOf(values.size - 1), plotBot)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = 0.12f))

        // ── Line path ─────────────────────────────────────────────────────────
        val linePath = Path().apply {
            moveTo(xOf(0), yOf(values[0]))
            for (i in 1 until values.size) lineTo(xOf(i), yOf(values[i]))
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // End-point dots
        drawCircle(lineColor, radius = 4.5f, center = Offset(xOf(0), yOf(values.first())))
        drawCircle(lineColor, radius = 4.5f, center = Offset(xOf(values.size - 1), yOf(values.last())))

        // ── Y-axis labels ─────────────────────────────────────────────────────
        val yPaint = Paint().apply {
            isAntiAlias = true
            textSize    = labelTextSizePx
            color       = labelArgb
            textAlign   = Paint.Align.RIGHT
        }
        for (step in 0..3) {
            val frac  = step / 3f
            val value = yMin + frac * yRange
            val gy    = plotTop + plotH * (1f - frac)
            drawContext.canvas.nativeCanvas.drawText(
                "${value.toInt()}",
                plotLeft - with(density) { 3.dp.toPx() },
                gy + labelTextSizePx / 2f,
                yPaint
            )
        }

        // ── X-axis phase labels ───────────────────────────────────────────────
        val xPaint = Paint().apply {
            isAntiAlias = true
            textSize    = labelTextSizePx
            color       = labelArgb
            textAlign   = Paint.Align.CENTER
        }
        // "Supine" at 25% and "Standing" at 75% (approximate)
        drawContext.canvas.nativeCanvas.drawText(
            "Supine",
            plotLeft + plotW * 0.20f,
            totalH - with(density) { 2.dp.toPx() },
            xPaint
        )
        drawContext.canvas.nativeCanvas.drawText(
            "Standing",
            plotLeft + plotW * 0.75f,
            totalH - with(density) { 2.dp.toPx() },
            xPaint
        )
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun OrthoStatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

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

/** A detail row with a small info-bubble icon next to the label. */
@Composable
private fun DetailRowWithHelp(
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
