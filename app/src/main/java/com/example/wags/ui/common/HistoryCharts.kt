package com.example.wags.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.ui.theme.*
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Unified data models
// ---------------------------------------------------------------------------

/** A single (x=index, y=value) point for a line chart, with a date label. */
data class HistoryChartPoint(
    val index: Float,
    val value: Float,
    val dateLabel: String
)

/** Time period filter for history graphs — controls zoom level (how many points visible on screen). */
enum class HistoryTimePeriod(val label: String, val days: Int?, val visiblePoints: Int) {
    WEEK("7d", 7, 7),
    MONTH("1m", 30, 30),
    THREE_MONTHS("3m", 90, 90),
    YEAR("1y", 365, 365),
    ALL("All", null, 0);  // 0 = show all points, no scrolling
}

// ---------------------------------------------------------------------------
// Data aggregation utilities
// ---------------------------------------------------------------------------

/**
 * Aggregates daily data points into weekly or monthly averages based on the
 * selected time period.
 *
 * - WEEK / MONTH: returns points as-is (data points are days)
 * - THREE_MONTHS: aggregates into week averages
 * - YEAR / ALL: aggregates into month averages
 */
fun aggregatePoints(
    points: List<HistoryChartPoint>,
    period: HistoryTimePeriod
): List<HistoryChartPoint> {
    if (points.isEmpty()) return emptyList()

    return when (period) {
        HistoryTimePeriod.WEEK,
        HistoryTimePeriod.MONTH -> points

        HistoryTimePeriod.THREE_MONTHS -> aggregateByWeek(points)
        HistoryTimePeriod.YEAR,
        HistoryTimePeriod.ALL -> aggregateByMonth(points)
    }
}

private fun aggregateByWeek(
    points: List<HistoryChartPoint>
): List<HistoryChartPoint> {
    if (points.size <= 14) return points // Not enough data to justify aggregation

    return points.chunked(7).mapIndexed { idx, chunk ->
        val avgValue = chunk.map { it.value }.average().toFloat()
        HistoryChartPoint(
            index = idx.toFloat(),
            value = avgValue,
            dateLabel = chunk.first().dateLabel
        )
    }
}

private fun aggregateByMonth(
    points: List<HistoryChartPoint>
): List<HistoryChartPoint> {
    if (points.size <= 31) return points // Not enough data to justify aggregation

    // Group by year-month from the dateLabel (format: "yyyy-MM-dd")
    return points
        .groupBy { it.dateLabel.substringBeforeLast("-") }
        .entries
        .mapIndexed { idx, (_, chunk) ->
            val avgValue = chunk.map { it.value }.average().toFloat()
            HistoryChartPoint(
                index = idx.toFloat(),
                value = avgValue,
                dateLabel = chunk.first().dateLabel
            )
        }
}

// ---------------------------------------------------------------------------
// Time period selector
// ---------------------------------------------------------------------------

@Composable
fun HistoryTimePeriodSelector(
    selected: HistoryTimePeriod,
    onSelect: (HistoryTimePeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HistoryTimePeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) EcgCyan.copy(alpha = 0.25f) else SurfaceVariant)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) EcgCyan else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Graph section wrapper
// ---------------------------------------------------------------------------

