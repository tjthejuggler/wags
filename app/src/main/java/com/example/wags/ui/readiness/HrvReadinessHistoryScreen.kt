package com.example.wags.ui.readiness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvReadinessHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HrvReadinessHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("HRV Readiness History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (uiState.allReadings.isEmpty()) {
            HrvEmptyHistoryContent(modifier = Modifier.padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header summary
                HrvHistorySummaryCard(uiState = uiState)

                // ── 1. Readiness Score ─────────────────────────────────────
                HrvGraphSection(
                    title = "Readiness Score",
                    subtitle = "0–100 composite score over time"
                ) {
                    HrvReadinessScoreChart(points = uiState.chartData.readinessScore)
                }

                // ── 2. RMSSD ───────────────────────────────────────────────
                HrvGraphSection(
                    title = "RMSSD",
                    subtitle = "Root mean square of successive differences (ms)"
                ) {
                    HrvMetricChart(
                        label = "RMSSD (ms)",
                        points = uiState.chartData.rmssd,
                        lineColor = EcgCyan
                    )
                }

                // ── 3. ln(RMSSD) ───────────────────────────────────────────
                HrvGraphSection(
                    title = "ln(RMSSD)",
                    subtitle = "Natural log of RMSSD — used for baseline scoring"
                ) {
                    HrvMetricChart(
                        label = "ln(RMSSD)",
                        points = uiState.chartData.lnRmssd,
                        lineColor = ReadinessGreen
                    )
                }

                // ── 4. SDNN ────────────────────────────────────────────────
                HrvGraphSection(
                    title = "SDNN",
                    subtitle = "Standard deviation of NN intervals (ms)"
                ) {
                    HrvMetricChart(
                        label = "SDNN (ms)",
                        points = uiState.chartData.sdnn,
                        lineColor = ReadinessBlue
                    )
                }

                // ── 5. Resting HR ──────────────────────────────────────────
                HrvGraphSection(
                    title = "Resting Heart Rate",
                    subtitle = "Average HR during 2-minute session (bpm)"
                ) {
                    HrvMetricChart(
                        label = "Resting HR (bpm)",
                        points = uiState.chartData.restingHr,
                        lineColor = ReadinessOrange,
                        invertGood = true
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Summary card ───────────────────────────────────────────────────────────────

@Composable
private fun HrvHistorySummaryCard(uiState: HrvReadinessHistoryUiState) {
    val count = uiState.allReadings.size
    val latestScore = uiState.chartData.readinessScore.lastOrNull()?.value?.toInt()
    val avgScore = if (uiState.chartData.readinessScore.isNotEmpty())
        uiState.chartData.readinessScore.map { it.value }.average().toInt() else null
    val latestRmssd = uiState.chartData.rmssd.lastOrNull()?.value
    val avgRmssd = if (uiState.chartData.rmssd.isNotEmpty())
        uiState.chartData.rmssd.map { it.value }.average().toFloat() else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Summary",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(color = SurfaceDark)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HrvSummaryChip(
                    label = "Sessions",
                    value = count.toString(),
                    color = EcgCyan
                )
                latestScore?.let {
                    HrvSummaryChip(
                        label = "Latest Score",
                        value = it.toString(),
                        color = hrvScoreColor(it.toFloat())
                    )
                }
                avgScore?.let {
                    HrvSummaryChip(
                        label = "Avg Score",
                        value = it.toString(),
                        color = hrvScoreColor(it.toFloat())
                    )
                }
            }
            if (latestRmssd != null && avgRmssd != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    HrvSummaryChip(
                        label = "Latest RMSSD",
                        value = String.format("%.1f ms", latestRmssd),
                        color = EcgCyan
                    )
                    HrvSummaryChip(
                        label = "Avg RMSSD",
                        value = String.format("%.1f ms", avgRmssd),
                        color = EcgCyanDim
                    )
                }
            }
        }
    }
}

// ── Graph section wrapper ──────────────────────────────────────────────────────

@Composable
private fun HrvGraphSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

// ── Readiness score chart ──────────────────────────────────────────────────────

