package com.example.wags.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  COHERENCE STRIP CHART
//
//  Scrolling coherence ratio chart that behaves like the RR/RMSSD strip charts.
//  Coherence samples arrive every ~5 seconds and are plotted at wall-clock time.
//  The chart scrolls smoothly at 60 fps via withFrameNanos.
//  Uses Catmull-Rom spline interpolation for smooth curves between sparse points.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Default time window for coherence chart (60 seconds). */
private const val COHERENCE_WINDOW_MS = 60_000.0

/** Assumed cadence between raw coherence samples (the loop computes one every ~5 s). */
private const val SAMPLE_INTERVAL_MS = 5_000.0

/** Sub-point spacing used to densify the line (1 sub-point per second, like RR beats). */
private const val SUB_POINT_MS = 1_000.0

/**
 * A single coherence sample with its wall-clock time offset.
 */
data class TimedCoherence(val timeMs: Double, val value: Double)

/**
 * Holds the strip-chart state for coherence ratios.
 *
 * Coherence values arrive as a plain `List<Float>` history whose samples are
 * produced every [SAMPLE_INTERVAL_MS]. To make the foreground strip look like
 * the RR/RMSSD strips (short line segments, no left-edge pile-up, smooth
 * per-second scrolling), each new raw sample is:
 *  1. Stamped at the wall-clock time it should have been produced
 *     (`now - interval` for the newest sample), and
 *  2. Expanded into ~[SAMPLE_INTERVAL_MS]/[SUB_POINT_MS] linearly interpolated
 *     sub-points between the previous anchor and the new anchor.
 *
 * The resulting `samples` list therefore contains roughly one point per second,
 * exactly like the RR strip chart's beat cadence.
 */
@Stable
class CoherenceStripChartState(private val windowMs: Double = COHERENCE_WINDOW_MS) {
    val samples = mutableStateListOf<TimedCoherence>()
    var started by mutableStateOf(false)
        private set
    var firstSampleWallMs: Long = 0L
        private set
    private var lastSourceSize = 0

    /**
     * Ingest new coherence samples from the ViewModel.
     */
    fun ingest(source: List<Float>, nowNanos: Long) {
        if (source.isEmpty()) return

        val wallNow = System.currentTimeMillis()
        if (!started) {
            started = true
            firstSampleWallMs = wallNow
        }
        val wallTimeMs = (wallNow - firstSampleWallMs).toDouble()

        // How many raw samples are genuinely new
        val newCount = if (lastSourceSize == 0 && samples.isEmpty()) {
            source.size
        } else {
            (source.size - lastSourceSize).coerceAtLeast(0)
        }

        if (newCount <= 0) {
            lastSourceSize = source.size
            return
        }

        val startIdx = source.size - newCount

        // Anchor times: the newest raw sample was produced ~SAMPLE_INTERVAL_MS ago;
        // walk backwards from there at the fixed cadence so points never pile up.
        // For the very first batch we further extend into the past.
        for (i in startIdx until source.size) {
            // Position of this raw sample on the wall-clock axis
            val stepsFromLatest = (source.size - 1) - i
            val anchorTime = wallTimeMs - SAMPLE_INTERVAL_MS * (stepsFromLatest + 1)

            // Previous anchor (time, value) — either the last ingested sample or
            // the previous element in the source list for the first batch.
            val prevTime: Double
            val prevValue: Double
            if (samples.isNotEmpty()) {
                prevTime = samples.last().timeMs
                prevValue = samples.last().value
            } else if (i > 0) {
                prevTime = anchorTime - SAMPLE_INTERVAL_MS
                prevValue = source[i - 1].toDouble()
            } else {
                // Very first sample ever — just drop a single point.
                samples.add(TimedCoherence(anchorTime.coerceAtLeast(0.0), source[i].toDouble()))
                continue
            }

            // Densify: emit 1 sub-point per SUB_POINT_MS between prev and anchor.
            val span = (anchorTime - prevTime).coerceAtLeast(1.0)
            val subPoints = (span / SUB_POINT_MS).toInt().coerceAtLeast(1)
            for (k in 1..subPoints) {
                val t = k.toDouble() / subPoints
                val time = prevTime + t * span
                val value = prevValue + t * (source[i].toDouble() - prevValue)
                // Never place a point to the right of the previous one (monotonic time)
                val safeTime = if (samples.isNotEmpty() && time <= samples.last().timeMs)
                    samples.last().timeMs + 1.0 else time
                samples.add(TimedCoherence(safeTime, value))
            }
        }

        lastSourceSize = source.size

        // Prune far-offscreen samples
        val cutoff = wallTimeMs - windowMs * 2
        while (samples.size > 2 && samples.first().timeMs < cutoff) {
            samples.removeAt(0)
        }
    }
}

