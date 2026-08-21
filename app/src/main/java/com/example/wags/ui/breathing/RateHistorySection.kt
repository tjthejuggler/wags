package com.example.wags.ui.breathing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.wags.domain.usecase.breathing.RateConsistencyStat
import com.example.wags.domain.usecase.breathing.RateHistoryResult
import com.example.wags.domain.usecase.breathing.RateHistorySnapshot
import com.example.wags.ui.theme.EcgCyan
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.TextPrimary
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.text.TextMeasurer
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

// ── Palette (matches RateRecommendationScreen) ─────────────────────────────────
private val RecBone     = Color(0xFFE8E8E8)
private val RecSilver   = Color(0xFFB0B0B0)
private val RecAsh      = Color(0xFF707070)
private val RecGold     = Color(0xFFD4AF37)
private val ChartBgDark = Color(0xFF0A0A0A)
private val ChartBgMid  = Color(0xFF1C1C1C)
private val GridColor   = Color(0xFF2A2A2A)
private val TooltipBg   = Color(0xFF2A2A2A)

private val monthFmt = DateTimeFormatter.ofPattern("MMM")
private val monthYearFmt = DateTimeFormatter.ofPattern("MMM yy")
private val dayFmt = DateTimeFormatter.ofPattern("MMM d")
private val tooltipDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * "Best rate over time" section for the Rate Recommendation (Why?) screen.
 *
 * Replays the 60-day recommendation engine across the user's full history at
 * weekly checkpoints and shows:
 *  1. A timeline chart — gold step line = #1 rated rate at each checkpoint,
 *     cyan band = spread of the top-3 rates. A flat, narrow chart means the
 *     winner keeps re-earning its crown; drift means the window moved on.
 *     Every checkpoint dot is tappable for full details of that moment.
 *  2. A staying-power leaderboard — which rates spend the most time at #1
 *     and inside the top-3, so consistently strong rates stand out even when
 *     they are not the current winner.
 */
@Composable
fun RateHistorySection(
    history: RateHistoryResult,
    modifier: Modifier = Modifier
) {
    val stepWord = if (history.stepDays == 7) "weekly" else "every ${history.stepDays} days"
    val championRate = history.consistency.firstOrNull()?.rateBpm

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "BEST RATE OVER TIME",
                style = MaterialTheme.typography.labelMedium,
                color = RecSilver,
                letterSpacing = 2.sp
            )
            Text(
                text = "Your full history replayed $stepWord: each point of the gold line " +
                        "re-runs the recommendation using only the 60 days before it. " +
                        "The band shows the top-3 spread — rates have to keep proving " +
                        "themselves to stay inside it. Tap any point for details.",
                style = MaterialTheme.typography.bodySmall,
                color = RecBone,
                lineHeight = 18.sp
            )

            TimelineChart(snapshots = history.snapshots)

            // Legend
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LegendSwatch(color = RecGold, label = "#1 rate")
                LegendSwatch(color = EcgCyan.copy(alpha = 0.25f), label = "top-3 range")
            }

            if (history.consistency.isNotEmpty()) {
                StayingPowerLeaderboard(
                    stats = history.consistency.take(5),
                    championRate = championRate
                )
            }
        }
    }
}

// ── Timeline chart geometry (shared by drawing and tap hit-testing) ─────────────

/** A tappable checkpoint dot: its canvas center plus the snapshot it represents. */
private data class TimelinePoint(
    val center: Offset,
    val snapshot: RateHistorySnapshot
)

/**
 * Precomputed layout for the timeline chart. Derived once per size/data change
 * so the draw pass and the tap gesture handler always agree on positions.
 */
private data class TimelineGeometry(
    val w: Float,
    val h: Float,
    val padLeft: Float,
    val padTop: Float,
    val padRight: Float,
    val padBottom: Float,
    val chartW: Float,
    val chartH: Float,
    val yMin: Float,
    val yMax: Float,
    val startTs: Long,
    val spanTs: Float,
    val gridTexts: List<TextLayoutResult>,
    val points: List<TimelinePoint>
)

