package com.example.wags.ui.apnea

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.ui.theme.*
import org.json.JSONObject

// ── Data class for parsed Min Breath hold info ──────────────────────────────

data class MinBreathHoldInfo(
    val holdNumber: Int,
    val durationMs: Long,
    val contractionMs: Long?,       // elapsed ms within the hold
    val breathDurationMs: Long?     // breathing time after this hold (null if last hold or old data)
)

// ── Parse Min Breath tableParamsJson ─────────────────────────────────────────

fun parseMinBreathParams(json: String): MinBreathParsedParams? {
    return try {
        val root = JSONObject(json)
        val sessionDurationSec = root.optInt("sessionDurationSec", 0)
        val totalHoldTimeMs = root.optLong("totalHoldTimeMs", 0L)
        val totalBreathTimeMs = root.optLong("totalBreathTimeMs", 0L)
        val holdPct = root.optDouble("holdPct", 0.0)
        val holdsArray = root.optJSONArray("holds")
        val holds = mutableListOf<MinBreathHoldInfo>()
        if (holdsArray != null) {
            for (i in 0 until holdsArray.length()) {
                val obj = holdsArray.getJSONObject(i)
                val holdNum = obj.optInt("hold", i + 1)
                val durMs = obj.optLong("durationMs", 0L)
                val cMs = if (obj.isNull("contractionMs")) null
                          else obj.optLong("contractionMs", -1L).takeIf { it >= 0 }
                val bMs = if (obj.has("breathDurationMs") && !obj.isNull("breathDurationMs"))
                              obj.optLong("breathDurationMs", -1L).takeIf { it >= 0 }
                          else null
                holds.add(MinBreathHoldInfo(holdNum, durMs, cMs, bMs))
            }
        }
        MinBreathParsedParams(sessionDurationSec, totalHoldTimeMs, totalBreathTimeMs, holdPct, holds)
    } catch (_: Exception) {
        null
    }
}

data class MinBreathParsedParams(
    val sessionDurationSec: Int,
    val totalHoldTimeMs: Long,
    val totalBreathTimeMs: Long,
    val holdPct: Double,
    val holds: List<MinBreathHoldInfo>
)

// ── Min Breath Session Details composable ────────────────────────────────────

