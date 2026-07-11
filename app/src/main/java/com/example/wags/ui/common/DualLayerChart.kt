package com.example.wags.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.ui.theme.SurfaceDark

/**
 * A reusable dual-layer chart widget that displays both short-term (foreground)
 * and long-term (background) data for a given metric.
 *
 * The foreground shows recent data with smooth styling and full opacity,
 * while the background shows the entire session data in a muted gray color.
 *
 * @param shortTermData Recent data points to display prominently
 * @param longTermData All session data points to display in background
 * @param label The metric label to display
 * @param colors Color scheme for the chart
 * @param modifier Standard Compose modifier
 */
@Composable
fun DualLayerChart(
    shortTermData: List<Float>,
    longTermData: List<Float>,
    label: String,
    colors: DualLayerChartColors = DualLayerChartColors(),
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "chart_shimmer")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing), RepeatMode.Restart
        ),
        label = "shimmer"
    )

    // Calculate Y-axis range from combined data
    val allData = shortTermData + longTermData
    val minVal = if (allData.isEmpty()) 0f else allData.min()
    val maxVal = if (allData.isEmpty()) 1f else allData.max()
    val range = (maxVal - minVal).coerceAtLeast(0.1f)

    // Animate Y-axis bounds smoothly
    val animMin by animateFloatAsState(
        targetValue = (minVal - range * 0.15).toFloat(),
        animationSpec = tween(600, easing = LinearOutSlowInEasing), label = "y_min"
    )
    val animMax by animateFloatAsState(
        targetValue = (maxVal + range * 0.15).toFloat(),
        animationSpec = tween(600, easing = LinearOutSlowInEasing), label = "y_max"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(colors.bgDark, colors.bgMid.copy(alpha = 0.3f), colors.bgDark)
                )
            )
    ) {
        if (allData.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                val w = size.width
                val h = size.height

                // Draw long-term data (background, muted)
                if (longTermData.size >= 2) {
                    val longPath = Path()
                    longTermData.forEachIndexed { idx, value ->
                        val x = idx.toFloat() / (longTermData.size - 1) * w
                        val y = h - ((value - animMin) / (animMax - animMin) * h).coerceIn(0f, h)
                        if (idx == 0) longPath.moveTo(x, y) else longPath.lineTo(x, y)
                    }

                    // Draw background line with muted color
                    drawPath(
                        longPath,
                        colors.longTermColor.copy(alpha = 0.3f),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Draw short-term data (foreground, prominent)
                if (shortTermData.size >= 2) {
                    val shortPath = Path()
                    shortTermData.forEachIndexed { idx, value ->
                        val x = idx.toFloat() / (shortTermData.size - 1) * w
                        val y = h - ((value - animMin) / (animMax - animMin) * h).coerceIn(0f, h)
                        if (idx == 0) shortPath.moveTo(x, y) else shortPath.lineTo(x, y)
                    }

                    // Draw foreground line with full color
                    drawPath(
                        shortPath,
                        colors.foregroundColor,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    // Draw dots at each short-term data point
                    shortTermData.forEachIndexed { idx, value ->
                        val x = idx.toFloat() / (shortTermData.size - 1) * w
                        val y = h - ((value - animMin) / (animMax - animMin) * h).coerceIn(0f, h)
                        drawCircle(
                            colors.dotColor,
                            radius = 3.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                }
            }

            // Label
            androidx.compose.material3.Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = colors.labelColor.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(
                    "awaiting data…",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.labelColor.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

/**
 * Color scheme for the dual-layer chart.
 */
data class DualLayerChartColors(
    val foregroundColor: Color = Color(0xFFD0D0D0),
    val longTermColor: Color = Color(0xFF707070),
    val dotColor: Color = Color(0xFFFFFFFF),
    val bgDark: Color = SurfaceDark,
    val bgMid: Color = Color(0xFF383838),
    val labelColor: Color = Color(0xFF707070)
)