private fun computeTimelineGeometry(
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    canvasSize: Size,
    snapshots: List<RateHistorySnapshot>
): TimelineGeometry {
    fun empty() = TimelineGeometry(
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0L, 1f, emptyList(), emptyList()
    )
    if (canvasSize == Size.Zero || snapshots.isEmpty()) return empty()

    val w = canvasSize.width
    val h = canvasSize.height
    val startTs = snapshots.first().timestamp
    val endTs = maxOf(snapshots.last().timestamp, startTs + 1)
    val spanTs = (endTs - startTs).toFloat()

    val allRates = snapshots.flatMap { s -> s.top3.map { it.rateBpm } }.ifEmpty {
        snapshots.mapNotNull { it.winnerBpm }
    }
    if (allRates.isEmpty()) return empty()

    var yMin = allRates.min() - 0.15f
    var yMax = allRates.max() + 0.15f
    if (yMax - yMin < 0.5f) { yMin -= 0.25f; yMax += 0.25f }

    // Pads sized from measured axis labels so nothing clips
    val gridTexts = (0..3).map { i ->
        textMeasurer.measure(
            "%.2f".format(yMax - (yMax - yMin) * i / 3f),
            style = labelStyle.copy(color = RecAsh)
        )
    }
    val axisLabelHeight = gridTexts.maxOf { it.size.height }
    val padLeft = (gridTexts.maxOf { it.size.width } + 10f).coerceAtLeast(40f)
    val padRight = 14f
    val padTop = (axisLabelHeight / 2f + 6f).coerceAtLeast(14f)
    val padBottom = (axisLabelHeight + 20f).coerceAtLeast(30f)
    val chartW = w - padLeft - padRight
    val chartH = h - padTop - padBottom

    fun xOf(ts: Long) = padLeft + (ts - startTs) / spanTs * chartW
    fun yOf(bpm: Float) = padTop + (1f - (bpm - yMin) / (yMax - yMin)) * chartH

    val points = snapshots.filter { it.winnerBpm != null }.map {
        TimelinePoint(Offset(xOf(it.timestamp), yOf(it.winnerBpm!!)), it)
    }

    return TimelineGeometry(
        w, h, padLeft, padTop, padRight, padBottom, chartW, chartH,
        yMin, yMax, startTs, spanTs, gridTexts, points
    )
}

// ── Timeline chart ──────────────────────────────────────────────────────────────

