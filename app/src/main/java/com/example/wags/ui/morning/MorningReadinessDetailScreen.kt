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
                        "Stand cue",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadinessOrange
                    )
                    if (supineAvg != null && standingAvg != null) {
                        Spacer(Modifier.width(8.dp))
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
                    value = "${reading.peakStandHr} bpm",
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