@Composable
fun MinBreathSessionDetails(
    tableSession: ApneaSessionEntity,
    record: ApneaRecordEntity
) {
    val parsed = remember(tableSession.tableParamsJson) {
        parseMinBreathParams(tableSession.tableParamsJson)
    }

    if (parsed == null) {
        Text("Could not parse session data.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        return
    }

    val totalActiveMs = parsed.totalHoldTimeMs + parsed.totalBreathTimeMs
    val holdPct = if (totalActiveMs > 0) parsed.totalHoldTimeMs.toDouble() / totalActiveMs * 100.0 else 0.0
    val breathPct = if (totalActiveMs > 0) parsed.totalBreathTimeMs.toDouble() / totalActiveMs * 100.0 else 0.0
    val longestMs = parsed.holds.maxOfOrNull { it.durationMs } ?: 0L
    val avgMs = if (parsed.holds.isNotEmpty()) parsed.holds.sumOf { it.durationMs } / parsed.holds.size else 0L

    val durMin = parsed.sessionDurationSec / 60
    val durSec = (parsed.sessionDurationSec % 60).toString().padStart(2, '0')

    MinBreathDetailRow("Session Duration", "$durMin:$durSec")
    MinBreathDetailRow(
        "Total Hold Time",
        "${formatMinBreathMs(parsed.totalHoldTimeMs)} (${"%.1f".format(holdPct)}%)",
        valueColor = TextPrimary,
        valueBold = true
    )
    MinBreathDetailRow(
        "Total Breath Time",
        "${formatMinBreathMs(parsed.totalBreathTimeMs)} (${"%.1f".format(breathPct)}%)"
    )
    MinBreathDetailRow("Number of Holds", "${parsed.holds.size}")
    MinBreathDetailRow("Longest Hold", formatMinBreathMs(longestMs))
    MinBreathDetailRow("Average Hold", formatMinBreathMs(avgMs))

    // ── Per-hold breakdown with estimated breathing times ────────────────
    if (parsed.holds.isNotEmpty()) {
        HorizontalDivider(
            color = TextDisabled.copy(alpha = 0.3f),
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            "Hold Breakdown",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        val sortedHolds = parsed.holds.sortedBy { it.holdNumber }
        // Estimate per-period breathing time: distribute totalBreathTimeMs evenly
        val breathPeriods = (sortedHolds.size - 1).coerceAtLeast(0)
        val avgBreathMs = if (breathPeriods > 0) parsed.totalBreathTimeMs / breathPeriods else 0L

        sortedHolds.forEachIndexed { index, hold ->
            val breathText = if (index < sortedHolds.size - 1 && avgBreathMs > 0) {
                // Use per-hold breathDurationMs if available, otherwise estimate
                val breathMs = hold.breathDurationMs ?: avgBreathMs
                ", breath ${formatMinBreathMs(breathMs)}"
            } else ""
            MinBreathDetailRow(
                "Hold #${hold.holdNumber}",
                "${formatMinBreathMs(hold.durationMs)}$breathText"
            )
        }
    }
}

// ── Min Breath session-aware chart ──────────────────────────────────────────

/**
 * Enhanced line chart for Min Breath sessions that shows data across the entire
 * session duration with vertical indicators for first contractions (dashed lines)
 * and shaded regions for breathing periods.
 */
@Composable
fun MinBreathSessionChart(
    samples: List<Float>,
    sampleTimestampsMs: List<Long>,
    lineColor: Color,
    sessionDurationMs: Long,
    holds: List<MinBreathHoldInfo>,
    showYLabels: Boolean = false,
    yMin: Float = samples.minOrNull() ?: 0f,
    yMax: Float = samples.maxOrNull() ?: 1f,
    modifier: Modifier = Modifier
) {
    if (samples.size < 2 || sampleTimestampsMs.size < 2) return

    val density = LocalDensity.current
    val leftPadPx = if (showYLabels) with(density) { 34.dp.toPx() } else with(density) { 4.dp.toPx() }
    val bottomPadPx = with(density) { 16.dp.toPx() }
    val topPadPx = with(density) { 4.dp.toPx() }
    val rightPadPx = with(density) { 4.dp.toPx() }

    val labelTextSizePx = with(density) { 9.sp.toPx() }
    val labelColor = TextSecondary.copy(alpha = 0.7f)
    val labelArgb = labelColor.toArgb()

    val yRange = (yMax - yMin).coerceAtLeast(1f)

    // Compute breathing periods from holds: between end of hold N and start of hold N+1
    // We need cumulative timing. Holds are sequential: hold1, breath1, hold2, breath2, ...
    // Cumulative offsets: hold1 starts at 0, breath1 starts at hold1.durationMs, etc.
    val breathingRegions = remember(holds) {
        val regions = mutableListOf<Pair<Long, Long>>() // startMs, endMs relative to session start
        var cumulativeMs = 0L
        val sortedHolds = holds.sortedBy { it.holdNumber }
        for (i in sortedHolds.indices) {
            val holdEnd = cumulativeMs + sortedHolds[i].durationMs
            if (i < sortedHolds.size - 1) {
                // There's a breathing period between this hold and the next
                val nextHoldStart = holdEnd + computeBreathDuration(sortedHolds, i, sessionDurationMs)
                regions.add(Pair(holdEnd, nextHoldStart))
                cumulativeMs = nextHoldStart
            } else {
                cumulativeMs = holdEnd
            }
        }
        regions
    }

    // Compute contraction absolute positions
    val contractionPositions = remember(holds) {
        val positions = mutableListOf<Long>() // absolute ms from session start
        var cumulativeMs = 0L
        val sortedHolds = holds.sortedBy { it.holdNumber }
        for (i in sortedHolds.indices) {
            sortedHolds[i].contractionMs?.let { cMs ->
                positions.add(cumulativeMs + cMs)
            }
            val holdEnd = cumulativeMs + sortedHolds[i].durationMs
            if (i < sortedHolds.size - 1) {
                cumulativeMs = holdEnd + computeBreathDuration(sortedHolds, i, sessionDurationMs)
            } else {
                cumulativeMs = holdEnd
            }
        }
        positions
    }

    // Use actual telemetry time span as the chart duration
    val chartDurationMs = if (sessionDurationMs > 0) sessionDurationMs
        else (sampleTimestampsMs.last() - sampleTimestampsMs.first()).coerceAtLeast(1L)

    val chartStartMs = sampleTimestampsMs.first()

    Canvas(modifier = modifier) {
        val totalW = size.width
        val totalH = size.height

        val plotLeft = leftPadPx
        val plotRight = totalW - rightPadPx
        val plotTop = topPadPx
        val plotBottom = totalH - bottomPadPx
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

        // ── Subtle horizontal grid lines ────────────────────────────────
        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
            val y = plotTop + plotH * (1f - frac)
            drawLine(
                color = TextDisabled.copy(alpha = 0.15f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f
            )
        }

        // ── Breathing period shading (light grey bands) ─────────────────
        breathingRegions.forEach { (startMs, endMs) ->
            val x1 = plotLeft + (startMs.toFloat() / chartDurationMs) * plotW
            val x2 = plotLeft + (endMs.toFloat() / chartDurationMs) * plotW
            drawRect(
                color = TextDisabled.copy(alpha = 0.12f),
                topLeft = Offset(x1.coerceIn(plotLeft, plotRight), plotTop),
                size = androidx.compose.ui.geometry.Size(
                    (x2 - x1).coerceIn(0f, plotRight - x1),
                    plotH
                )
            )
        }

        // ── First contraction vertical lines ────────────────────────────
        contractionPositions.forEach { absMs ->
            val frac = (absMs.toFloat() / chartDurationMs).coerceIn(0f, 1f)
            val x = plotLeft + frac * plotW
            // Draw dashed-style line (short segments)
            val dashLen = 6f
            val gapLen = 4f
            var cy = plotTop
            while (cy < plotBottom) {
                val endY = (cy + dashLen).coerceAtMost(plotBottom)
                drawLine(
                    color = TextSecondary.copy(alpha = 0.5f),
                    start = Offset(x, cy),
                    end = Offset(x, endY),
                    strokeWidth = 1.5f
                )
                cy += dashLen + gapLen
            }
        }

        // ── Main data line ──────────────────────────────────────────────
        val path = Path()
        samples.forEachIndexed { i, value ->
            val elapsedMs = sampleTimestampsMs[i] - chartStartMs
            val x = plotLeft + (elapsedMs.toFloat() / chartDurationMs) * plotW
            val y = plotTop + plotH * (1f - ((value - yMin) / yRange).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // End-point dots
        val firstElapsed = sampleTimestampsMs.first() - chartStartMs
        val lastElapsed = sampleTimestampsMs.last() - chartStartMs
        val firstX = plotLeft + (firstElapsed.toFloat() / chartDurationMs) * plotW
        val lastX = plotLeft + (lastElapsed.toFloat() / chartDurationMs) * plotW
        val firstY = plotTop + plotH * (1f - ((samples.first() - yMin) / yRange).coerceIn(0f, 1f))
        val lastY = plotTop + plotH * (1f - ((samples.last() - yMin) / yRange).coerceIn(0f, 1f))
        drawCircle(color = lineColor, radius = 5f, center = Offset(firstX, firstY))
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))

        // ── Y-axis labels ───────────────────────────────────────────────
        if (showYLabels) {
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = labelTextSizePx
                color = labelArgb
                textAlign = Paint.Align.RIGHT
            }
            for (step in 0..3) {
                val frac = step / 3f
                val value = yMin + frac * yRange
                val y = plotTop + plotH * (1f - frac)
                drawContext.canvas.nativeCanvas.drawText(
                    "${value.toInt()}",
                    plotLeft - with(density) { 3.dp.toPx() },
                    y + labelTextSizePx / 2f,
                    paint
                )
            }
        }

        // ── X-axis time labels ──────────────────────────────────────────
        if (chartDurationMs > 0L) {
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = labelTextSizePx
                color = labelArgb
                textAlign = Paint.Align.CENTER
            }
            val totalMinutes = (chartDurationMs / 60_000L).toInt()
            for (min in 1..totalMinutes) {
                val frac = (min * 60_000L).toFloat() / chartDurationMs.toFloat()
                if (frac > 1f) break
                val x = plotLeft + frac * plotW
                drawLine(
                    color = labelColor,
                    start = Offset(x, plotBottom),
                    end = Offset(x, plotBottom + with(density) { 3.dp.toPx() }),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${min}m",
                    x,
                    totalH - with(density) { 1.dp.toPx() },
                    paint
                )
            }
        }
    }
}

// ── Helper: estimate breath duration between holds ──────────────────────────

private fun computeBreathDuration(
    sortedHolds: List<MinBreathHoldInfo>,
    holdIndex: Int,
    sessionDurationMs: Long
): Long {
    // We don't have explicit breath durations per-period in the JSON,
    // but we can estimate: total session time minus total hold time = total breath time
    // distributed proportionally. For simplicity, use equal distribution.
    val totalHoldMs = sortedHolds.sumOf { it.durationMs }
    val totalBreathMs = (sessionDurationMs - totalHoldMs).coerceAtLeast(0L)
    val breathPeriods = (sortedHolds.size - 1).coerceAtLeast(1)
    return totalBreathMs / breathPeriods
}

// ── Shared helpers ──────────────────────────────────────────────────────────

@Composable
private fun MinBreathDetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    valueBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            value,
            style = if (valueBold)
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            else
                MaterialTheme.typography.bodyLarge,
            color = if (valueColor == Color.Unspecified)
                MaterialTheme.colorScheme.onSurface
            else
                valueColor
        )
    }
}

private fun formatMinBreathMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
