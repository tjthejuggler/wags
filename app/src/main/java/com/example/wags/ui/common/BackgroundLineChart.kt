package com.example.wags.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.ui.theme.SurfaceDark

/**
 * A simple background chart that displays long-term data on its own scale.
 * Designed to be layered behind the main scrolling charts.
 *
 * @param data The data points to display
 * @param color The color for the line (should be muted/transparent)
 * @param modifier Standard Compose modifier
 */
@Composable
fun BackgroundLineChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (data.size < 2) return

    // Calculate Y-axis range from this data only (independent scale)
    val minVal = data.min()
    val maxVal = data.max()
    val range = (maxVal - minVal).coerceAtLeast(0.1f)

    // Animate Y-axis bounds smoothly
    val animMin by animateFloatAsState(
        targetValue = (minVal - range * 0.15).toFloat(),
        animationSpec = tween(600, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
        label = "bg_y_min"
    )
    val animMax by animateFloatAsState(
        targetValue = (maxVal + range * 0.15).toFloat(),
        animationSpec = tween(600, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
        label = "bg_y_max"
    )

    // Make the line more visible by increasing alpha
    val visibleColor = color.copy(alpha = 0.5f)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        val w = size.width
        val h = size.height

        val path = Path()
        data.forEachIndexed { idx, value ->
            val x = idx.toFloat() / (data.size - 1) * w
            val y = h - ((value - animMin) / (animMax - animMin) * h).coerceIn(0f, h)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path,
            visibleColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}