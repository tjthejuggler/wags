package com.example.wags.ui.rapidhr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.RapidHrSessionEntity
import com.example.wags.data.db.entity.RapidHrTelemetryEntity
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "%d:%02d.%d".format(min, sec, tenths)
    else "%d.%d s".format(sec, tenths)
}

private val detailDateFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy  h:mm a")

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RapidHrDetailScreen(
    navController: NavController,
    viewModel: RapidHrDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Session Detail", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TextSecondary)
                }
            }
            state.session == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Session not found", color = TextSecondary)
                }
            }
            else -> {
                DetailContent(
                    session = state.session!!,
                    telemetry = state.telemetry,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

// ── Detail content ─────────────────────────────────────────────────────────────

@Composable
private fun DetailContent(
    session: RapidHrSessionEntity,
    telemetry: List<RapidHrTelemetryEntity>,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(session.timestamp).atZone(zone)
    val direction = runCatching { RapidHrDirection.valueOf(session.direction) }
        .getOrDefault(RapidHrDirection.HIGH_TO_LOW)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (session.isPersonalBest) {
                    Text(
                        "🏆 Personal Best",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    formatMs(session.transitionDurationMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "Transition Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${direction.label}  •  ${session.highThreshold}→${session.lowThreshold} bpm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    zdt.format(detailDateFmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Timing breakdown
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Timing", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailStatCell("Phase 1", formatMs(session.phase1DurationMs))
                    DetailStatCell("Transition", formatMs(session.transitionDurationMs))
                    DetailStatCell("Total", formatMs(session.totalDurationMs))
                }
            }
        }

        // HR stats
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Heart Rate", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailStatCell("Peak", "${session.peakHrBpm} bpm")
                    DetailStatCell("Trough", "${session.troughHrBpm} bpm")
                    session.avgHrBpm?.let { avg ->
                        DetailStatCell("Avg", "%.1f bpm".format(avg))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailStatCell("At 1st crossing", "${session.hrAtFirstCrossing} bpm")
                    DetailStatCell("At 2nd crossing", "${session.hrAtSecondCrossing} bpm")
                }
                session.monitorId?.let { id ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Monitor: $id",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // HR chart with threshold lines
        if (telemetry.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("HR Over Session", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    HrSessionChart(
                        telemetry = telemetry,
                        highThreshold = session.highThreshold,
                        lowThreshold = session.lowThreshold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendItem(Color(0xFF888888), "High threshold (${session.highThreshold})")
                        LegendItem(Color(0xFF555555), "Low threshold (${session.lowThreshold})")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailStatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, textAlign = TextAlign.Center)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(12.dp, 2.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

// ── HR chart with threshold lines ──────────────────────────────────────────────

@Composable
private fun HrSessionChart(
    telemetry: List<RapidHrTelemetryEntity>,
    highThreshold: Int,
    lowThreshold: Int,
    modifier: Modifier = Modifier
) {
    if (telemetry.isEmpty()) return

    val hrValues = telemetry.map { it.hrBpm.toFloat() }
    val allValues = hrValues + listOf(highThreshold.toFloat(), lowThreshold.toFloat())
    val yMin = allValues.min()
    val yMax = allValues.max()
    val yPad = ((yMax - yMin) * 0.15f).coerceAtLeast(5f)
    val yLow = yMin - yPad
    val yHigh = yMax + yPad
    val yRange = yHigh - yLow

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = telemetry.size
        val xStep = if (n > 1) w / (n - 1) else w

        fun xOf(i: Int) = i * xStep
        fun yOf(v: Float) = h - ((v - yLow) / yRange * h)

        // Threshold lines (dashed)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        drawLine(
            color = Color(0xFF888888),
            start = Offset(0f, yOf(highThreshold.toFloat())),
            end = Offset(w, yOf(highThreshold.toFloat())),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = dashEffect
        )
        drawLine(
            color = Color(0xFF555555),
            start = Offset(0f, yOf(lowThreshold.toFloat())),
            end = Offset(w, yOf(lowThreshold.toFloat())),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = dashEffect
        )

        // Phase 1 vs Phase 2 background tint
        val transitionStart = telemetry.indexOfFirst { it.phase == RapidHrPhase.TRANSITIONING.name }
        if (transitionStart > 0) {
            val xTrans = xOf(transitionStart)
            drawRect(
                color = Color(0xFFFFFFFF).copy(alpha = 0.02f),
                topLeft = Offset(xTrans, 0f),
                size = androidx.compose.ui.geometry.Size(w - xTrans, h)
            )
        }

        // Fill
        val fillPath = Path().apply {
            moveTo(xOf(0), h)
            telemetry.forEachIndexed { i, t ->
                lineTo(xOf(i), yOf(t.hrBpm.toFloat()))
            }
            lineTo(xOf(n - 1), h)
            close()
        }
        drawPath(fillPath, color = Color(0xFFD0D0D0).copy(alpha = 0.08f))

        // HR line
        val linePath = Path().apply {
            telemetry.forEachIndexed { i, t ->
                val x = xOf(i)
                val y = yOf(t.hrBpm.toFloat())
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(linePath, color = Color(0xFFD0D0D0), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
