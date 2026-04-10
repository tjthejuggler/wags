package com.example.wags.ui.apnea

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val HrLineColor  = Color(0xFFD0D0D0)
private val SpO2LineColor = Color(0xFFB0B0B0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveO2DetailScreen(
    navController: NavController,
    viewModel: ProgressiveO2DetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progressive O\u2082 Detail") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark, titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = TextSecondary) }
            state.notFound -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center
            ) { Text("Session not found", color = TextSecondary) }
            else -> DetailBody(state, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DetailBody(state: ProgressiveO2DetailUiState, modifier: Modifier = Modifier) {
    val durationMs = state.sessionDurationSec * 1000L
    val hrSamples = remember(state.telemetry) {
        state.telemetry.mapNotNull { it.heartRateBpm }.filter { it in 20..250 }.map { it.toFloat() }
    }
    val spO2Samples = remember(state.telemetry) {
        state.telemetry.mapNotNull { it.spO2 }.filter { it > 0 }.map { it.toFloat() }
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        SessionSummaryCard(state)
        if (hrSamples.size >= 2) {
            ChartCard("Heart Rate", hrSamples, HrLineColor, durationMs) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    state.minHr?.let { SmallLabel("Min: $it bpm", TextPrimary) }
                    state.avgHr?.let { SmallLabel("Avg: $it bpm", TextSecondary) }
                    state.maxHr?.let { SmallLabel("Max: $it bpm", TextPrimary) }
                }
            }
        }
        if (spO2Samples.size >= 2) {
            val yMin = (spO2Samples.minOrNull() ?: 80f).coerceAtMost(80f)
            ChartCard("SpO\u2082", spO2Samples, SpO2LineColor, durationMs, yMin, 100f) {
                state.lowestSpO2?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        SmallLabel("Lowest: $it%", SpO2LineColor)
                    }
                }
            }
        }
        if (state.roundResults.isNotEmpty()) RoundsBreakdownCard(state.roundResults)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SessionSummaryCard(state: ProgressiveO2DetailUiState) {
    val session = state.session ?: return
    val dateStr = remember(session.timestamp) {
        SimpleDateFormat("EEE, MMM d yyyy · h:mm a", Locale.getDefault())
            .format(Date(session.timestamp))
    }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dateStr, style = MaterialTheme.typography.titleMedium,
                color = TextPrimary, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = SurfaceVariant)
            DetailRow("Breath period", "${state.breathPeriodSec}s")
            DetailRow("Rounds", "${state.roundsCompleted} / ${state.totalRoundsAttempted}")
            DetailRow("Max hold reached", formatSec(state.maxHoldReachedSec), valueBold = true)
            DetailRow("Total hold time", formatSec(state.totalHoldTimeSec), valueBold = true)
            DetailRow("Session duration", formatSec(state.sessionDurationSec))
            session.hrDeviceId?.let { DetailRow("Device", it) }
            if (state.minHr != null || state.maxHr != null) {
                HorizontalDivider(color = SurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    state.minHr?.let { StatBox("Min HR", "$it bpm", TextPrimary) }
                    state.avgHr?.let { StatBox("Avg HR", "$it bpm", TextPrimary) }
                    state.maxHr?.let { StatBox("Max HR", "$it bpm", TextPrimary) }
                }
            }
            state.lowestSpO2?.let { spo2 ->
                if (state.minHr == null && state.maxHr == null) HorizontalDivider(color = SurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    StatBox("Lowest SpO\u2082", "$spo2%", SpO2LineColor)
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    samples: List<Float>,
    lineColor: Color,
    durationMs: Long,
    yMin: Float = samples.minOrNull() ?: 0f,
    yMax: Float = samples.maxOrNull() ?: 1f,
    footer: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            TelemetryLineChart(
                samples = samples, lineColor = lineColor,
                yMin = yMin, yMax = yMax, durationMs = durationMs, showYLabels = true,
                modifier = Modifier.fillMaxWidth().height(200.dp)
                    .background(SurfaceVariant.copy(alpha = 0.3f))
            )
            Spacer(Modifier.height(8.dp))
            footer()
        }
    }
}

