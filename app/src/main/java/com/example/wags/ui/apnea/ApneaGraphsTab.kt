package com.example.wags.ui.apnea

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.ui.theme.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

// ── Tab content ─────────────────────────────────────────────────────────────────

/**
 * "Graphs" tab of the apnea History screen: time-period scoped line charts of
 * every free-hold metric (duration, PB progression, HR, SpO₂, contractions)
 * plus a training-volume bar chart, a summary strip and tap-to-inspect points.
 */
@Composable
fun ApneaGraphsTabContent(
    chartData: ApneaChartData,
    readingCount: Int,
    timePeriod: ApneaChartTimePeriod,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onTimePeriodChange: (ApneaChartTimePeriod) -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Time period selector ───────────────────────────────────────────
        ApneaPeriodSelector(
            selected = timePeriod,
            onSelect = onTimePeriodChange
        )

        // ── Step navigation (only for finite windows) ─────────────────────
        if (timePeriod != ApneaChartTimePeriod.ALL) {
            ApneaPeriodStepRow(
                timePeriod = timePeriod,
                canStepBack = canStepBack,
                canStepForward = canStepForward,
                onStepBack = onStepBack,
                onStepForward = onStepForward
            )
        }

        if (chartData.holdDuration.isEmpty()) {
            ApneaGraphEmptyCard(
                title = "No records in this window",
                body = "No records match the current filters in this window. Adjust the filters above (or step to another window) and your progress charts will appear here."
            )
            return@Column
        }

        // ── Summary strip ─────────────────────────────────────────────────
        ApneaSummaryStrip(points = chartData.holdDuration)
        Text(
            "$readingCount records total · showing ${timePeriod.label}",
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled
        )

        // ── 1. Hold Duration (with rolling-average overlay) ───────────────
        ApneaGraphSectionCard(
            title = "Hold Duration",
            subtitle = "Filtered records · dashed line = 5-hold rolling average"
        ) {
            ApneaMetricLineChart(
                label = "Duration",
                points = chartData.holdDuration,
                lineColor = EcgCyan,
                overlayPoints = rollingAverage(chartData.holdDuration),
                overlayLabel = "5-hold avg",
                isLandscape = isLandscape,
                isPrimary = true,
                formatValue = ::formatSecondsCompact
            )
        }

        // ── 2. Personal Best progression ──────────────────────────────────
        if (chartData.pbProgression.size >= 2) {
            ApneaGraphSectionCard(
                title = "Personal Best Progression",
                subtitle = "Running best hold — each step is a new record"
            ) {
                ApneaMetricLineChart(
                    label = "Best so far",
                    points = chartData.pbProgression,
                    lineColor = CoherenceWhite,
                    isLandscape = isLandscape,
                    isPrimary = true,
                    stepMode = true,
                    formatValue = ::formatSecondsCompact
                )
            }
        }

        // ── 3. Training volume ────────────────────────────────────────────
        if (chartData.volumePerBucket.isNotEmpty()) {
            ApneaGraphSectionCard(
                title = "Training Volume",
                subtitle = "All training sessions per ${chartData.volumeBucketLabel} · gaps show missed training"
            ) {
                ApneaVolumeBarChart(
                    points = chartData.volumePerBucket,
                    unitLabel = chartData.volumeBucketLabel
                )
            }
        }

        // ── 4. Heart Rate ─────────────────────────────────────────────────
        if (chartData.minHr.isNotEmpty() || chartData.maxHr.isNotEmpty() || chartData.hrDrop.isNotEmpty()) {
            ApneaGraphSectionCard(
                title = "Heart Rate",
                subtitle = "Free holds with an HR strap connected"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (chartData.minHr.isNotEmpty()) {
                        ApneaMetricLineChart(
                            label = "Min HR (bpm)",
                            points = chartData.minHr,
                            lineColor = ReadinessBlue,
                            invertGood = true,
                            isLandscape = isLandscape,
                            formatValue = { "%.0f".format(it) }
                        )
                    }
                    if (chartData.maxHr.isNotEmpty()) {
                        ApneaMetricLineChart(
                            label = "Max HR (bpm)",
                            points = chartData.maxHr,
                            lineColor = ReadinessOrange,
                            invertGood = true,
                            isLandscape = isLandscape,
                            formatValue = { "%.0f".format(it) }
                        )
                    }
                    if (chartData.hrDrop.isNotEmpty()) {
                        ApneaMetricLineChart(
                            label = "HR drop, max→min (bpm)",
                            points = chartData.hrDrop,
                            lineColor = ReadinessGreen,
                            isLandscape = isLandscape,
                            formatValue = { "%.0f".format(it) }
                        )
                    }
                }
            }
        }

        // ── 5. Oximetry ───────────────────────────────────────────────────
        if (chartData.lowestSpO2.isNotEmpty()) {
            ApneaGraphSectionCard(
                title = "Oximetry",
                subtitle = "Lowest SpO₂ reached per hold"
            ) {
                ApneaMetricLineChart(
                    label = "Lowest SpO₂ (%)",
                    points = chartData.lowestSpO2,
                    lineColor = ReadinessGreen,
                    isLandscape = isLandscape,
                    formatValue = { "%.0f".format(it) }
                )
            }
        }

        // ── 6. Contractions ───────────────────────────────────────────────
        if (chartData.firstContractionSec.isNotEmpty() || chartData.contractionEasePct.isNotEmpty()) {
            ApneaGraphSectionCard(
                title = "Contractions",
                subtitle = "How long the easy phase lasted before the first contraction"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (chartData.firstContractionSec.isNotEmpty()) {
                        ApneaMetricLineChart(
                            label = "Time to first contraction",
                            points = chartData.firstContractionSec,
                            lineColor = CoherencePink,
                            isLandscape = isLandscape,
                            formatValue = ::formatSecondsCompact
                        )
                    }
                    if (chartData.contractionEasePct.isNotEmpty()) {
                        ApneaMetricLineChart(
                            label = "Easy phase (% of hold)",
                            points = chartData.contractionEasePct,
                            lineColor = ReadinessGreen,
                            isLandscape = isLandscape,
                            formatValue = { "%.0f%%".format(it) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Summary strip ───────────────────────────────────────────────────────────────

/** Four at-a-glance tiles: hold count, average, best and recent trend. */
@Composable
private fun ApneaSummaryStrip(points: List<ApneaChartPoint>) {
    val values = points.map { it.value }
    val count = values.size
    val avg = if (values.isEmpty()) 0f else values.average().toFloat()
    val best = values.maxOrNull() ?: 0f

    // Trend: average of the last 10 holds vs the 10 before that
    // (falls back to half-vs-half for shorter histories).
    val trendText: String
    val trendUp: Boolean?
    if (count >= 4) {
        val split = if (count >= 20) count - 10 else count / 2
        val recent = values.subList(split, count).average().toFloat()
        val earlier = values.subList(0, split).average().toFloat()
        trendText = if (earlier > 0f) "%+.0f%%".format((recent / earlier - 1f) * 100f) else "—"
        trendUp = if (earlier > 0f) recent >= earlier else null
    } else {
        trendText = "—"
        trendUp = null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ApneaSummaryTile(
            label = "Holds",
            value = count.toString(),
            modifier = Modifier.weight(1f)
        )
        ApneaSummaryTile(
            label = "Average",
            value = formatSecondsCompact(avg),
            modifier = Modifier.weight(1f)
        )
        ApneaSummaryTile(
            label = "Best",
            value = formatSecondsCompact(best),
            modifier = Modifier.weight(1f)
        )
        ApneaSummaryTile(
            label = "Trend",
            value = trendText,
            valueColor = when (trendUp) {
                true -> ReadinessGreen
                false -> CoherencePink
                null -> TextSecondary
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ApneaSummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

// ── Time period selector ────────────────────────────────────────────────────────

@Composable
private fun ApneaPeriodSelector(
    selected: ApneaChartTimePeriod,
    onSelect: (ApneaChartTimePeriod) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ApneaChartTimePeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) EcgCyan.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(period) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Period step navigation row ──────────────────────────────────────────────────

@Composable
private fun ApneaPeriodStepRow(
    timePeriod: ApneaChartTimePeriod,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "‹",
            style = MaterialTheme.typography.headlineMedium,
            color = if (canStepBack) TextPrimary else TextDisabled,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canStepBack) { onStepBack() }
                .padding(horizontal = 14.dp, vertical = 2.dp)
        )
        Text(
            "Scroll ${timePeriod.label} windows",
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled
        )
        Text(
            "›",
            style = MaterialTheme.typography.headlineMedium,
            color = if (canStepForward) TextPrimary else TextDisabled,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canStepForward) { onStepForward() }
                .padding(horizontal = 14.dp, vertical = 2.dp)
        )
    }
}

// ── Section card & empty state ──────────────────────────────────────────────────

@Composable
private fun ApneaGraphSectionCard(
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

@Composable
private fun ApneaGraphEmptyCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Metric line chart (header + canvas + tooltip + footer) ──────────────────────

@Composable
private fun ApneaMetricLineChart(
    label: String,
    points: List<ApneaChartPoint>,
    lineColor: Color,
    invertGood: Boolean = false,
    isLandscape: Boolean = false,
    isPrimary: Boolean = false,
    stepMode: Boolean = false,
    overlayPoints: List<ApneaChartPoint> = emptyList(),
    overlayLabel: String? = null,
    formatValue: (Float) -> String
) {
    if (points.isEmpty()) {
        Text(
            "No data yet",
            style = MaterialTheme.typography.bodySmall,
            color = TextDisabled,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        return
    }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val minV = points.minOf { it.value }
    val maxV = points.maxOf { it.value }
    val yPad = ((maxV - minV) * 0.12f).coerceAtLeast(1f)

    var tooltipPoint by remember { mutableStateOf<ApneaChartPoint?>(null) }

    val chartHeight = if (isLandscape) {
        if (isPrimary) 210.dp else 150.dp
    } else {
        if (isPrimary) 150.dp else 110.dp
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Header: metric name + latest value with direction arrow vs previous
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            val prev = points.getOrNull(points.size - 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (prev != null && points.size >= 2) {
                    val delta = latest.value - prev.value
                    if (abs(delta) > 0.01f) {
                        val improved = if (invertGood) delta < 0f else delta > 0f
                        Text(
                            (if (delta > 0) "▲ " else "▼ "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (improved) ReadinessGreen else CoherencePink
                        )
                    }
                }
                Text(
                    formatValue(latest.value),
                    style = MaterialTheme.typography.titleMedium,
                    color = lineColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        ApneaLineChartCanvas(
            points = points,
            lineColor = lineColor,
            yMin = minV - yPad,
            yMax = maxV + yPad,
            overlayPoints = overlayPoints,
            overlayColor = TextSecondary,
            stepMode = stepMode,
            tooltipPoint = tooltipPoint,
            onTap = { tooltipPoint = if (tooltipPoint == it) null else it },
            formatValue = formatValue,
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        )

        // Tooltip for the selected point
        tooltipPoint?.let { tp ->
            ApneaTooltipCard(
                value = formatValue(tp.value),
                date = shortDate(tp.label),
                color = lineColor,
                vsAvg = if (avg > 0f) "%+.0f%% vs avg".format((tp.value / avg - 1f) * 100f) else null
            )
        }

        // Footer: window statistics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Avg ${formatValue(avg)}", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            Text(
                "Min ${formatValue(minV)} · Max ${formatValue(maxV)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }

        if (overlayPoints.isNotEmpty() && overlayLabel != null) {
            Text(
                "┄ $overlayLabel",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }
    }
}

// ── Tooltip card ────────────────────────────────────────────────────────────────

@Composable
private fun ApneaTooltipCard(value: String, date: String, color: Color, vsAvg: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(date, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            vsAvg?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

// ── Line chart canvas ───────────────────────────────────────────────────────────

/**
 * Canvas line chart with: nice y-axis ticks + gridlines, gradient fill under a
 * smoothed (Catmull-Rom) curve, animated reveal, optional dashed overlay
 * series, step mode for running-max charts, min/max markers and tap-to-inspect.
 */
@Composable
private fun ApneaLineChartCanvas(
    points: List<ApneaChartPoint>,
    lineColor: Color,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    overlayPoints: List<ApneaChartPoint> = emptyList(),
    overlayColor: Color = TextSecondary,
    stepMode: Boolean = false,
    tooltipPoint: ApneaChartPoint? = null,
    onTap: (ApneaChartPoint) -> Unit = {},
    formatValue: (Float) -> String
) {
    if (points.size < 2) {
        Canvas(modifier = modifier) {
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = TextSecondary)

    val ticks = remember(yMin, yMax) { niceTicks(yMin, yMax, maxTicks = 4) }
    val tickLayouts = remember(ticks, formatValue) {
        ticks.map { textMeasurer.measure(formatValue(it), tickStyle) }
    }
    val maxTickWidth = tickLayouts.maxOf { it.size.width }

    val xLabelIndices = remember(points) { spreadLabelIndices(points.size, maxLabels = 5) }
    val xLayouts = remember(points, xLabelIndices) {
        xLabelIndices.map { idx -> textMeasurer.measure(shortDate(points[idx].label), tickStyle) }
    }

    // Animated reveal when the series changes
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points.size, points.firstOrNull()?.label, points.lastOrNull()?.label) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }

    val pathMeasure = remember { PathMeasure() }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points) {
                    detectTapGestures { tapOffset ->
                        val gutter = maxTickWidth + 12.dp.toPx()
                        val plotWidth = size.width - gutter
                        if (plotWidth <= 0f) return@detectTapGestures
                        val xStep = plotWidth / (points.size - 1).toFloat()
                        val tappedIdx = ((tapOffset.x - gutter) / xStep).toInt().coerceIn(0, points.size - 1)
                        val closest = points.minByOrNull { p -> abs(p.dayIndex - tappedIdx.toFloat()) }
                        closest?.let { onTap(it) }
                    }
                }
        ) {
            val gutter = maxTickWidth + 12.dp.toPx()
            val xGutter = 6.dp.toPx()
            val plotLeft = gutter
            val plotRight = size.width - xGutter
            val plotTop = 4.dp.toPx()
            val plotBottom = size.height - 4.dp.toPx()
            val w = plotRight - plotLeft
            val h = plotBottom - plotTop
            if (w <= 0f || h <= 0f) return@Canvas

            val yRange = (yMax - yMin).coerceAtLeast(0.001f)
            fun xOf(i: Int) = plotLeft + i * (w / (points.size - 1).toFloat())
            fun yOf(v: Float) = plotBottom - ((v - yMin) / yRange * h).coerceIn(0f, h)

            // ── Gridlines + y-axis labels ─────────────────────────────────
            ticks.forEachIndexed { ti, tick ->
                val ty = yOf(tick)
                drawLine(
                    color = TextDisabled.copy(alpha = 0.18f),
                    start = Offset(plotLeft, ty),
                    end = Offset(plotRight, ty),
                    strokeWidth = 1f
                )
                val layout = tickLayouts[ti]
                drawText(
                    layout,
                    topLeft = Offset(
                        x = (gutter - 6.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                        y = (ty - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }

            // ── Build the line path (smoothed or stepped) ─────────────────
            val offsets = points.mapIndexed { i, p -> Offset(xOf(i), yOf(p.value)) }
            val linePath = buildLinePath(offsets, stepMode)

            // ── Gradient fill under the revealed portion ──────────────────
            pathMeasure.setPath(linePath, false)
            val revealLen = pathMeasure.length * reveal.value
            val segment = Path()
            pathMeasure.getSegment(0f, revealLen, segment, true)
            val segRight = if (reveal.value >= 0.999f) offsets.last().x else segment.getBounds().right

            val fillPath = Path().apply {
                addPath(segment)
                lineTo(segRight, plotBottom)
                lineTo(offsets.first().x, plotBottom)
                close()
            }
            drawPath(
                fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.26f), lineColor.copy(alpha = 0.02f)),
                    startY = plotTop,
                    endY = plotBottom
                )
            )

            // ── Dashed overlay series (rolling average) ───────────────────
            if (overlayPoints.size >= 2) {
                val overlayOffsets = overlayPoints.mapIndexed { i, p ->
                    Offset(xOf(min(i, points.size - 1)), yOf(p.value))
                }
                val overlayPath = buildLinePath(overlayOffsets, stepMode = false)
                pathMeasure.setPath(overlayPath, false)
                // Simple dash effect: draw the overlay as short segments
                val dashLen = 10f
                val gapLen = 7f
                var dist = 0f
                while (dist < pathMeasure.length * reveal.value) {
                    val end = min(dist + dashLen, pathMeasure.length * reveal.value)
                    val dash = Path()
                    pathMeasure.getSegment(dist, end, dash, true)
                    drawPath(dash, overlayColor.copy(alpha = 0.8f), style = Stroke(width = 1.8f))
                    dist += dashLen + gapLen
                }
            }

            // ── Main line ─────────────────────────────────────────────────
            drawPath(segment, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // ── Min / max markers ─────────────────────────────────────────
            if (!stepMode) {
                val minIdx = points.indices.minByOrNull { points[it].value } ?: 0
                val maxIdx = points.indices.maxByOrNull { points[it].value } ?: 0
                listOf(minIdx, maxIdx).forEach { idx ->
                    val c = Offset(xOf(idx), yOf(points[idx].value))
                    drawCircle(color = lineColor.copy(alpha = 0.25f), radius = 7f, center = c)
                    drawCircle(color = lineColor, radius = 3f, center = c)
                }
            }

            // ── Latest point: halo + core ─────────────────────────────────
            val lastC = Offset(xOf(points.size - 1), yOf(points.last().value))
            drawCircle(color = lineColor.copy(alpha = 0.25f), radius = 10f, center = lastC)
            drawCircle(color = lineColor, radius = 5f, center = lastC)
            drawCircle(color = BackgroundDark, radius = 2.5f, center = lastC)

            // ── Selected (tapped) point ───────────────────────────────────
            tooltipPoint?.let { tp ->
                val tpIdx = points.indexOfFirst { it.label == tp.label && it.value == tp.value }
                if (tpIdx >= 0) {
                    val tx = xOf(tpIdx)
                    val ty = yOf(tp.value)
                    drawLine(
                        color = lineColor.copy(alpha = 0.45f),
                        start = Offset(tx, plotTop),
                        end = Offset(tx, plotBottom),
                        strokeWidth = 1.2f
                    )
                    drawCircle(color = lineColor.copy(alpha = 0.25f), radius = 11f, center = Offset(tx, ty))
                    drawCircle(color = lineColor, radius = 6f, center = Offset(tx, ty))
                    drawCircle(color = BackgroundDark, radius = 3f, center = Offset(tx, ty))
                }
            }
        }

        // ── X-axis date labels ────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val gutter = maxTickWidth + 12.dp.toPx()
            val plotLeft = gutter
            val plotRight = size.width - 6.dp.toPx()
            val w = plotRight - plotLeft
            if (w <= 0f) return@Canvas
            xLabelIndices.forEachIndexed { li, idx ->
                val fraction = idx.toFloat() / (points.size - 1).toFloat()
                val layout = xLayouts[li]
                val cx = plotLeft + fraction * w
                val x = (cx - layout.size.width / 2f)
                    .coerceIn(plotLeft, (plotRight - layout.size.width).coerceAtLeast(plotLeft))
                drawText(layout, topLeft = Offset(x, 0f))
            }
        }
    }
}

// ── Volume bar chart ────────────────────────────────────────────────────────────

/** Bar chart of holds per day/week/month with peak highlighting and tap-to-inspect. */
@Composable
private fun ApneaVolumeBarChart(
    points: List<ApneaChartPoint>,
    unitLabel: String
) {
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = TextSecondary)

    val maxCount = points.maxOf { it.value }.coerceAtLeast(1f)
    val peakIdx = points.indices.maxByOrNull { points[it].value } ?: 0
    val total = points.sumOf { it.value.toDouble().toInt() }

    var selectedIdx by remember { mutableStateOf<Int?>(null) }

    // Integer-only ticks: counts are whole numbers, so fractional gridlines
    // (e.g. "0.5 hold") would only add noise.
    val yTicks = remember(maxCount) {
        val top = ceil(maxCount).toInt().coerceAtLeast(2)
        listOf(0f, (top / 2).toFloat(), top.toFloat()).distinct()
    }
    val yLayouts = remember(yTicks) { yTicks.map { textMeasurer.measure("%.0f".format(it), tickStyle) } }
    val maxTickWidth = yLayouts.maxOf { it.size.width }

    val xLabelIndices = remember(points) { spreadLabelIndices(points.size, maxLabels = 5) }
    val xLayouts = remember(points, xLabelIndices) {
        xLabelIndices.map { idx -> textMeasurer.measure(shortDate(points[idx].label), tickStyle) }
    }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points.size, points.firstOrNull()?.label, points.lastOrNull()?.label) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$total holds · peak ${maxCount.toInt()}/$unitLabel",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                "tap a bar to inspect",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(points) {
                        detectTapGestures { tapOffset ->
                            val gutter = maxTickWidth + 12.dp.toPx()
                            val plotWidth = size.width - gutter - 6.dp.toPx()
                            if (plotWidth <= 0f || points.isEmpty()) return@detectTapGestures
                            val slot = plotWidth / points.size
                            val idx = ((tapOffset.x - gutter) / slot).toInt().coerceIn(0, points.size - 1)
                            selectedIdx = if (selectedIdx == idx) null else idx
                        }
                    }
            ) {
                val gutter = maxTickWidth + 12.dp.toPx()
                val plotLeft = gutter
                val plotRight = size.width - 6.dp.toPx()
                val plotTop = 6.dp.toPx()
                val plotBottom = size.height - 2.dp.toPx()
                val w = plotRight - plotLeft
                val h = plotBottom - plotTop
                if (w <= 0f || h <= 0f) return@Canvas

                val slot = w / points.size
                val barW = (slot * 0.62f).coerceAtLeast(3f)

                // Gridlines + y labels
                yTicks.forEachIndexed { ti, tick ->
                    val ty = plotBottom - (tick / maxCount) * h
                    drawLine(
                        color = TextDisabled.copy(alpha = 0.18f),
                        start = Offset(plotLeft, ty),
                        end = Offset(plotRight, ty),
                        strokeWidth = 1f
                    )
                    val layout = yLayouts[ti]
                    drawText(
                        layout,
                        topLeft = Offset(
                            x = (gutter - 6.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                            y = (ty - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                        )
                    )
                }

                // Bars
                points.forEachIndexed { i, p ->
                    val fraction = (p.value / maxCount) * reveal.value
                    val barH = (fraction * h).coerceIn(0f, h)
                    val x = plotLeft + i * slot + (slot - barW) / 2f
                    val isPeak = i == peakIdx
                    val isSelected = i == selectedIdx
                    drawRoundRect(
                        color = when {
                            isSelected -> TextPrimary
                            isPeak -> EcgCyan
                            else -> EcgCyanDim.copy(alpha = 0.75f)
                        },
                        topLeft = Offset(x, plotBottom - barH),
                        size = Size(barW, barH.coerceAtLeast(if (p.value > 0f) 3f else 0f)),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }

                // Value label above the peak bar
                val peakLayout = textMeasurer.measure(
                    "${points[peakIdx].value.toInt()}",
                    tickStyle.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
                )
                val peakX = plotLeft + peakIdx * slot + slot / 2f
                drawText(
                    peakLayout,
                    topLeft = Offset(
                        x = (peakX - peakLayout.size.width / 2f)
                            .coerceIn(plotLeft, (plotRight - peakLayout.size.width).coerceAtLeast(plotLeft)),
                        y = (plotBottom - (points[peakIdx].value / maxCount) * h * reveal.value -
                            peakLayout.size.height - 3.dp.toPx()).coerceAtLeast(0f)
                    )
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            ) {
                val gutter = maxTickWidth + 12.dp.toPx()
                val plotLeft = gutter
                val plotRight = size.width - 6.dp.toPx()
                val w = plotRight - plotLeft
                if (w <= 0f) return@Canvas
                xLabelIndices.forEachIndexed { li, idx ->
                    val fraction = (idx + 0.5f) / points.size
                    val layout = xLayouts[li]
                    val cx = plotLeft + fraction * w
                    val x = (cx - layout.size.width / 2f)
                        .coerceIn(plotLeft, (plotRight - layout.size.width).coerceAtLeast(plotLeft))
                    drawText(layout, topLeft = Offset(x, 0f))
                }
            }
        }

        // Tooltip for the selected bucket
        selectedIdx?.let { idx ->
            val p = points.getOrNull(idx) ?: return@let
            ApneaTooltipCard(
                value = "${p.value.toInt()} hold${if (p.value == 1f) "" else "s"}",
                date = "${unitLabel.replaceFirstChar { it.uppercase() }} of ${shortDate(p.label)}",
                color = TextPrimary,
                vsAvg = if (total > 0) "%+.0f%% vs avg".format((p.value / (total.toFloat() / points.size) - 1f) * 100f) else null
            )
        }
    }
}

// ── Path & math helpers ─────────────────────────────────────────────────────────

/** Catmull-Rom-smoothed path through [offsets], or a stepped path when [stepMode]. */
private fun buildLinePath(offsets: List<Offset>, stepMode: Boolean): Path {
    val path = Path()
    path.moveTo(offsets.first().x, offsets.first().y)
    when {
        stepMode -> {
            for (i in 1 until offsets.size) {
                path.lineTo(offsets[i].x, offsets[i - 1].y)
                path.lineTo(offsets[i].x, offsets[i].y)
            }
        }
        offsets.size < 3 -> {
            for (i in 1 until offsets.size) path.lineTo(offsets[i].x, offsets[i].y)
        }
        else -> {
            for (i in 0 until offsets.size - 1) {
                val p0 = offsets[maxOf(0, i - 1)]
                val p1 = offsets[i]
                val p2 = offsets[i + 1]
                val p3 = offsets[minOf(offsets.size - 1, i + 2)]
                val c1x = p1.x + (p2.x - p0.x) / 6f
                val c1y = p1.y + (p2.y - p0.y) / 6f
                val c2x = p2.x - (p3.x - p1.x) / 6f
                val c2y = p2.y - (p3.y - p1.y) / 6f
                path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
            }
        }
    }
    return path
}

/**
 * "Nice" axis ticks covering [min, max]: steps of 1/2/5 × 10ⁿ so gridline
 * labels never look arbitrary (e.g. 37.4, 75.8, …).
 */
private fun niceTicks(min: Float, max: Float, maxTicks: Int): List<Float> {
    val range = (max - min).coerceAtLeast(0.001f)
    val rawStep = range / (maxTicks - 1).coerceAtLeast(1)
    val mag = pow10(floor(log10(rawStep.toDouble())).toFloat())
    val norm = rawStep / mag
    val step = when {
        norm <= 1f -> 1f
        norm <= 2f -> 2f
        norm <= 5f -> 5f
        else -> 10f
    } * mag
    val start = ceil(min / step) * step
    val ticks = mutableListOf<Float>()
    var t = start
    while (t <= max + step * 0.001f) {
        ticks.add(t)
        t += step
    }
    return if (ticks.isEmpty()) listOf(min, max) else ticks
}

private fun pow10(exp: Float): Float {
    var result = 1f
    var e = exp
    val positive = e >= 0
    if (!positive) e = -e
    repeat(e.toInt()) { result *= 10f }
    return if (positive) result else 1f / result
}

/** Up to [maxLabels] evenly-spaced indices, always including first and last. */
private fun spreadLabelIndices(size: Int, maxLabels: Int): List<Int> = when {
    size <= maxLabels -> (0 until size).toList()
    else -> {
        val step = (size - 1).toFloat() / (maxLabels - 1).toFloat()
        (0 until maxLabels).map { i -> (i * step).toInt().coerceIn(0, size - 1) }
    }
}

/** Rolling average of [window] points, aligned to the input x positions. */
private fun rollingAverage(points: List<ApneaChartPoint>, window: Int = 5): List<ApneaChartPoint> {
    if (points.size < 3) return emptyList()
    val w = window.coerceAtMost(points.size)
    return points.indices.map { i ->
        val from = maxOf(0, i - w + 1)
        val slice = points.subList(from, i + 1)
        ApneaChartPoint(points[i].dayIndex, slice.map { it.value }.average().toFloat(), points[i].label)
    }
}

/** Formats seconds as m:ss (or h:mm:ss) for chart labels and tooltips. */
internal fun formatSecondsCompact(seconds: Float): String =
    formatHoldDuration((seconds * 1000).toLong())

/** "yyyy-MM-dd" → "Mar 3" (falls back to the input when unparsable). */
internal fun shortDate(isoDate: String): String = try {
    val parts = isoDate.split("-")
    val month = java.time.Month.of(parts[1].toInt()).name.take(3).lowercase()
        .replaceFirstChar { it.uppercase() }
    "$month ${parts[2].trimStart('0')}"
} catch (_: Exception) {
    isoDate
}
