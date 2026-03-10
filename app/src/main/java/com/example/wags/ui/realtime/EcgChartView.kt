package com.example.wags.ui.realtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.BackgroundDark
import com.example.wags.ui.theme.EcgCyan

/**
 * Hardware-accelerated real-time ECG chart using Jetpack Compose Canvas.
 * Android Canvas API is GPU-accelerated via RenderNode on API 29+.
 *
 * Performance rules:
 * - FloatArray snapshot (not List<Float>) — avoids boxing/unboxing GC pressure
 * - remember { Path() } + path.reset() each frame — reuse Path object, never allocate per frame
 * - Visible window ≤ 520 samples (4s at 130 Hz ECG)
 * - ViewModel emits new array reference each 16ms tick so Compose detects state change
 */
@Composable
fun EcgChartView(
    samples: FloatArray,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
    lineColor: Color = EcgCyan,
    backgroundColor: Color = BackgroundDark,
    strokeWidthDp: Dp = 1.5.dp,
    amplitudeScaleFactor: Float = 4000f  // ECG amplitude scale (µV)
) {
    val path = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .background(backgroundColor)
    ) {
        if (samples.isEmpty()) return@Canvas

        path.reset()  // Reuse Path object — never allocate per frame

        val xStep = size.width / (samples.size - 1).coerceAtLeast(1)
        val midY = size.height / 2f
        val scale = size.height / amplitudeScaleFactor

        samples.forEachIndexed { i, v ->
            val x = i * xStep
            val y = (midY - v * scale).coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = strokeWidthDp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}
