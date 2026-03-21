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
//  SHARED STRIP CHART FOR RR INTERVALS & RMSSD
//
//  Single source of truth used by every screen that shows a scrolling RR or
//  RMSSD chart (BreathingScreen, ResonanceSessionScreen, AssessmentRunScreen,
//  ReadinessScreen, MorningReadinessScreen).
//
//  Behaves like a classic strip-chart recorder / ECG monitor:
//  • Configurable time window (default 30 s).
//  • Each RR interval is plotted at its cumulative time position so that
//    shorter intervals appear closer together on the X axis.
//  • A continuously-advancing cursor (driven by withFrameNanos) moves the
//    "now" edge smoothly to the right at real-time speed.
//  • Once the line fills the window, old data scrolls off the left edge.
//  • Data arrives in ~500 ms polling chunks but the cursor moves every frame,
//    producing perfectly smooth motion.
//  • Catmull-Rom spline interpolation for smooth curves through every point.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Default chart colours (monochrome palette) ──────────────────────────────
private val DefaultChartLine = Color(0xFFD0D0D0)
private val DefaultChartDot  = Color(0xFFFFFFFF)
private val DefaultChartGlow = Color(0xFF909090)
private val ChartBgDark      = Color(0xFF0A0A0A)   // Ink
private val ChartBgMid       = Color(0xFF1C1C1C)   // Charcoal
private val ChartTextDim     = Color(0xFF707070)    // Ash

/** Default time window the chart displays, in milliseconds. */
const val DEFAULT_CHART_WINDOW_MS = 30_000.0

/**
 * Colour scheme for a strip chart. Callers can override to get different
 * line/dot/glow colours (e.g. amber for RMSSD).
 */
data class StripChartColors(
    val lineColor: Color = DefaultChartLine,
    val dotColor: Color = DefaultChartDot,
    val glowColor: Color = DefaultChartGlow,
    val bgDark: Color = ChartBgDark,
    val bgMid: Color = ChartBgMid,
    val waitingTextColor: Color = ChartTextDim
)

// ── Data model ──────────────────────────────────────────────────────────────

/**
 * A single data point with its cumulative time offset from the first beat.
 * [timeMs] = sum of all preceding RR intervals (0 for the first beat).
 * [valueMs] = the value to plot on the Y axis (RR interval or RMSSD diff).
 */
data class TimedBeat(val timeMs: Double, val valueMs: Double)

// ── State holders ───────────────────────────────────────────────────────────

/**
 * Holds the strip-chart state for RR intervals. Converts raw RR intervals
 * into time-positioned beats and tracks a smoothly-advancing "now" cursor.
 *
 * The ViewModel provides a **sliding window** of the last ~45 RR intervals
 * from a circular buffer. We detect new beats by comparing the last value
 * in our internal buffer with the tail of the source list.
 */
@Stable
class RrStripChartState(private val windowMs: Double = DEFAULT_CHART_WINDOW_MS) {
    val beats = mutableStateListOf<TimedBeat>()
    var cumulativeTimeMs by mutableDoubleStateOf(0.0)
        private set
    var firstBeatNanos: Long = 0L
        private set
    var started by mutableStateOf(false)
        private set
    private var lastSourceTail = 0.0

    fun ingest(source: List<Double>, nowNanos: Long) {
        if (source.isEmpty()) return
        if (!started) {
            started = true
            firstBeatNanos = nowNanos
        }
        val newBeatsCount: Int = if (beats.isEmpty()) {
            source.size
        } else {
            val lastKnownValue = lastSourceTail
            var overlapIndex = -1
            for (i in source.size - 1 downTo 0) {
                if (source[i] == lastKnownValue) { overlapIndex = i; break }
            }
            if (overlapIndex >= 0) source.size - overlapIndex - 1 else source.size
        }
        if (newBeatsCount <= 0) {
            lastSourceTail = source.last()
            return
        }
        val startIdx = source.size - newBeatsCount
        for (i in startIdx until source.size) {
            val rrMs = source[i]
            if (beats.isNotEmpty()) cumulativeTimeMs += rrMs
            beats.add(TimedBeat(timeMs = cumulativeTimeMs, valueMs = rrMs))
        }
        lastSourceTail = source.last()
        val cutoff = cumulativeTimeMs - windowMs * 2
        while (beats.size > 2 && beats.first().timeMs < cutoff) beats.removeAt(0)
    }
}

/**
 * Holds the strip-chart state for RMSSD. Computes per-beat RMSSD
 * (|RR[i] − RR[i−1]|) from raw RR intervals and plots them on the same
 * cumulative-time axis as the RR chart.
 */
@Stable
class RmssdStripChartState(private val windowMs: Double = DEFAULT_CHART_WINDOW_MS) {
    val beats = mutableStateListOf<TimedBeat>()
    var cumulativeTimeMs by mutableDoubleStateOf(0.0)
        private set
    var firstBeatNanos: Long = 0L
        private set
    var started by mutableStateOf(false)
        private set
    private var lastSourceTail = 0.0
    private var prevRrMs = Double.NaN

