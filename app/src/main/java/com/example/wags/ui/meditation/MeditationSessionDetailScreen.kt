package com.example.wags.ui.meditation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ln

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationSessionDetailScreen(
    navController: NavController,
    viewModel: MeditationSessionDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Pop back automatically once the session has been deleted
    LaunchedEffect(state.deleted) {
        if (state.deleted) navController.popBackStack()
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
                actions = {
                    // Only show delete when a session is loaded
                    if (state.session != null) {
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
                val audio   = state.audio
                val zone    = ZoneId.systemDefault()
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
                                    color = EcgCyan
                                )
                                if (session.avgHrBpm != null) {
                                    HeaderStat(
                                        label = "Avg HR",
                                        value = "${String.format("%.0f", session.avgHrBpm)} bpm",
                                        color = ReadinessOrange
                                    )
                                }
                                if (session.endRmssdMs != null) {
                                    HeaderStat(
                                        label = "End RMSSD",
                                        value = "${String.format("%.1f", session.endRmssdMs)} ms",
                                        color = ReadinessGreen
                                    )
                                }
                            }
                        }
                    }

                    // ── Overview card ──────────────────────────────────────────
                    DetailSection(title = "Overview") {
                        DetailRow("Audio", audioName)
                        DetailRow("Duration", "${durationMin}m ${durationSec}s")
                        DetailRow("Monitor", session.monitorId ?: "None")
                        audio?.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            DetailRow("Source URL", url)
                        }
                        if (!audio?.youtubeChannel.isNullOrBlank()) {
                            DetailRow("Channel", audio!!.youtubeChannel!!)
                        }
                    }

                    // ── HR analytics card + chart ──────────────────────────────
                    if (session.avgHrBpm != null) {
                        DetailSection(title = "Heart Rate Analytics") {
                            DetailRow("Avg HR", "${String.format("%.1f", session.avgHrBpm)} BPM")
                            session.hrSlopeBpmPerMin?.let { slope ->
                                val sign  = if (slope >= 0) "+" else ""
                                val color = if (slope <= 0) ReadinessGreen else ReadinessOrange
                                DetailRowColored(
                                    label = "HR Trend",
                                    value = "$sign${String.format("%.2f", slope)} BPM/min",
                                    valueColor = color
                                )
                                Spacer(Modifier.height(8.dp))
                                // HR slope bar chart
                                HrSlopeChart(
                                    slopeBpmPerMin = slope,
                                    modifier = Modifier.fillMaxWidth().height(72.dp)
                                )
                                Text(
                                    if (slope <= 0)
                                        "✓ Heart rate decreased during session — good relaxation response"
                                    else
                                        "Heart rate increased slightly during session",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (slope <= 0) ReadinessGreen else TextDisabled,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // ── HRV analytics card + charts ────────────────────────────
                    if (session.startRmssdMs != null || session.endRmssdMs != null || session.lnRmssdSlope != null) {
                        DetailSection(title = "HRV Analytics") {

                            // Start / End RMSSD comparison
                            if (session.startRmssdMs != null && session.endRmssdMs != null) {
                                val delta = session.endRmssdMs - session.startRmssdMs
                                val sign  = if (delta >= 0) "+" else ""
                                val deltaColor = if (delta >= 0) ReadinessGreen else ReadinessOrange

                                // Side-by-side RMSSD bars
                                RmssdComparisonChart(
                                    startMs = session.startRmssdMs,
                                    endMs   = session.endRmssdMs,
                                    modifier = Modifier.fillMaxWidth().height(80.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "${String.format("%.1f", session.startRmssdMs)} ms",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = EcgCyan,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Start RMSSD",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextDisabled
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "$sign${String.format("%.1f", delta)} ms",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = deltaColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Change",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextDisabled
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "${String.format("%.1f", session.endRmssdMs)} ms",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = ReadinessGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "End RMSSD",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextDisabled
                                        )
                                    }
                                }
                                Text(
                                    if (delta >= 0)
                                        "✓ HRV improved during session — parasympathetic activation"
                                    else
                                        "HRV decreased slightly — may indicate residual stress",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (delta >= 0) ReadinessGreen else TextDisabled,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                HorizontalDivider(
                                    color = SurfaceDark,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                session.startRmssdMs?.let {
                                    DetailRow("Start RMSSD", "${String.format("%.1f", it)} ms")
                                }
                                session.endRmssdMs?.let {
                                    DetailRow("End RMSSD", "${String.format("%.1f", it)} ms")
                                }
                            }

                            // ln(RMSSD) slope
                            session.lnRmssdSlope?.let { slope ->
                                val sign  = if (slope >= 0) "+" else ""
                                val color = if (slope >= 0) ReadinessGreen else ReadinessOrange
                                DetailRowColored(
                                    label = "ln(RMSSD) Slope",
                                    value = "$sign${String.format("%.4f", slope)}",
                                    valueColor = color
                                )
                                Spacer(Modifier.height(8.dp))
                                LnRmssdSlopeChart(
                                    slope = slope,
                                    modifier = Modifier.fillMaxWidth().height(72.dp)
                                )
                                Text(
                                    if (slope >= 0)
                                        "✓ Positive slope — HRV trending upward through session"
                                    else
                                        "Negative slope — HRV trended downward through session",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (slope >= 0) ReadinessGreen else TextDisabled,
                                    modifier = Modifier.padding(top = 4.dp)
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
                color = EcgCyan,
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

// ── HR slope chart ─────────────────────────────────────────────────────────────
// Visualises the HR trend as a diagonal line from left to right, with a zero
// reference line in the middle. Positive slope goes up (orange), negative goes
// down (green).

@Composable
private fun HrSlopeChart(
    slopeBpmPerMin: Float,
    modifier: Modifier = Modifier
) {
    val lineColor = if (slopeBpmPerMin <= 0) ReadinessGreen else ReadinessOrange
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Zero reference line
        drawLine(
            color = Color.Gray.copy(alpha = 0.35f),
            start = Offset(0f, midY),
            end   = Offset(w, midY),
            strokeWidth = 1.5f
        )

        // Clamp visual deflection to ±40% of half-height
        val maxDeflect = h * 0.4f
        val clampedSlope = slopeBpmPerMin.coerceIn(-5f, 5f)
        val deflect = (clampedSlope / 5f) * maxDeflect

        val startY = midY + deflect   // positive slope → start high, end low (inverted canvas)
        val endY   = midY - deflect

        // Fill
        val fillPath = Path().apply {
            moveTo(0f, midY)
            lineTo(0f, startY)
            lineTo(w, endY)
            lineTo(w, midY)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = 0.15f))

        // Trend line
        drawLine(
            color = lineColor,
            start = Offset(0f, startY),
            end   = Offset(w, endY),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Endpoint dot
        drawCircle(color = lineColor, radius = 6f, center = Offset(w, endY))
        drawCircle(color = BackgroundDark, radius = 3f, center = Offset(w, endY))

        // Zero label tick marks
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(0f, midY - 2f),
            end   = Offset(0f, midY + 2f),
            strokeWidth = 2f
        )
    }
}

// ── RMSSD comparison bar chart ─────────────────────────────────────────────────
// Two horizontal bars side-by-side: start (cyan) and end (green).

@Composable
private fun RmssdComparisonChart(
    startMs: Float,
    endMs: Float,
    modifier: Modifier = Modifier
) {
    val maxVal = maxOf(startMs, endMs).coerceAtLeast(1f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barH = h * 0.35f
        val gap  = h * 0.1f
        val topY1 = h * 0.05f
        val topY2 = topY1 + barH + gap

        val startFrac = (startMs / maxVal).coerceIn(0f, 1f)
        val endFrac   = (endMs   / maxVal).coerceIn(0f, 1f)

        // Background tracks
        drawRoundRect(
            color = SurfaceDark,
            topLeft = Offset(0f, topY1),
            size = androidx.compose.ui.geometry.Size(w, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2)
        )
        drawRoundRect(
            color = SurfaceDark,
            topLeft = Offset(0f, topY2),
            size = androidx.compose.ui.geometry.Size(w, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2)
        )

        // Start bar (cyan)
        if (startFrac > 0f) {
            drawRoundRect(
                color = EcgCyan,
                topLeft = Offset(0f, topY1),
                size = androidx.compose.ui.geometry.Size(w * startFrac, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2)
            )
        }

        // End bar (green)
        if (endFrac > 0f) {
            drawRoundRect(
                color = ReadinessGreen,
                topLeft = Offset(0f, topY2),
                size = androidx.compose.ui.geometry.Size(w * endFrac, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2)
            )
        }
    }
}

// ── ln(RMSSD) slope chart ──────────────────────────────────────────────────────
// Similar to HR slope but uses a simulated ln(RMSSD) curve shape.

@Composable
private fun LnRmssdSlopeChart(
    slope: Float,
    modifier: Modifier = Modifier
) {
    val lineColor = if (slope >= 0) ReadinessGreen else ReadinessOrange
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Zero reference
        drawLine(
            color = Color.Gray.copy(alpha = 0.35f),
            start = Offset(0f, midY),
            end   = Offset(w, midY),
            strokeWidth = 1.5f
        )

        val maxDeflect = h * 0.38f
        val clampedSlope = slope.coerceIn(-0.01f, 0.01f)
        val deflect = (clampedSlope / 0.01f) * maxDeflect

        // Draw a smooth curve using 20 points
        val points = (0..20).map { i ->
            val t = i / 20f
            val x = t * w
            // Slight S-curve: more change in the middle
            val curveT = t * t * (3f - 2f * t)
            val y = midY - deflect * curveT
            Offset(x, y)
        }

        // Fill
        val fillPath = Path().apply {
            moveTo(0f, midY)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(w, midY)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = 0.15f))

        // Curve line
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // Endpoint dot
        val last = points.last()
        drawCircle(color = lineColor, radius = 6f, center = last)
        drawCircle(color = BackgroundDark, radius = 3f, center = last)
    }
}