@Composable
private fun TimelineChart(snapshots: List<RateHistorySnapshot>) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
    val winnerStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val zone = ZoneId.systemDefault()
    val density = LocalDensity.current

    var selectedIdx by remember { mutableIntStateOf(-1) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val geom = remember(canvasSize, snapshots, labelStyle) {
        computeTimelineGeometry(textMeasurer, labelStyle, canvasSize, snapshots)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(8.dp))
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(geom) {
                    detectTapGestures { offset ->
                        val tapped = geom.points.indexOfFirst { point ->
                            (point.center - offset).getDistance() < 36f
                        }
                        selectedIdx = if (tapped == selectedIdx) -1 else tapped
                    }
                }
        ) {
            val w = geom.w
            val h = geom.h
            if (w <= 0f || snapshots.isEmpty()) return@Canvas

            val padLeft = geom.padLeft
            val padRight = geom.padRight
            val padTop = geom.padTop
            val padBottom = geom.padBottom
            val chartW = geom.chartW
            val chartH = geom.chartH
            val yMin = geom.yMin
            val yMax = geom.yMax
            val startTs = geom.startTs
            val spanTs = geom.spanTs

            fun xOf(ts: Long) = padLeft + (ts - startTs) / spanTs * chartW
            fun yOf(bpm: Float) = padTop + (1f - (bpm - yMin) / (yMax - yMin)) * chartH

            // Background
            drawRect(Brush.verticalGradient(listOf(ChartBgDark, ChartBgMid)), size = size)

            // ── Grid + Y labels ─────────────────────────────────────────────
            for (i in 0..3) {
                val y = padTop + chartH * i / 3f
                drawLine(GridColor, Offset(padLeft, y), Offset(w - padRight, y), strokeWidth = 1f)
                val gridText = geom.gridTexts[i]
                drawText(
                    gridText,
                    color = RecAsh,
                    topLeft = Offset(padLeft - gridText.size.width - 4f, y - gridText.size.height / 2f)
                )
            }

            // ── X axis: month ticks ─────────────────────────────────────────
            data class Tick(val x: Float, val label: String)
            val ticks = mutableListOf<Tick>()
            var monthCursor = Instant.ofEpochMilli(startTs).atZone(zone).toLocalDate()
                .plusMonths(1).withDayOfMonth(1)
            while (monthCursor.atStartOfDay(zone).toInstant().toEpochMilli() < geom.startTs + spanTs) {
                val ms = monthCursor.atStartOfDay(zone).toInstant().toEpochMilli()
                val label = if (monthCursor.monthValue == 1)
                    monthCursor.format(monthYearFmt) else monthCursor.format(monthFmt)
                ticks += Tick(xOf(ms), label)
                monthCursor = monthCursor.plusMonths(1)
            }
            // Fallback for spans shorter than a month: label both edges
            if (ticks.isEmpty()) {
                ticks += Tick(padLeft + 2f, Instant.ofEpochMilli(startTs).atZone(zone).format(dayFmt))
                ticks += Tick(w - padRight - 2f, Instant.ofEpochMilli(geom.startTs + spanTs.toLong()).atZone(zone).format(dayFmt))
            }
            // Thin out labels when months get dense
            val approxLabelWidth = 30f
            val stride = ceil(approxLabelWidth * ticks.size / chartW).toInt().coerceAtLeast(1)
            ticks.forEachIndexed { i, tick ->
                drawLine(GridColor, Offset(tick.x, padTop + chartH), Offset(tick.x, padTop + chartH + 5f), strokeWidth = 1f)
                if (i % stride == 0) {
                    val t = textMeasurer.measure(tick.label, style = labelStyle.copy(color = RecAsh))
                    drawText(
                        t,
                        color = RecAsh,
                        topLeft = Offset(
                            (tick.x - t.size.width / 2f).coerceIn(padLeft, w - padRight - t.size.width),
                            padTop + chartH + 8f
                        )
                    )
                }
            }

            // ── Contiguous runs of checkpoints with data ────────────────────
            val runs = mutableListOf<List<RateHistorySnapshot>>()
            var current = mutableListOf<RateHistorySnapshot>()
            snapshots.forEach { s ->
                if (s.winnerBpm != null) current += s else if (current.isNotEmpty()) {
                    runs += current; current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) runs += current

            // Top-3 band (cyan)
            runs.forEach { run ->
                if (run.size == 1) return@forEach
                val band = Path()
                run.forEachIndexed { i, s ->
                    val x = xOf(s.timestamp)
                    val yTop = yOf(s.top3.maxOf { it.rateBpm })
                    if (i == 0) band.moveTo(x, yTop) else band.lineTo(x, yTop)
                }
                run.asReversed().forEach { s ->
                    band.lineTo(xOf(s.timestamp), yOf(s.top3.minOf { it.rateBpm }))
                }
                band.close()
                drawPath(band, color = EcgCyan.copy(alpha = 0.16f))
            }

            // Winner step line (gold)
            runs.forEach { run ->
                val line = Path()
                var prevY: Float? = null
                run.forEach { s ->
                    val x = xOf(s.timestamp)
                    val y = yOf(s.winnerBpm!!)
                    val py = prevY
                    if (py == null) {
                        line.moveTo(x, y)
                    } else {
                        // Step: hold the previous rate until this checkpoint, then move
                        line.lineTo(x, py)
                        line.lineTo(x, y)
                    }
                    prevY = y
                }
                drawPath(
                    line,
                    color = RecGold,
                    style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Checkpoint dots (tappable) with selection highlight
            geom.points.forEachIndexed { i, point ->
                if (i == selectedIdx) {
                    drawCircle(Color.White.copy(alpha = 0.2f), radius = 12f, center = point.center)
                    drawCircle(RecGold, radius = 6f, center = point.center)
                } else {
                    drawCircle(RecGold.copy(alpha = 0.85f), radius = 4f, center = point.center)
                }
            }

            // ── Current winner annotation ───────────────────────────────────
            snapshots.lastOrNull { it.winnerBpm != null }?.let { last ->
                val x = xOf(last.timestamp)
                val y = yOf(last.winnerBpm!!)
                drawCircle(Color.White, radius = 7f, center = Offset(x, y))
                drawCircle(RecGold, radius = 5f, center = Offset(x, y))
                val label = "%.2f".format(last.winnerBpm!!)
                val t = textMeasurer.measure(label, style = winnerStyle.copy(color = RecGold))
                val lx = (x - t.size.width - 10f).coerceAtLeast(padLeft)
                val ly = (y - t.size.height - 8f).coerceAtLeast(padTop)
                drawText(t, color = RecGold, topLeft = Offset(lx, ly))
            }
        }

        // ── Tap tooltip ────────────────────────────────────────────────────
        if (selectedIdx in geom.points.indices) {
            val point = geom.points[selectedIdx]
            val snapshot = point.snapshot
            val tooltipWidthPx: Float = with(density) { 176.dp.toPx() }
            val tooltipHeightPx: Float = with(density) { 118.dp.toPx() }
            val tooltipX: Int = (point.center.x - tooltipWidthPx / 2f)
                .toInt()
                .coerceIn(0, (geom.w - tooltipWidthPx).toInt().coerceAtLeast(0))
            val tooltipY: Int = if (point.center.y > tooltipHeightPx + 8f) {
                (point.center.y - tooltipHeightPx - 8f).toInt()
            } else {
                (point.center.y + 12f).toInt()
            }

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(tooltipX, tooltipY),
                properties = PopupProperties(clippingEnabled = false)
            ) {
                Card(
                    modifier = Modifier.width(176.dp),
                    colors = CardDefaults.cardColors(containerColor = TooltipBg),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = Instant.ofEpochMilli(snapshot.timestamp)
                                .atZone(zone).format(tooltipDateFmt),
                            style = MaterialTheme.typography.labelSmall,
                            color = RecAsh
                        )
                        if (snapshot.winnerBpm != null) {
                            Text(
                                text = "#1  %.2f BPM".format(snapshot.winnerBpm),
                                style = MaterialTheme.typography.titleMedium,
                                color = RecGold,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "score %.2f  ·  conf %.0f%%".format(
                                    snapshot.winnerScore,
                                    snapshot.winnerConfidence * 100f
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = RecBone
                            )
                        } else {
                            Text(
                                text = "No data in window",
                                style = MaterialTheme.typography.bodySmall,
                                color = RecSilver
                            )
                        }
                        if (snapshot.top3.isNotEmpty()) {
                            Text(
                                text = "top-3: " + snapshot.top3.joinToString(" / ") { "%.2f".format(it.rateBpm) },
                                style = MaterialTheme.typography.bodySmall,
                                color = RecSilver
                            )
                        }
                        Text(
                            text = "%d data pts in 60-day window".format(snapshot.dataPointCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = RecAsh
                        )
                    }
                }
            }
        }
    }
}