@Composable
private fun HrvReadinessScoreChart(points: List<HrvChartPoint>) {
    if (points.isEmpty()) { HrvNoDataLabel(); return }

    val latest = points.last()
    val avg    = points.map { it.value }.average().toFloat()
    val min    = points.minOf { it.value }
    val max    = points.maxOf { it.value }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HrvStatChip("Latest", latest.value.toInt().toString(), hrvScoreColor(latest.value))
            HrvStatChip("Avg",    avg.toInt().toString(),          hrvScoreColor(avg))
            HrvStatChip("Min",    min.toInt().toString(),          hrvScoreColor(min))
            HrvStatChip("Max",    max.toInt().toString(),          hrvScoreColor(max))
        }

        HrvLineChartCanvas(
            points = points.map { it.index to it.value },
            lineColor = EcgCyan,
            fillAlpha = 0.15f,
            yMin = 0f,
            yMax = 100f,
            referenceLines = listOf(80f to ReadinessGreen, 60f to ReadinessOrange),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HrvZoneLegend("≥80 Green",   ReadinessGreen)
            HrvZoneLegend("60–79 Yellow", ReadinessOrange)
            HrvZoneLegend("<60 Red",      ReadinessRed)
        }
    }
}

// ── Generic metric chart ───────────────────────────────────────────────────────

@Composable
private fun HrvMetricChart(
    label: String,
    points: List<HrvChartPoint>,
    lineColor: Color,
    invertGood: Boolean = false
) {
    if (points.isEmpty()) { HrvNoDataLabel(); return }

    val latest = points.last()
    val avg    = points.map { it.value }.average().toFloat()
    val min    = points.minOf { it.value }
    val max    = points.maxOf { it.value }
    val yPad   = ((max - min) * 0.1f).coerceAtLeast(0.1f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                "Latest: ${String.format("%.2f", latest.value)}",
                style = MaterialTheme.typography.labelMedium,
                color = lineColor,
                fontWeight = FontWeight.Bold
            )
        }

        HrvLineChartCanvas(
            points = points.map { it.index to it.value },
            lineColor = lineColor,
            fillAlpha = 0.10f,
            yMin = min - yPad,
            yMax = max + yPad,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Avg ${String.format("%.2f", avg)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
            Text(
                "Min ${String.format("%.2f", min)}  Max ${String.format("%.2f", max)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }
    }
}

// ── Pure Canvas line chart ─────────────────────────────────────────────────────

@Composable
private fun HrvLineChartCanvas(
    points: List<Pair<Float, Float>>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    referenceLines: List<Pair<Float, Color>> = emptyList()
) {
    if (points.size < 2) {
        Canvas(modifier = modifier) {
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }

    val yRange = (yMax - yMin).coerceAtLeast(0.001f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val xStep = w / (points.size - 1).toFloat()

        fun xOf(i: Int) = i * xStep
        fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

        // Reference lines
        referenceLines.forEach { (refY, refColor) ->
            val ry = yOf(refY)
            drawLine(
                color = refColor.copy(alpha = 0.35f),
                start = Offset(0f, ry),
                end = Offset(w, ry),
                strokeWidth = 1.5f
            )
        }

        // Fill
        val fillPath = Path().apply {
            moveTo(xOf(0), h)
            lineTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].second))
            }
            lineTo(xOf(points.size - 1), h)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = fillAlpha))

        // Line
        val linePath = Path().apply {
            moveTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].second))
            }
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // Latest dot
        val lastX = xOf(points.size - 1)
        val lastY = yOf(points.last().second)
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
        drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun HrvSummaryChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun HrvStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun HrvZoneLegend(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun HrvNoDataLabel() {
    Text(
        "No data yet",
        style = MaterialTheme.typography.bodySmall,
        color = TextDisabled,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun HrvEmptyHistoryContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "No sessions yet",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary
        )
        Text(
            "Complete a 2-minute HRV Readiness session to see your history here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
    }
}

private fun hrvScoreColor(score: Float) = when {
    score >= 80f -> ReadinessGreen
    score >= 60f -> ReadinessOrange
    else         -> ReadinessRed
}
