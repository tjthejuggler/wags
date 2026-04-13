package com.example.wags.ui.meditation

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.MeditationTelemetryEntity
import com.example.wags.ui.navigation.WagsRoutes
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

    // Pop back automatically once the session has been deleted
    LaunchedEffect(state.deleted) {
        if (state.deleted) navController.popBackStack(WagsRoutes.MEDITATION_HISTORY, inclusive = false)
    }

    // Delete confirmation dialog
    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            containerColor   = SurfaceVariant,
            title = {
                Text(
                    "Delete this session?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "This will permanently remove the meditation session record. This cannot be undone.",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = TextSecondary,
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("Delete", color = TextDisabled, fontWeight = FontWeight.Bold)
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
                title = { Text("Session Detail") },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
                        )
                    }
                },
                actions = {
                    if (state.session != null) {
                        IconButton(onClick = { viewModel.requestDelete() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete session",
                                tint = TextDisabled
                            )
                        }
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
                    CircularProgressIndicator(color = TextSecondary)
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
                val session   = state.session!!
                val audio     = state.audio
                val telemetry = state.telemetry
                val zone      = ZoneId.systemDefault()
                val dateLabel = Instant.ofEpochMilli(session.timestamp).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
                val timeLabel = Instant.ofEpochMilli(session.timestamp).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("h:mm a"))
                val durationMin = session.durationMs / 60_000L
                val durationSec = (session.durationMs % 60_000L) / 1_000L
                val audioName = audio?.let {
                    if (it.isNone) "Silent Meditation" else it.displayName
                } ?: "Unknown"

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // ── Header card ────────────────────────────────────────────
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                timeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            HorizontalDivider(color = SurfaceDark)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                HeaderStat(
                                    label = "Duration",
                                    value = "${durationMin}m ${durationSec}s",
                                    color = TextPrimary
                                )
                                if (session.avgHrBpm != null) {
                                    HeaderStat(
                                        label = "Avg HR",
                                        value = "${String.format("%.0f", session.avgHrBpm)} bpm",
                                        color = TextSecondary
                                    )
                                }
                                if (session.endRmssdMs != null) {
                                    HeaderStat(
                                        label = "End RMSSD",
                                        value = "${String.format("%.1f", session.endRmssdMs)} ms",
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // ── Overview card ──────────────────────────────────────────
                    DetailSection(title = "Overview") {
                        DetailRow("Audio", audioName)
                        DetailRow("Duration", "${durationMin}m ${durationSec}s")
                        DetailRow("Posture", session.posture.lowercase()
                            .replaceFirstChar { it.uppercase() })
                        DetailRow("Monitor", session.monitorId ?: "None")
                        audio?.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            DetailRow("Source URL", url)
                        }
                        if (!audio?.youtubeChannel.isNullOrBlank()) {
                            DetailRow("Channel", audio!!.youtubeChannel!!)
                        }
                    }

                    // ── Full HR chart (telemetry) ──────────────────────────────
                    if (telemetry.isNotEmpty()) {
                        // Timer fraction: where in the data the timer ended
                        val timerFraction: Float? = session.timerDurationMs?.let { timerMs ->
                            if (session.durationMs > 0L)
                                (timerMs.toFloat() / session.durationMs.toFloat()).coerceIn(0f, 1f)
                            else null
                        }

                        val hrValues = telemetry.mapNotNull { it.hrBpm?.toFloat() }
                        if (hrValues.size >= 2) {
                            TelemetryChartCard(
                                title         = "Heart Rate",
                                subtitle      = "BPM over session",
                                values        = hrValues,
                                lineColor     = TextSecondary,
                                unit          = "bpm",
                                totalDurationMs = session.durationMs,
                                timerFraction = timerFraction
                            )
                        }

                        val rmssdValues = telemetry
                            .filter { it.rollingRmssdMs > 0.0 }
                            .map { it.rollingRmssdMs.toFloat() }
                        if (rmssdValues.size >= 2) {
                            TelemetryChartCard(
                                title         = "Rolling RMSSD",
                                subtitle      = "ms over session (20-beat window)",
                                values        = rmssdValues,
                                lineColor     = TextPrimary,
                                unit          = "ms",
                                totalDurationMs = session.durationMs,
                                timerFraction = timerFraction
                            )
                        }
                    }

                    // ── HR analytics card ──────────────────────────────────────
                    if (session.avgHrBpm != null) {
                        DetailSection(title = "Heart Rate Analytics") {
                            DetailRow("Avg HR", "${String.format("%.1f", session.avgHrBpm)} BPM")
                            session.hrSlopeBpmPerMin?.let { slope ->
                                val sign  = if (slope >= 0) "+" else ""
                                val color = if (slope <= 0) TextPrimary else TextSecondary
                                DetailRowColored(
                                    label = "HR Trend",
                                    value = "$sign${String.format("%.2f", slope)} BPM/min",
                                    valueColor = color
                                )
                                Text(
                                    if (slope <= 0)
                                        "✓ Heart rate decreased during session — good relaxation response"
                                    else
                                        "Heart rate increased slightly during session",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (slope <= 0) TextPrimary else TextDisabled,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    // ── HRV analytics card ─────────────────────────────────────
                    if (session.startRmssdMs != null || session.endRmssdMs != null || session.lnRmssdSlope != null) {
                        DetailSection(title = "HRV Analytics") {

                            if (session.startRmssdMs != null && session.endRmssdMs != null) {
                                val delta = session.endRmssdMs - session.startRmssdMs
                                val sign  = if (delta >= 0) "+" else ""
                                val deltaColor = if (delta >= 0) TextPrimary else TextSecondary

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    HrvStatBox(
                                        label = "Start RMSSD",
                                        value = "${String.format("%.1f", session.startRmssdMs)} ms",
                                        color = TextSecondary
                                    )
                                    HrvStatBox(
                                        label = "Change",
                                        value = "$sign${String.format("%.1f", delta)} ms",
                                        color = deltaColor
                                    )
                                    HrvStatBox(
                                        label = "End RMSSD",
                                        value = "${String.format("%.1f", session.endRmssdMs)} ms",
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    if (delta >= 0)
                                        "✓ HRV improved during session — parasympathetic activation"
                                    else
                                        "HRV decreased slightly — may indicate residual stress",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (delta >= 0) TextPrimary else TextDisabled,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                HorizontalDivider(
                                    color = SurfaceDark,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            } else {
                                session.startRmssdMs?.let {
                                    DetailRow("Start RMSSD", "${String.format("%.1f", it)} ms")
                                }
                                session.endRmssdMs?.let {
                                    DetailRow("End RMSSD", "${String.format("%.1f", it)} ms")
                                }
                            }

                            session.lnRmssdSlope?.let { slope ->
                                val sign  = if (slope >= 0) "+" else ""
                                val color = if (slope >= 0) TextPrimary else TextSecondary
                                DetailRowColored(
                                    label = "ln(RMSSD) Slope",
                                    value = "$sign${String.format("%.4f", slope)}",
                                    valueColor = color
                                )
                                Text(
                                    if (slope >= 0)
                                        "✓ Positive slope — HRV trending upward through session"
                                    else
                                        "Negative slope — HRV trended downward through session",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (slope >= 0) TextPrimary else TextDisabled,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    // ── No HR data note ────────────────────────────────────────
                    if (session.avgHrBpm == null) {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("ℹ️", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "No HR data was recorded for this session. Connect a heart rate monitor to capture HR and HRV analytics.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    // ── Session metadata ───────────────────────────────────────
                    DetailSection(title = "Session Info") {
                        DetailRow("Session ID", session.sessionId.toString())
                        DetailRow(
                            "Started",
                            Instant.ofEpochMilli(session.timestamp).atZone(zone)
                                .format(DateTimeFormatter.ofPattern("MMM d yyyy, h:mm a"))
                        )
                        DetailRow("Duration (ms)", session.durationMs.toString())
                        if (telemetry.isNotEmpty()) {
                            DetailRow("Telemetry samples", telemetry.size.toString())
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ── Header stat chip ───────────────────────────────────────────────────────────

@Composable
private fun HeaderStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled
        )
    }
}

// ── HRV stat box ───────────────────────────────────────────────────────────────

@Composable
private fun HrvStatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled
        )
    }
}

// ── Section wrapper ────────────────────────────────────────────────────────────

@Composable
private fun DetailSection(
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

// ── Detail rows ────────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@Composable
private fun DetailRowColored(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1.5f)
        )
    }
}

// ── Telemetry chart card ───────────────────────────────────────────────────────

@Composable
private fun TelemetryChartCard(
    title: String,
    subtitle: String,
    values: List<Float>,
    lineColor: Color,
    unit: String,
    totalDurationMs: Long,
    timerFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    val yMin = (values.minOrNull() ?: 0f) * 0.92f
    val yMax = (values.maxOrNull() ?: 1f) * 1.08f
    val avg  = values.average().toFloat()
    val minV = values.minOrNull() ?: 0f
    val maxV = values.maxOrNull() ?: 0f

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title + stats row
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat("min", "${minV.toInt()} $unit", TextSecondary)
                    MiniStat("avg", "${avg.toInt()} $unit", lineColor)
                    MiniStat("max", "${maxV.toInt()} $unit", TextSecondary)
                }
            }

            HorizontalDivider(color = SurfaceDark)

            // Chart
            SessionLineChart(
                values          = values,
                yMin            = yMin,
                yMax            = yMax,
                lineColor       = lineColor,
                unit            = unit,
                totalDurationMs = totalDurationMs,
                timerFraction   = timerFraction,
                modifier        = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )
        }
    }
}

// ── Mini stat chip ─────────────────────────────────────────────────────────────

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

// ── Canvas line chart with tap-to-inspect + timer marker ───────────────────────

@Composable
private fun SessionLineChart(
    values: List<Float>,
    yMin: Float,
    yMax: Float,
    lineColor: Color,
    unit: String,
    totalDurationMs: Long,
    timerFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Not enough data", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        return
    }

    val density         = LocalDensity.current
    val leftPadPx       = with(density) { 36.dp.toPx() }
    val rightPadPx      = with(density) { 6.dp.toPx() }
    val topPadPx        = with(density) { 6.dp.toPx() }
    val bottomPadPx     = with(density) { 6.dp.toPx() }
    val labelTextSizePx = with(density) { 9.sp.toPx() }

    val yRange    = (yMax - yMin).coerceAtLeast(1f)
    val gridColor = TextDisabled.copy(alpha = 0.15f)
    val labelArgb = TextSecondary.copy(alpha = 0.7f).toArgb()

    // Tap state: index of the tapped data point, null = no popup
    var tappedIndex by remember { mutableStateOf<Int?>(null) }
    // Store layout dimensions for popup positioning
    var chartWidthPx by remember { mutableFloatStateOf(0f) }
    var chartHeightPx by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val totalW   = size.width.toFloat()
                        val plotLeft = leftPadPx
                        val plotRight = totalW - rightPadPx
                        val plotW    = (plotRight - plotLeft).coerceAtLeast(1f)
                        // Map tap x to nearest data index
                        val frac = ((offset.x - plotLeft) / plotW).coerceIn(0f, 1f)
                        val idx  = (frac * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
                        tappedIndex = if (tappedIndex == idx) null else idx
                        chartWidthPx = totalW
                        chartHeightPx = size.height.toFloat()
                    }
                }
        ) {
            val totalW    = size.width
            val totalH    = size.height
            val plotLeft  = leftPadPx
            val plotRight = totalW - rightPadPx
            val plotTop   = topPadPx
            val plotBot   = totalH - bottomPadPx
            val plotW     = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH     = (plotBot - plotTop).coerceAtLeast(1f)

            fun xOf(i: Int)   = plotLeft + i.toFloat() / (values.size - 1).toFloat() * plotW
            fun yOf(v: Float) = plotTop + plotH * (1f - ((v - yMin) / yRange).coerceIn(0f, 1f))

            // Horizontal grid lines
            listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { frac ->
                val gy = plotTop + plotH * (1f - frac)
                drawLine(gridColor, Offset(plotLeft, gy), Offset(plotRight, gy), strokeWidth = 1f)
            }

            // Timer end marker (dashed vertical line)
            if (timerFraction != null && timerFraction in 0.01f..0.99f) {
                val timerX = plotLeft + timerFraction * plotW
                drawLine(
                    color       = Color(0xFFFF9800),
                    start       = Offset(timerX, plotTop),
                    end         = Offset(timerX, plotBot),
                    strokeWidth = 2f,
                    pathEffect  = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
                // Small "Timer" label above the line
                val timerPaint = Paint().apply {
                    isAntiAlias = true
                    textSize    = labelTextSizePx
                    color       = Color(0xFFFF9800).toArgb()
                    textAlign   = Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "⏱",
                    timerX,
                    plotTop - with(density) { 1.dp.toPx() },
                    timerPaint
                )
            }

            // Fill path
            val fillPath = Path().apply {
                moveTo(xOf(0), plotBot)
                lineTo(xOf(0), yOf(values[0]))
                for (i in 1 until values.size) lineTo(xOf(i), yOf(values[i]))
                lineTo(xOf(values.size - 1), plotBot)
                close()
            }
            drawPath(fillPath, color = lineColor.copy(alpha = 0.12f))

            // Line path
            val linePath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                for (i in 1 until values.size) lineTo(xOf(i), yOf(values[i]))
            }
            drawPath(
                linePath,
                color = lineColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Start / end dots
            drawCircle(lineColor, radius = 4.5f, center = Offset(xOf(0), yOf(values.first())))
            drawCircle(lineColor, radius = 4.5f, center = Offset(xOf(values.size - 1), yOf(values.last())))

            // Tapped point indicator
            tappedIndex?.let { idx ->
                val cx = xOf(idx)
                val cy = yOf(values[idx])
                // Vertical crosshair
                drawLine(
                    TextSecondary.copy(alpha = 0.4f),
                    Offset(cx, plotTop),
                    Offset(cx, plotBot),
                    strokeWidth = 1f
                )
                // Dot
                drawCircle(lineColor, radius = 6f, center = Offset(cx, cy))
                drawCircle(SurfaceVariant, radius = 3f, center = Offset(cx, cy))
            }

            // Y-axis labels
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
        }

        // ── Info popup ─────────────────────────────────────────────────────
        tappedIndex?.let { idx ->
            val dataFrac = idx.toFloat() / (values.size - 1).coerceAtLeast(1).toFloat()
            val timeMs   = (dataFrac * totalDurationMs).toLong()
            val timeSec  = timeMs / 1_000L
            val mm       = timeSec / 60L
            val ss       = timeSec % 60L
            val timeStr  = "${mm}m ${ss}s"
            val valStr   = "${values[idx].toInt()} $unit"

            // Position popup near the tapped point
            val popupOffsetX = with(density) {
                val plotLeft  = 36.dp.toPx()
                val plotRight = chartWidthPx - 6.dp.toPx()
                val plotW     = (plotRight - plotLeft).coerceAtLeast(1f)
                val px        = plotLeft + dataFrac * plotW
                (px - 50.dp.toPx()).toInt()  // center the ~100dp popup
            }

            Popup(
                alignment = Alignment.TopStart,
                offset    = IntOffset(popupOffsetX, with(density) { (-4).dp.roundToPx() }),
                onDismissRequest = { tappedIndex = null },
                properties = PopupProperties(focusable = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceDark,
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            valStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = lineColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
