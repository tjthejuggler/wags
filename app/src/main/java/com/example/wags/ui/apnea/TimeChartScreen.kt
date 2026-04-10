package com.example.wags.ui.apnea

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.ui.theme.*
import java.time.format.DateTimeFormatter

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeChartScreen(
    onBack: () -> Unit,
    viewModel: TimeChartViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Force landscape
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            if (state.showCumulative) "Cumulative" else "Daily",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    // Toggle between bar and cumulative line
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Daily", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Switch(
                            checked = state.showCumulative,
                            onCheckedChange = { viewModel.toggleCumulative() },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        Text("Cumulative", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TextSecondary)
            }
        } else {
            val data = if (state.showCumulative) state.cumulativeValues else state.dailyValues
            if (data.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No data yet", color = TextDisabled)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    if (state.showCumulative) {
                        CumulativeLineChart(data = data, modifier = Modifier.fillMaxSize())
                    } else {
                        DailyBarChart(data = data, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

// ── Bar chart (daily amounts) ──────────────────────────────────────────────────

@Composable
private fun DailyBarChart(data: List<DayValue>, modifier: Modifier = Modifier) {
    val maxMs = data.maxOfOrNull { it.ms } ?: 1L
    val dateFmt = DateTimeFormatter.ofPattern("M/d")
    val barColor = Color(0xFFB0B0B0)

    Canvas(modifier = modifier.background(SurfaceDark)) {
        val leftPad = 60f
        val bottomPad = 40f
        val chartW = size.width - leftPad
        val chartH = size.height - bottomPad
        val barW = (chartW / data.size.coerceAtLeast(1)).coerceAtMost(40f)
        val gap = ((chartW - barW * data.size) / (data.size + 1).coerceAtLeast(1))

        // Y-axis labels
        val paint = android.graphics.Paint().apply {
            color = 0xFFAAAAAA.toInt()
            textSize = 24f
            isAntiAlias = true
        }
        for (i in 0..4) {
            val frac = i / 4f
            val y = chartH * (1f - frac)
            val ms = (maxMs * frac).toLong()
            drawContext.canvas.nativeCanvas.drawText(
                formatChartDuration(ms), 2f, y + 8f, paint
            )
            // Grid line
            drawLine(
                color = Color(0xFF444444),
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        // Bars
        data.forEachIndexed { i, dv ->
            val x = leftPad + gap + i * (barW + gap)
            val barH = (dv.ms.toFloat() / maxMs) * chartH
            val y = chartH - barH

            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barW, barH)
            )

            // X-axis date label (show every Nth to avoid overlap)
            val showLabel = data.size <= 30 || i % (data.size / 15 + 1) == 0
            if (showLabel) {
                drawContext.canvas.nativeCanvas.drawText(
                    dv.date.format(dateFmt),
                    x, chartH + 30f, paint
                )
            }
        }
    }
}

// ── Line chart (cumulative) ────────────────────────────────────────────────────

@Composable
private fun CumulativeLineChart(data: List<DayValue>, modifier: Modifier = Modifier) {
    val maxMs = data.maxOfOrNull { it.ms } ?: 1L
    val dateFmt = DateTimeFormatter.ofPattern("M/d")
    val lineColor = Color(0xFFD0D0D0)

    Canvas(modifier = modifier.background(SurfaceDark)) {
        val leftPad = 60f
        val bottomPad = 40f
        val chartW = size.width - leftPad
        val chartH = size.height - bottomPad

        // Y-axis labels
        val paint = android.graphics.Paint().apply {
            color = 0xFFAAAAAA.toInt()
            textSize = 24f
            isAntiAlias = true
        }
        for (i in 0..4) {
            val frac = i / 4f
            val y = chartH * (1f - frac)
            val ms = (maxMs * frac).toLong()
            drawContext.canvas.nativeCanvas.drawText(
                formatChartDuration(ms), 2f, y + 8f, paint
            )
            drawLine(
                color = Color(0xFF444444),
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        if (data.size < 2) return@Canvas

        // Line path
        val path = Path()
        data.forEachIndexed { i, dv ->
            val x = leftPad + (i.toFloat() / (data.size - 1)) * chartW
            val y = chartH * (1f - dv.ms.toFloat() / maxMs)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))

        // Dots
        data.forEachIndexed { i, dv ->
            val x = leftPad + (i.toFloat() / (data.size - 1)) * chartW
            val y = chartH * (1f - dv.ms.toFloat() / maxMs)
            drawCircle(lineColor, radius = 4f, center = Offset(x, y))
        }

        // X-axis labels
        data.forEachIndexed { i, dv ->
            val showLabel = data.size <= 30 || i % (data.size / 15 + 1) == 0
            if (showLabel) {
                val x = leftPad + (i.toFloat() / (data.size - 1)) * chartW
                drawContext.canvas.nativeCanvas.drawText(
                    dv.date.format(dateFmt),
                    x - 15f, chartH + 30f, paint
                )
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatChartDuration(ms: Long): String {
    if (ms <= 0L) return "0"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    return when {
        h > 0 -> "${h}h${if (m > 0) " ${m}m" else ""}"
        m > 0 -> "${m}m"
        else  -> "${totalSec}s"
    }
}