@Composable
private fun RoundsBreakdownCard(rounds: List<RoundDisplayData>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Rounds", style = MaterialTheme.typography.titleMedium,
                color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("#", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, modifier = Modifier.width(28.dp))
                Text("Target", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, modifier = Modifier.weight(1f))
                Text("Actual", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(28.dp))
            }
            HorizontalDivider(color = SurfaceVariant)
            rounds.forEachIndexed { idx, round ->
                val bg = if (idx % 2 == 0) Color.Transparent else SurfaceVariant.copy(alpha = 0.3f)
                Row(
                    Modifier.fillMaxWidth().background(bg).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${round.roundNumber}", style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary, modifier = Modifier.width(28.dp))
                    Text(formatSec(round.targetSec), style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary, modifier = Modifier.weight(1f))
                    Text(formatSec(round.actualSec), style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary, modifier = Modifier.weight(1f))
                    Text(
                        if (round.completed) "✓" else "✗",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (round.completed) TextPrimary else TextSecondary,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp)
                    )
                }
            }
        }
    }
}

// Canvas line chart — follows ApneaRecordDetailScreen.LineChart pattern
@Composable
private fun TelemetryLineChart(
    samples: List<Float>, lineColor: Color, modifier: Modifier = Modifier,
    yMin: Float = samples.minOrNull() ?: 0f, yMax: Float = samples.maxOrNull() ?: 1f,
    durationMs: Long = 0L, showYLabels: Boolean = false
) {
    if (samples.size < 2) return
    val density = LocalDensity.current
    val leftPad  = if (showYLabels) with(density) { 34.dp.toPx() } else with(density) { 4.dp.toPx() }
    val botPad   = with(density) { 16.dp.toPx() }
    val topPad   = with(density) { 4.dp.toPx() }
    val rightPad = with(density) { 4.dp.toPx() }
    val labelSz  = with(density) { 9.sp.toPx() }
    val lblColor = TextSecondary.copy(alpha = 0.7f)
    val lblArgb  = lblColor.toArgb()
    val yRange   = (yMax - yMin).coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val pL = leftPad; val pR = size.width - rightPad
        val pT = topPad;  val pB = size.height - botPad
        val pW = (pR - pL).coerceAtLeast(1f); val pH = (pB - pT).coerceAtLeast(1f)
        val stepX = pW / (samples.size - 1).toFloat()

        // Grid lines
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            val y = pT + pH * (1f - f)
            drawLine(TextDisabled.copy(alpha = 0.15f), Offset(pL, y), Offset(pR, y), 1f)
        }
        // Data line
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = pL + i * stepX
            val y = pT + pH * (1f - ((v - yMin) / yRange).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // End dots
        val fY = pT + pH * (1f - ((samples.first() - yMin) / yRange).coerceIn(0f, 1f))
        val lY = pT + pH * (1f - ((samples.last()  - yMin) / yRange).coerceIn(0f, 1f))
        drawCircle(lineColor, 5f, Offset(pL, fY)); drawCircle(lineColor, 5f, Offset(pR, lY))
        // Y labels
        if (showYLabels) {
            val paint = Paint().apply {
                isAntiAlias = true; textSize = labelSz; color = lblArgb; textAlign = Paint.Align.RIGHT
            }
            for (s in 0..3) {
                val f = s / 3f; val v = yMin + f * yRange; val y = pT + pH * (1f - f)
                drawContext.canvas.nativeCanvas.drawText(
                    "${v.toInt()}", pL - with(density) { 3.dp.toPx() }, y + labelSz / 2f, paint
                )
            }
        }
        // X labels
        if (durationMs > 0L) {
            val paint = Paint().apply {
                isAntiAlias = true; textSize = labelSz; color = lblArgb; textAlign = Paint.Align.CENTER
            }
            val totalMin = (durationMs / 60_000L).toInt()
            for (m in 1..totalMin) {
                val f = (m * 60_000L).toFloat() / durationMs.toFloat()
                if (f > 1f) break
                val x = pL + f * pW
                drawLine(lblColor, Offset(x, pB), Offset(x, pB + with(density) { 3.dp.toPx() }), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    "${m}m", x, size.height - with(density) { 1.dp.toPx() }, paint
                )
            }
        }
    }
}

// Helpers

@Composable
private fun DetailRow(label: String, value: String, valueBold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, color = TextPrimary,
            style = if (valueBold) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            else MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium,
            color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun SmallLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun formatSec(totalSec: Int): String {
    val m = totalSec / 60; val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