/**
 * Scrolling coherence ratio strip chart.
 *
 * @param coherenceHistory List of coherence ratio samples from the ViewModel.
 * @param windowMs Time window in ms (default 60 s).
 * @param colors Colour scheme for the chart.
 * @param waitingText Text shown before enough data arrives.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun CoherenceStripChart(
    coherenceHistory: List<Float>,
    modifier: Modifier = Modifier,
    windowMs: Double = COHERENCE_WINDOW_MS,
    colors: StripChartColors = StripChartColors(),
    waitingText: String = "awaiting data…"
) {
    val state = remember { CoherenceStripChartState(windowMs) }
    var cursorTimeMs by remember { mutableDoubleStateOf(0.0) }

    // Fingerprint must not truncate the float to Long — two close coherence
    // values (e.g. 2.04 and 2.03) would otherwise collide and skip an update.
    val fingerprint = remember(coherenceHistory) {
        if (coherenceHistory.isEmpty()) 0L
        else (coherenceHistory.size.toLong() shl 32) xor
             coherenceHistory.last().toBits().toLong()
    }
    LaunchedEffect(fingerprint) { state.ingest(coherenceHistory, System.nanoTime()) }

    // Pure wall-clock cursor: advances every frame at exactly real-time speed
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { _ ->
                if (state.started) {
                    cursorTimeMs = (System.currentTimeMillis() - state.firstSampleWallMs).toDouble()
                }
            }
        }
    }

    CoherenceStripChartShell(
        samples = state.samples,
        cursorTimeMs = cursorTimeMs,
        windowMs = windowMs,
        colors = colors,
        waitingText = waitingText,
        defaultMinY = 0.0,
        defaultMaxY = 5.0,
        minYRange = 1.0,
        modifier = modifier
    )
}

/**
 * Internal shell that renders the coherence strip chart using the same
 * approach as RR/RMSSD strip charts (Catmull-Rom spline, left-edge fade).
 */
