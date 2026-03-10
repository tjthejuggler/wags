package com.example.wags.ui.realtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.BackgroundDark
import com.example.wags.ui.theme.EcgCyan

/**
 * RR interval tachogram (scatter + connecting lines).
 * Uses drawPoints with PointMode.Lines — faster than Path for sparse RR scatter data.
 *
 * @param rrIntervals List of RR intervals in milliseconds (most recent last)
 * @param minRrMs Y-axis minimum (default 400ms = 150 BPM)
 * @param maxRrMs Y-axis maximum (default 1200ms = 50 BPM)
 */
@Composable
fun TachogramView(
    rrIntervals: List<Double>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 100.dp,
    lineColor: Color = EcgCyan,
    backgroundColor: Color = BackgroundDark,
    minRrMs: Float = 400f,
    maxRrMs: Float = 1200f
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .background(backgroundColor)
    ) {
        if (rrIntervals.size < 2) return@Canvas

        val n = rrIntervals.size
        val xStep = size.width / (n - 1).coerceAtLeast(1)
        val rrRange = maxRrMs - minRrMs

        // Build point pairs for PointMode.Lines (pairs of start/end for each segment)
        val points = mutableListOf<Offset>()
        for (i in 0 until n - 1) {
            val x1 = i * xStep
            val y1 = size.height - ((rrIntervals[i].toFloat() - minRrMs) / rrRange * size.height)
                .coerceIn(0f, size.height)
            val x2 = (i + 1) * xStep
            val y2 = size.height - ((rrIntervals[i + 1].toFloat() - minRrMs) / rrRange * size.height)
                .coerceIn(0f, size.height)
            points.add(Offset(x1, y1))
            points.add(Offset(x2, y2))
        }

        drawPoints(
            points = points,
            pointMode = PointMode.Lines,
            color = lineColor,
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Draw dot at each RR point
        rrIntervals.forEachIndexed { i, rr ->
            val x = i * xStep
            val y = size.height - ((rr.toFloat() - minRrMs) / rrRange * size.height)
                .coerceIn(0f, size.height)
            drawCircle(color = lineColor, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}