// ── Legend ──────────────────────────────────────────────────────────────────────

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = RecAsh
        )
    }
}

// ── Staying-power leaderboard ───────────────────────────────────────────────────

@Composable
private fun StayingPowerLeaderboard(
    stats: List<RateConsistencyStat>,
    championRate: Float?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "STAYING POWER",
            style = MaterialTheme.typography.labelMedium,
            color = RecSilver,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Share of checkpoints spent at #1 (gold) and inside the top-3 (blue). " +
                    "Long bars are rates that keep re-earning their spot.",
            style = MaterialTheme.typography.bodySmall,
            color = RecAsh,
            lineHeight = 16.sp
        )

        stats.forEach { stat ->
            val isChampion = stat.rateBpm == championRate
            val total = stat.totalCheckpoints.coerceAtLeast(1)
            val goldFraction = (stat.checkpointsAtNumber1.toFloat() / total).coerceIn(0f, 1f)
            val cyanFraction = ((stat.checkpointsInTop3 - stat.checkpointsAtNumber1).toFloat() / total)
                .coerceIn(0f, 1f - goldFraction)
            val top3Pct = (stat.checkpointsInTop3.toFloat() / total * 100f).toInt()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = (if (isChampion) "★ " else "") + "%.2f".format(stat.rateBpm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isChampion) RecGold else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(76.dp)
                )

                // Stacked share bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF2A2A2A))
                ) {
                    Row(modifier = Modifier.height(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(goldFraction)
                                .height(10.dp)
                                .background(RecGold)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    if (cyanFraction > 0f && goldFraction < 1f)
                                        (cyanFraction / (1f - goldFraction)).coerceIn(0f, 1f)
                                    else 0f
                                )
                                .height(10.dp)
                                .background(EcgCyan.copy(alpha = 0.55f))
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "#1 ×${stat.checkpointsAtNumber1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isChampion) RecGold else RecSilver,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "top-3 $top3Pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = RecAsh
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
    }
}
