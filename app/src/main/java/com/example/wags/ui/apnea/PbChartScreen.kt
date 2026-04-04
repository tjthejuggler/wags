package com.example.wags.ui.apnea

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PbChartScreen(
    navController: NavController,
    viewModel: PbChartViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Force landscape
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            if (state.showPbOnly) "Personal Bests Only" else "All Holds",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    PbToggleChip(
                        showPbOnly = state.showPbOnly,
                        onToggle = { viewModel.togglePbOnly() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = TextSecondary) }
        } else {
            val points: List<PbChartPoint> = if (state.showPbOnly) state.pbOnlyPoints else state.allPoints
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No breath holds recorded yet", color = TextSecondary)
                }
            } else {
                HoldChart(
                    points = points,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PB toggle chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PbToggleChip(showPbOnly: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            "PB only",
            fontSize = 12.sp,
            color = if (showPbOnly) TextPrimary else TextSecondary,
            modifier = Modifier.padding(end = 4.dp)
        )
        Switch(
            checked = showPbOnly,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = SurfaceVariant,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceVariant
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chart composable with zoom & pan
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HoldChart(
    points: List<PbChartPoint>,
    modifier: Modifier = Modifier
) {
    // Zoom & pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    // Reset zoom when points change
    LaunchedEffect(points) {
        scale = 1f
        offsetX = 0f
    }

    val leftPad = 56f   // space for Y-axis labels
    val bottomPad = 40f // space for X-axis labels
    val topPad = 16f
    val rightPad = 16f

    val lineColor = TextPrimary
    val dotColor = TextPrimary
    val gridColor = TextDisabled.copy(alpha = 0.3f)
    val labelColor = TextSecondary

    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 28f
            color = android.graphics.Color.argb(180, 144, 144, 144)
        }
    }

    Canvas(
        modifier = modifier
            .background(BackgroundDark)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 10f)
                    // Adjust offset so zoom centers on the gesture
                    val chartW = size.width - leftPad - rightPad
                    val maxOffset = chartW * (newScale - 1f)
                    val newOffset = (offsetX + pan.x).coerceIn(-maxOffset, 0f)
                    scale = newScale
                    offsetX = newOffset
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val chartW = w - leftPad - rightPad
        val chartH = h - topPad - bottomPad

        if (points.size < 2) {
            // Single point — draw a dot
            val cx = leftPad + chartW / 2f
            val cy = topPad + chartH / 2f
            drawCircle(dotColor, radius = 6f, center = Offset(cx, cy))
            drawContext.canvas.nativeCanvas.drawText(
                formatDurationLabel(points[0].durationMs),
                cx + 10f, cy - 10f, paint
            )
            return@Canvas
        }

        val minT = points.first().timestampMs.toFloat()
        val maxT = points.last().timestampMs.toFloat()
        val timeRange = (maxT - minT).coerceAtLeast(1f)

        // Y-axis in seconds
        val maxDurSec = points.maxOf { it.durationMs } / 1000f
        val minDurSec = 0f
        val durRange = (maxDurSec - minDurSec).coerceAtLeast(1f)
        val durPad = durRange * 0.1f

        val yMin = minDurSec
        val yMax = maxDurSec + durPad

        // Scaled time range
        val scaledTimeRange = timeRange / scale
        val viewStartT = minT - (offsetX / chartW) * scaledTimeRange
        val viewEndT = viewStartT + scaledTimeRange

        // ── Grid lines (Y-axis) ──────────────────────────────────────────
        val yTicks = computeYTicks(yMin, yMax)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (tick in yTicks) {
            val y = topPad + chartH * (1f - (tick - yMin) / (yMax - yMin))
            drawLine(gridColor, Offset(leftPad, y), Offset(w - rightPad, y),
                strokeWidth = 1f, pathEffect = dashEffect)
            drawContext.canvas.nativeCanvas.drawText(
                formatDurationLabel((tick * 1000f).toLong()),
                4f, y + 10f, paint
            )
        }

        // ── Grid lines (X-axis) ──────────────────────────────────────────
        val xTicks = computeSmartTimeTicks(viewStartT.toLong(), viewEndT.toLong())
        for (tick in xTicks) {
            val x = leftPad + chartW * ((tick - viewStartT) / (viewEndT - viewStartT))
            if (x < leftPad || x > w - rightPad) continue
            drawLine(gridColor, Offset(x, topPad), Offset(x, topPad + chartH),
                strokeWidth = 1f, pathEffect = dashEffect)
            val label = formatSmartDateLabel(tick, viewEndT.toLong() - viewStartT.toLong())
            drawContext.canvas.nativeCanvas.drawText(
                label, x - 30f, h - 4f, paint
            )
        }

        // ── Line path ────────────────────────────────────────────────────
        val path = Path()
        var first = true
        val visiblePoints = mutableListOf<Offset>()

        for (pt in points) {
            val ptT = pt.timestampMs.toFloat()
            val ptSec = pt.durationMs.toFloat() / 1000f
            val x = leftPad + chartW * ((ptT - viewStartT) / (viewEndT - viewStartT))
            val y = topPad + chartH * (1f - (ptSec - yMin) / (yMax - yMin))
            if (x < leftPad - 20f || x > w - rightPad + 20f) continue
            val clampedX = x.coerceIn(leftPad, w - rightPad)
            if (first) {
                path.moveTo(clampedX, y)
                first = false
            } else {
                path.lineTo(clampedX, y)
            }
            visiblePoints.add(Offset(clampedX, y))
        }

        drawPath(path, lineColor, style = Stroke(width = 2.5f))

        // ── Dots ─────────────────────────────────────────────────────────
        for (pt in visiblePoints) {
            drawCircle(dotColor, radius = 4f, center = pt)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Y-axis tick computation (duration in seconds)
// ─────────────────────────────────────────────────────────────────────────────

private fun computeYTicks(minSec: Float, maxSec: Float): List<Float> {
    val range = maxSec - minSec
    if (range <= 0f) return listOf(minSec)
    // Target ~5 ticks
    val rawStep = range / 5f
    val step = niceStep(rawStep)
    val ticks = mutableListOf<Float>()
    var v = (kotlin.math.ceil(minSec / step) * step).toFloat()
    while (v <= maxSec) {
        ticks.add(v)
        v += step.toFloat()
    }
    return ticks
}

private fun niceStep(raw: Float): Double {
    val exp = kotlin.math.floor(kotlin.math.log10(raw.toDouble()))
    val base = Math.pow(10.0, exp)
    val frac = raw.toDouble() / base
    val nice = when {
        frac <= 1.5 -> 1.0
        frac <= 3.5 -> 2.0
        frac <= 7.5 -> 5.0
        else -> 10.0
    }
    return nice * base
}

// ─────────────────────────────────────────────────────────────────────────────
// Smart X-axis time ticks
// ─────────────────────────────────────────────────────────────────────────────

private fun computeSmartTimeTicks(startMs: Long, endMs: Long): List<Long> {
    val rangeMs = endMs - startMs
    if (rangeMs <= 0) return emptyList()

    // Choose interval based on visible range
    val intervalMs = when {
        rangeMs < 2 * 3_600_000L       -> 15 * 60_000L        // < 2h → 15 min
        rangeMs < 12 * 3_600_000L      -> 3_600_000L          // < 12h → 1 hour
        rangeMs < 3 * 86_400_000L      -> 6 * 3_600_000L      // < 3d → 6 hours
        rangeMs < 14 * 86_400_000L     -> 86_400_000L          // < 2w → 1 day
        rangeMs < 60 * 86_400_000L     -> 7 * 86_400_000L      // < 2m → 1 week
        rangeMs < 365 * 86_400_000L    -> 30 * 86_400_000L     // < 1y → ~1 month
        else                            -> 90 * 86_400_000L     // > 1y → ~3 months
    }

    val ticks = mutableListOf<Long>()
    // Align to interval
    val firstTick = ((startMs / intervalMs) + 1) * intervalMs
    var t = firstTick
    while (t <= endMs) {
        ticks.add(t)
        t += intervalMs
    }
    return ticks
}

private val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
private val fmtDayMonth = SimpleDateFormat("MMM d", Locale.getDefault())
private val fmtMonthYear = SimpleDateFormat("MMM ''yy", Locale.getDefault())
private val fmtYear = SimpleDateFormat("yyyy", Locale.getDefault())

private fun formatSmartDateLabel(tickMs: Long, visibleRangeMs: Long): String {
    val date = Date(tickMs)
    return when {
        visibleRangeMs < 2 * 86_400_000L  -> fmtTime.format(date)
        visibleRangeMs < 60 * 86_400_000L -> fmtDayMonth.format(date)
        visibleRangeMs < 365 * 86_400_000L -> fmtDayMonth.format(date)
        else -> fmtMonthYear.format(date)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Duration label formatter (for Y-axis)
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDurationLabel(ms: Long): String {
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return if (min > 0) "${min}m${if (sec > 0) " ${sec}s" else ""}"
    else "${sec}s"
}