@Composable
private fun CoherenceStripChartShell(
    samples: List<TimedCoherence>,
    cursorTimeMs: Double,
    windowMs: Double,
    colors: StripChartColors,
    waitingText: String,
    defaultMinY: Double,
    defaultMaxY: Double,
    minYRange: Double,
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

    val targetMin = if (samples.isEmpty()) defaultMinY else samples.minOf { it.value }
    val targetMax = if (samples.isEmpty()) defaultMaxY else samples.maxOf { it.value }
    val range = (targetMax - targetMin).coerceAtLeast(minYRange)
    val animMin by animateFloatAsState(
        targetValue = (targetMin - range * 0.15).toFloat(),
        animationSpec = tween(600, easing = LinearOutSlowInEasing), label = "y_min"
    )
    val animMax by animateFloatAsState(
        targetValue = (targetMax + range * 0.15).toFloat(),
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
        if (samples.size >= 2) {
            val snap = samples.toList()
            val cursor = cursorTimeMs
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                drawCoherenceStripChart(
                    samples = snap,
                    cursorTimeMs = cursor,
                    windowMs = windowMs,
                    shimmerPhase = shimmerPhase,
                    paddedMin = animMin.toDouble(),
                    paddedMax = animMax.toDouble(),
                    lineColor = colors.lineColor,
                    dotColor = colors.dotColor,
                    glowColor = colors.glowColor,
                    bgDark = colors.bgDark
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    waitingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.waitingTextColor.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

// ── Drawing ─────────────────────────────────────────────────────────────────

private fun DrawScope.drawCoherenceStripChart(
    samples: List<TimedCoherence>,
    cursorTimeMs: Double,
    windowMs: Double,
    shimmerPhase: Float,
    paddedMin: Double,
    paddedMax: Double,
    lineColor: Color,
    dotColor: Color,
    glowColor: Color,
    bgDark: Color
) {
    if (samples.size < 2) return
    val w = size.width
    val h = size.height
    val yRange = (paddedMax - paddedMin).coerceAtLeast(1.0)
    val windowStart = cursorTimeMs - windowMs

    fun xAt(t: Double) = ((t - windowStart) / windowMs * w).toFloat()
    fun yAt(v: Double) = h - ((v - paddedMin) / yRange * h).toFloat()

    // Keep all samples that fall within the visible window (left edge with 2 s look-behind for
    // smooth entry). There is intentionally NO upper-bound filter on cursorTimeMs.
    val visible = samples.filter { it.timeMs >= windowStart - 2000 }
    if (visible.size < 2) return
    val pts = visible.map { Offset(xAt(it.timeMs), yAt(it.value)) }

    // Draw zone threshold lines
    val highY = (h - ((3.0 - paddedMin) / yRange * h).toFloat()).coerceIn(0f, h)
    val medY = (h - ((1.0 - paddedMin) / yRange * h).toFloat()).coerceIn(0f, h)
    
    drawLine(
        color = lineColor.copy(alpha = 0.3f),
        start = Offset(0f, highY),
        end = Offset(w.toFloat(), highY),
        strokeWidth = 1.dp.toPx()
    )
    drawLine(
        color = lineColor.copy(alpha = 0.2f),
        start = Offset(0f, medY),
        end = Offset(w.toFloat(), medY),
        strokeWidth = 1.dp.toPx()
    )

    // Glow line (wider, dimmer)
    val glowPath = Path()
    buildCatmullRomPath(glowPath, pts)
    drawPath(
        glowPath, glowColor.copy(alpha = 0.15f),
        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Main line
    val mainPath = Path()
    buildCatmullRomPath(mainPath, pts)
    drawPath(
        mainPath, lineColor,
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Dots at each data point
    pts.forEach { pt ->
        if (pt.x < -10f || pt.x > w + 10f) return@forEach
        val nx = (pt.x / w).coerceIn(0f, 1f)
        val fade = if (nx < 0.15f) nx / 0.15f else 1f
        val dist = kotlin.math.abs(nx - shimmerPhase)
        val boost = (1f - (dist * 4f).coerceIn(0f, 1f)) * 0.4f
        drawCircle(
            dotColor.copy(alpha = (0.3f + boost) * fade),
            radius = (1.8f + boost * 2f).dp.toPx(),
            center = pt
        )
    }

    // Left-edge fade overlay
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(bgDark, bgDark.copy(alpha = 0f)),
            startX = 0f, endX = w * 0.12f
        ),
        size = size
    )
}

// ── Catmull-Rom spline ──────────────────────────────────────────────────────

/**
 * Builds a Catmull-Rom spline path through the given points.
 * Produces a smooth curve that passes through every data point.
 */
private fun buildCatmullRomPath(path: Path, points: List<Offset>) {
    if (points.size < 2) return
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) { path.lineTo(points[1].x, points[1].y); return }

    val ext = buildList {
        add(Offset(
            points[0].x - (points[1].x - points[0].x),
            points[0].y - (points[1].y - points[0].y)
        ))
        addAll(points)
        add(Offset(
            points.last().x + (points.last().x - points[points.size - 2].x),
            points.last().y + (points.last().y - points[points.size - 2].y)
        ))
    }
    val tension = 0.5f
    val segs = 12
    for (i in 1 until ext.size - 2) {
        val p0 = ext[i - 1]; val p1 = ext[i]; val p2 = ext[i + 1]; val p3 = ext[i + 2]
        for (s in 1..segs) {
            val t = s.toFloat() / segs; val t2 = t * t; val t3 = t2 * t
            val x = tension * (
                (2 * p1.x) +
                (-p0.x + p2.x) * t +
                (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3
            )
            val y = tension * (
                (2 * p1.y) +
                (-p0.y + p2.y) * t +
                (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3
            )
            path.lineTo(x, y)
        }
    }
}