    fun ingest(source: List<Double>, nowNanos: Long) {
        if (source.isEmpty()) return
        if (!started) {
            started = true
            firstBeatNanos = nowNanos
        }
        val newBeatsCount: Int = if (beats.isEmpty() && prevRrMs.isNaN()) {
            source.size
        } else {
            val lastKnownValue = lastSourceTail
            var overlapIndex = -1
            for (i in source.size - 1 downTo 0) {
                if (source[i] == lastKnownValue) { overlapIndex = i; break }
            }
            if (overlapIndex >= 0) source.size - overlapIndex - 1 else source.size
        }
        if (newBeatsCount <= 0) {
            lastSourceTail = source.last()
            return
        }
        val startIdx = source.size - newBeatsCount
        for (i in startIdx until source.size) {
            val rrMs = source[i]
            if (beats.isNotEmpty() || !prevRrMs.isNaN()) cumulativeTimeMs += rrMs
            if (!prevRrMs.isNaN()) {
                val diff = kotlin.math.abs(rrMs - prevRrMs)
                beats.add(TimedBeat(timeMs = cumulativeTimeMs, valueMs = diff))
            }
            prevRrMs = rrMs
        }
        lastSourceTail = source.last()
        val cutoff = cumulativeTimeMs - windowMs * 2
        while (beats.size > 2 && beats.first().timeMs < cutoff) beats.removeAt(0)
    }
}

// ── Composables ─────────────────────────────────────────────────────────────

/**
 * Scrolling RR interval strip chart.
 *
 * @param rrIntervals  Raw RR intervals from the ViewModel (sliding window).
 * @param windowMs     Time window in ms (default 30 s).
 * @param colors       Colour scheme for the chart.
 * @param waitingText  Text shown before enough data arrives.
 * @param modifier     Standard Compose modifier (set height/width here).
 */
@Composable
fun RrIntervalChart(
    rrIntervals: List<Double>,
    modifier: Modifier = Modifier,
    windowMs: Double = DEFAULT_CHART_WINDOW_MS,
    colors: StripChartColors = StripChartColors(),
    waitingText: String = "awaiting heartbeats…"
) {
    val state = remember { RrStripChartState(windowMs) }
    var cursorTimeMs by remember { mutableDoubleStateOf(0.0) }

    val fingerprint = remember(rrIntervals) {
        if (rrIntervals.isEmpty()) 0L
        else rrIntervals.size.toLong() * 1_000_000L + rrIntervals.last().toLong()
    }
    LaunchedEffect(fingerprint) { state.ingest(rrIntervals, System.nanoTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                if (state.started) cursorTimeMs = (nanos - state.firstBeatNanos) / 1_000_000.0
            }
        }
    }

    StripChartShell(
        beats = state.beats,
        cursorTimeMs = cursorTimeMs,
        windowMs = windowMs,
        colors = colors,
        waitingText = waitingText,
        defaultMinY = 600.0,
        defaultMaxY = 1000.0,
        minYRange = 50.0,
        modifier = modifier
    )
}

/**
 * Scrolling RMSSD strip chart. Computes per-beat |ΔRR| from raw RR intervals
 * and scrolls on the same time axis as [RrIntervalChart].
 *
 * @param rrIntervals  Raw RR intervals from the ViewModel (same list as RR chart).
 * @param windowMs     Time window in ms (default 30 s).
 * @param colors       Colour scheme for the chart.
 * @param waitingText  Text shown before enough data arrives.
 * @param modifier     Standard Compose modifier.
 */
@Composable
fun RmssdChart(
    rrIntervals: List<Double>,
    modifier: Modifier = Modifier,
    windowMs: Double = DEFAULT_CHART_WINDOW_MS,
    colors: StripChartColors = StripChartColors(),
    waitingText: String = "awaiting RMSSD…"
) {
    val state = remember { RmssdStripChartState(windowMs) }
    var cursorTimeMs by remember { mutableDoubleStateOf(0.0) }

    val fingerprint = remember(rrIntervals) {
        if (rrIntervals.isEmpty()) 0L
        else rrIntervals.size.toLong() * 1_000_000L + rrIntervals.last().toLong()
    }
    LaunchedEffect(fingerprint) { state.ingest(rrIntervals, System.nanoTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                if (state.started) cursorTimeMs = (nanos - state.firstBeatNanos) / 1_000_000.0
            }
        }
    }

    StripChartShell(
        beats = state.beats,
        cursorTimeMs = cursorTimeMs,
        windowMs = windowMs,
        colors = colors,
        waitingText = waitingText,
        defaultMinY = 0.0,
        defaultMaxY = 80.0,
        minYRange = 10.0,
        modifier = modifier
    )
}

// ── Internal shell composable ───────────────────────────────────────────────

@Composable
private fun StripChartShell(
    beats: List<TimedBeat>,
    cursorTimeMs: Double,
    windowMs: Double,
    colors: StripChartColors,
    waitingText: String,
    defaultMinY: Double,
    defaultMaxY: Double,
    minYRange: Double,
    modifier: Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "chart_shimmer")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing), RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val targetMin = if (beats.isEmpty()) defaultMinY else beats.minOf { it.valueMs }
    val targetMax = if (beats.isEmpty()) defaultMaxY else beats.maxOf { it.valueMs }
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
        if (beats.size >= 2) {
            val snap = beats.toList()
            val cursor = cursorTimeMs
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                drawStripChart(
                    beats = snap,
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

private fun DrawScope.drawStripChart(
    beats: List<TimedBeat>,
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
    if (beats.size < 2) return
    val w = size.width
    val h = size.height
    val yRange = (paddedMax - paddedMin).coerceAtLeast(1.0)
    val windowStart = cursorTimeMs - windowMs

    fun xAt(t: Double) = ((t - windowStart) / windowMs * w).toFloat()
    fun yAt(v: Double) = h - ((v - paddedMin) / yRange * h).toFloat()

    // Keep all beats that fall within the visible window (left edge with 2 s look-behind for
    // smooth entry). There is intentionally NO upper-bound filter on cursorTimeMs: the cursor
    // is a wall-clock value that can lag behind cumulative RR time, which would cause beats
    // to be clipped before they reach the left edge of the screen.
    val visible = beats.filter { it.timeMs >= windowStart - 2000 }
    if (visible.size < 2) return
    val pts = visible.map { Offset(xAt(it.timeMs), yAt(it.valueMs)) }

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