@Composable
fun HistoryGraphSection(
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

// ---------------------------------------------------------------------------
// Tooltip card
// ---------------------------------------------------------------------------

@Composable
fun HistoryTooltipCard(label: String, value: String, date: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            Text(date, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Stat chip
// ---------------------------------------------------------------------------

@Composable
fun HistoryStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

// ---------------------------------------------------------------------------
// No data label
// ---------------------------------------------------------------------------

@Composable
fun HistoryNoDataLabel() {
    Text(
        "No data yet",
        style = MaterialTheme.typography.bodySmall,
        color = TextDisabled,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

// ---------------------------------------------------------------------------
// Format "MMM d" from ISO date string "yyyy-MM-dd"
// ---------------------------------------------------------------------------

private fun shortDate(isoDate: String): String = try {
    val parts = isoDate.split("-")
    val month = java.time.Month.of(parts[1].toInt()).name.take(3).lowercase()
        .replaceFirstChar { it.uppercase() }
    val day = parts[2].trimStart('0')
    "$month $day"
} catch (_: Exception) { isoDate }

// ---------------------------------------------------------------------------
// Smoothly scrollable line chart canvas
// ---------------------------------------------------------------------------

/**
 * Smoothly horizontally-scrollable line chart with x-axis labels and tap interaction.
 *
 * Both the chart canvas and the x-axis labels are inside the same scrollable
 * container, so they scroll together and labels always align with data points.
 *
 * @param points data points to render
 * @param lineColor color of the line and fill
 * @param fillAlpha alpha for the area fill under the line
 * @param yMin minimum Y value
 * @param yMax maximum Y value
 * @param referenceLines optional horizontal reference lines (value to color)
 * @param tooltipPoint currently highlighted point (from tap)
 * @param onTap callback when a point is tapped
 * @param isLandscape whether the device is in landscape orientation
 * @param chartHeightDp height of the chart area in dp
 * @param minPointSpacingDp minimum horizontal spacing between points in dp
 */
@Composable
fun ScrollableLineChartCanvas(
    points: List<HistoryChartPoint>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    referenceLines: List<Pair<Float, Color>> = emptyList(),
    tooltipPoint: HistoryChartPoint? = null,
    onTap: (HistoryChartPoint) -> Unit = {},
    isLandscape: Boolean = false,
    chartHeightDp: Int = 100,
    /** How many data points should be visible on screen at once. 0 = show all (no scrolling). */
    visiblePoints: Int = 0
) {
    if (points.isEmpty()) {
        HistoryNoDataLabel()
        return
    }

    if (points.size < 2) {
        Canvas(modifier = modifier.height(chartHeightDp.dp)) {
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }

    val xAxisHeightDp = 18
    val yRange = (yMax - yMin).coerceAtLeast(0.001f)
    val density = LocalDensity.current

    // Pick evenly-spaced label indices, always including first and last.
    val maxLabels = points.size.coerceIn(2, 8)
    val labelIndices: List<Int> = when {
        points.size <= maxLabels -> points.indices.toList()
        else -> {
            val step = (points.size - 1).toFloat() / (maxLabels - 1).toFloat()
            (0 until maxLabels).map { i -> (i * step).toInt().coerceIn(0, points.size - 1) }
        }
    }

    val scrollState = rememberScrollState()

    // Auto-scroll to the end (newest data) once the chart is laid out
    LaunchedEffect(scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportWidthDp = maxWidth.value

        // Calculate chart width based on zoom level:
        // - visiblePoints > 0: zoom so that many points fit in the viewport
        // - visiblePoints == 0 (All): fit all points in viewport, no scrolling
        val pointSpacingDp = if (visiblePoints > 0 && visiblePoints < points.size) {
            viewportWidthDp / visiblePoints.toFloat()
        } else {
            viewportWidthDp / (points.size - 1).coerceAtLeast(1).toFloat()
        }
        val totalChartWidthDp = (pointSpacingDp * (points.size - 1)).coerceAtLeast(viewportWidthDp)

        // Convert to pixels for label positioning
        val totalChartWidthPx = with(density) { totalChartWidthDp.dp.toPx() }
        val xStepPx = totalChartWidthPx / (points.size - 1).toFloat()

        Box(
            modifier = Modifier.horizontalScroll(scrollState)
        ) {
            Column(modifier = Modifier.width(totalChartWidthDp.dp)) {
                // ── Chart canvas ──────────────────────────────────────────
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeightDp.dp)
                        .pointerInput(points) {
                            detectTapGestures { tapOffset ->
                                val w = size.width
                                val xStep = w / (points.size - 1).toFloat()
                                val tappedIdx = (tapOffset.x / xStep).toInt().coerceIn(0, points.size - 1)
                                val closest = points.minByOrNull { p -> abs(p.index - tappedIdx.toFloat()) }
                                closest?.let { onTap(it) }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val xStep = w / (points.size - 1).toFloat()

                    fun xOf(i: Int) = i * xStep
                    fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

                    // Reference lines
                    referenceLines.forEach { (refY, refColor) ->
                        val ry = yOf(refY)
                        drawLine(color = refColor.copy(alpha = 0.35f), start = Offset(0f, ry), end = Offset(w, ry), strokeWidth = 1.5f)
                    }

                    // Fill path
                    val fillPath = Path().apply {
                        moveTo(xOf(0), h)
                        lineTo(xOf(0), yOf(points[0].value))
                        for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].value))
                        lineTo(xOf(points.size - 1), h)
                        close()
                    }
                    drawPath(fillPath, color = lineColor.copy(alpha = fillAlpha))

                    // Line path
                    val linePath = Path().apply {
                        moveTo(xOf(0), yOf(points[0].value))
                        for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].value))
                    }
                    drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

                    // Latest point dot
                    val lastX = xOf(points.size - 1)
                    val lastY = yOf(points.last().value)
                    drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
                    drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))

                    // Highlighted tapped point
                    tooltipPoint?.let { tp ->
                        val tpIdx = points.indexOfFirst { it.dateLabel == tp.dateLabel && it.value == tp.value }
                        if (tpIdx >= 0) {
                            val tx = xOf(tpIdx)
                            val ty = yOf(tp.value)
                            drawLine(color = lineColor.copy(alpha = 0.4f), start = Offset(tx, 0f), end = Offset(tx, h), strokeWidth = 1f)
                            drawCircle(color = lineColor, radius = 7f, center = Offset(tx, ty))
                            drawCircle(color = BackgroundDark, radius = 4f, center = Offset(tx, ty))
                        }
                    }

                    // Tick marks at label positions
                    labelIndices.forEach { idx ->
                        val tx = xOf(idx)
                        drawLine(color = TextDisabled.copy(alpha = 0.5f), start = Offset(tx, h - 4f), end = Offset(tx, h), strokeWidth = 1f)
                    }
                }

                // ── X-axis date labels (inside scrollable area, aligned with data) ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(xAxisHeightDp.dp)
                ) {
                    labelIndices.forEach { idx ->
                        val xPx = idx * xStepPx
                        val xDp = with(density) { xPx.toDp() }
                        val labelWidthEst = 36.dp
                        val dateStr = shortDate(points[idx].dateLabel)
                        // Clamp so labels don't overflow the left/right edges
                        val clampedX = (xDp - labelWidthEst / 2)
                            .coerceAtLeast(0.dp)
                            .coerceAtMost(totalChartWidthDp.dp - labelWidthEst)
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = TextDisabled,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .offset(x = clampedX)
                                .width(labelWidthEst)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Generic metric chart (with stats row + tooltip)
// ---------------------------------------------------------------------------

@Composable
fun HistoryMetricChart(
    label: String,
    points: List<HistoryChartPoint>,
    lineColor: Color,
    invertGood: Boolean = false,
    isLandscape: Boolean = false,
    valueFormat: String = "%.1f",
    visiblePoints: Int = 0
) {
    if (points.isEmpty()) { HistoryNoDataLabel(); return }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val yPad = ((max - min) * 0.1f).coerceAtLeast(0.1f)

    var tooltipPoint by remember { mutableStateOf<HistoryChartPoint?>(null) }

    val chartHeight = if (isLandscape) 160 else 100

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                "Latest: ${String.format(valueFormat, latest.value)}",
                style = MaterialTheme.typography.labelMedium,
                color = lineColor,
                fontWeight = FontWeight.Bold
            )
        }

        ScrollableLineChartCanvas(
            points = points,
            lineColor = lineColor,
            fillAlpha = 0.10f,
            yMin = min - yPad,
            yMax = max + yPad,
            tooltipPoint = tooltipPoint,
            onTap = { tooltipPoint = if (tooltipPoint == it) null else it },
            isLandscape = isLandscape,
            chartHeightDp = chartHeight,
            visiblePoints = visiblePoints,
            modifier = Modifier.fillMaxWidth()
        )

        tooltipPoint?.let { tp ->
            HistoryTooltipCard(
                label = label,
                value = String.format(valueFormat, tp.value),
                date = tp.dateLabel,
                color = lineColor
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Avg ${String.format(valueFormat, avg)}", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            Text("Min ${String.format(valueFormat, min)}  Max ${String.format(valueFormat, max)}", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
        }
    }
}

// ---------------------------------------------------------------------------
// Score chart (with zone reference lines + stat chips)
// ---------------------------------------------------------------------------

@Composable
fun HistoryScoreChart(
    points: List<HistoryChartPoint>,
    scoreColorFn: (Float) -> Color = { v -> when {
        v >= 80f -> ReadinessGreen
        v >= 60f -> ReadinessOrange
        else -> ReadinessRed
    }},
    referenceLines: List<Pair<Float, Color>> = listOf(80f to ReadinessGreen, 60f to ReadinessOrange),
    zoneLegendItems: List<Pair<String, Color>> = listOf(
        "≥80 Green" to ReadinessGreen,
        "60–79 Yellow" to ReadinessOrange,
        "<60 Red" to ReadinessRed
    ),
    tooltipLabel: String = "Score",
    isLandscape: Boolean = false,
    visiblePoints: Int = 0
) {
    if (points.isEmpty()) { HistoryNoDataLabel(); return }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }

    var tooltipPoint by remember { mutableStateOf<HistoryChartPoint?>(null) }

    val chartHeight = if (isLandscape) 200 else 140

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HistoryStatChip("Latest", latest.value.toInt().toString(), scoreColorFn(latest.value))
            HistoryStatChip("Avg", avg.toInt().toString(), scoreColorFn(avg))
            HistoryStatChip("Min", min.toInt().toString(), scoreColorFn(min))
            HistoryStatChip("Max", max.toInt().toString(), scoreColorFn(max))
        }

        ScrollableLineChartCanvas(
            points = points,
            lineColor = EcgCyan,
            fillAlpha = 0.15f,
            yMin = 0f,
            yMax = 100f,
            referenceLines = referenceLines,
            tooltipPoint = tooltipPoint,
            onTap = { tooltipPoint = if (tooltipPoint == it) null else it },
            isLandscape = isLandscape,
            chartHeightDp = chartHeight,
            visiblePoints = visiblePoints,
            modifier = Modifier.fillMaxWidth()
        )

        tooltipPoint?.let { tp ->
            HistoryTooltipCard(
                label = tooltipLabel,
                value = tp.value.toInt().toString(),
                date = tp.dateLabel,
                color = scoreColorFn(tp.value)
            )
        }

        if (zoneLegendItems.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                zoneLegendItems.forEach { (label, color) ->
                    ZoneLegendItem(label, color)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Zone legend item
// ---------------------------------------------------------------------------

@Composable
private fun ZoneLegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}
