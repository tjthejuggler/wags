package com.example.wags.ui.readiness

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.HrvMetrics
import com.example.wags.domain.model.ReadinessInterpretation
import com.example.wags.domain.model.ReadinessScore
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

// ── Monochrome palette (shared with MorningReadinessScreen) ───────────────────
private val Bone       = Color(0xFFE8E8E8)
private val Silver     = Color(0xFFB0B0B0)
private val Ash        = Color(0xFF707070)
private val Graphite   = Color(0xFF383838)
private val Charcoal   = Color(0xFF1C1C1C)
private val Ink        = Color(0xFF0A0A0A)
private val ChartLine  = Color(0xFFD0D0D0)
private val ChartDot   = Color(0xFFFFFFFF)
private val ChartGlow  = Color(0xFF909090)
private val ArcFill    = Color(0xFFCCCCCC)
private val ArcTrack   = Color(0xFF2A2A2A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessScreen(
    navController: NavController,
    onNavigateToHistory: () -> Unit,
    viewModel: ReadinessViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("HRV Readiness", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2)
                    TextButton(onClick = onNavigateToHistory) {
                        Text("History", color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.sessionState) {
                ReadinessSessionState.IDLE -> IdleContent(
                    onStart = { viewModel.startSession(deviceId, 120L) }
                )
                ReadinessSessionState.RECORDING -> RecordingContent(
                    state = state,
                    onCancel = { viewModel.cancelSession() }
                )
                ReadinessSessionState.PROCESSING -> ProcessingContent()
                ReadinessSessionState.COMPLETE -> CompleteContent(
                    state = state,
                    onReset = { viewModel.reset() }
                )
                ReadinessSessionState.ERROR -> ErrorContent(
                    message = state.errorMessage ?: "Unknown error",
                    onReset = { viewModel.reset() }
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  IDLE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "HRV Readiness",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("2-minute resting HRV measurement", style = MaterialTheme.typography.titleMedium, color = EcgCyan)
                Text("1. Ensure Polar H10 is connected and worn correctly", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text("2. Sit or lie down comfortably", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text("3. Breathe normally and stay still", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
        ) {
            Text("Start 2-Minute Session")
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  RECORDING — The main active screen (redesigned)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RecordingContent(state: ReadinessUiState, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Phase label ──
        Text(
            "RECORDING",
            style = MaterialTheme.typography.titleMedium,
            color = Silver,
            letterSpacing = 6.sp
        )

        // ── Countdown arc — shows elapsed (filled) vs remaining (track) ──
        MinimalistArcCountdown(
            remainingSeconds = state.remainingSeconds.toInt(),
            totalSeconds = state.sessionDurationSeconds.toInt()
        )

        Spacer(Modifier.height(4.dp))

        // ── Live metrics row ──
        HrvLiveMetricsRow(
            hrBpm = state.liveHr,
            rmssd = state.liveRmssd?.toDouble(),
            sdnn = state.liveSdnn?.toDouble(),
            rrCount = state.rrCount
        )

        Spacer(Modifier.height(4.dp))

        // ── Scrolling RR chart ──
        RrIntervalChart(
            rrIntervals = state.liveRrIntervals,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        )

        // ── Subtle instruction ──
        Text(
            "breathe normally · stay still",
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(4.dp))

        // ── Cancel button — minimal, unobtrusive ──
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(0.5f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ash),
            border = androidx.compose.foundation.BorderStroke(1.dp, Charcoal)
        ) {
            Text("Cancel", style = MaterialTheme.typography.bodySmall, letterSpacing = 2.sp)
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  PROCESSING
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun ProcessingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = EcgCyan, modifier = Modifier.size(64.dp))
        Text(
            "analyzing hrv…",
            style = MaterialTheme.typography.bodyMedium,
            color = Ash,
            letterSpacing = 2.sp
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  COMPLETE — Results display (redesigned)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CompleteContent(state: ReadinessUiState, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        state.readinessScore?.let { score ->
            ReadinessScoreDisplay(score = score)
        }
        state.hrvMetrics?.let { metrics ->
            HrvMetricsCard(metrics = metrics)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
        ) {
            Text("New Session")
        }
    }
}

@Composable
private fun ReadinessScoreDisplay(score: ReadinessScore) {
    // Interpretation brightness: OPTIMAL=bright, ELEVATED=mid, REDUCED=dim, LOW/OVERREACHING=dim
    val interpretationBrightness = when (score.interpretation) {
        ReadinessInterpretation.OPTIMAL     -> Bone
        ReadinessInterpretation.ELEVATED    -> Silver
        ReadinessInterpretation.REDUCED     -> Silver
        ReadinessInterpretation.LOW         -> Ash
        ReadinessInterpretation.OVERREACHING -> Ash
    }

    // Score brightness: high score = bright, low score = dim
    val scoreBrightness = when {
        score.score >= 80 -> Bone
        score.score >= 60 -> Silver
        else              -> Ash
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Graphite)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "READINESS",
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                letterSpacing = 4.sp
            )
            Text(
                text = score.score.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Thin,
                    fontSize = 80.sp,
                    letterSpacing = (-2).sp
                ),
                color = scoreBrightness
            )
            // Interpretation label
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Charcoal)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = score.interpretation.name.replace("_", " "),
                    style = MaterialTheme.typography.labelMedium,
                    color = interpretationBrightness,
                    letterSpacing = 3.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "z-score ${String.format("%+.2f", score.zScore)}",
                style = MaterialTheme.typography.bodySmall,
                color = Ash
            )
        }
    }
}

@Composable
private fun HrvMetricsCard(metrics: HrvMetrics) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Graphite)
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                "HRV METRICS",
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            HrvMetricRow("RMSSD",    "${String.format("%.1f", metrics.rmssdMs)} ms",  highlight = true)
            MetricDivider()
            HrvMetricRow("ln(RMSSD)", String.format("%.3f", metrics.lnRmssd),         highlight = true)
            MetricDivider()
            HrvMetricRow("SDNN",     "${String.format("%.1f", metrics.sdnnMs)} ms",   highlight = false)
            MetricDivider()
            HrvMetricRow("pNN50",    "${String.format("%.1f", metrics.pnn50Percent)} %", highlight = false)
            MetricDivider()
            HrvMetricRow("Samples",  metrics.sampleCount.toString(),                  highlight = false)
        }
    }
}

@Composable
private fun HrvMetricRow(label: String, value: String, highlight: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
            letterSpacing = 2.sp
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal
            ),
            color = if (highlight) Bone else Silver
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Charcoal)
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ERROR
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun ErrorContent(message: String, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Error", style = MaterialTheme.typography.headlineMedium, color = ReadinessRed)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
        Button(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
        ) {
            Text("Try Again")
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ✦  MINIMALIST ARC COUNTDOWN
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun MinimalistArcCountdown(remainingSeconds: Int, totalSeconds: Int) {
    val progress = if (totalSeconds > 0) remainingSeconds.toFloat() / totalSeconds.toFloat() else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "arc_progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "countdown_breathe")
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val tickAngle = 135f + animatedProgress * 270f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val padding = strokeWidth * 2
            val arcSize = size.minDimension - padding * 2

            drawArc(
                color = ArcTrack,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(padding, padding),
                size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            if (animatedProgress > 0f) {
                drawArc(
                    color = ArcFill,
                    startAngle = 135f,
                    sweepAngle = animatedProgress * 270f,
                    useCenter = false,
                    topLeft = Offset(padding, padding),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            if (animatedProgress > 0.01f) {
                val radius = arcSize / 2f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val angleRad = Math.toRadians(tickAngle.toDouble())
                val dotX = centerX + radius * cos(angleRad).toFloat()
                val dotY = centerY + radius * sin(angleRad).toFloat()
                drawCircle(
                    color = ChartDot,
                    radius = 4.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(breatheScale)
        ) {
            val minutes = remainingSeconds / 60
            val secs = remainingSeconds % 60
            Text(
                text = if (minutes > 0) String.format("%d:%02d", minutes, secs) else "$secs",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Thin,
                    fontSize = if (minutes > 0) 36.sp else 42.sp,
                    letterSpacing = 2.sp
                ),
                color = Bone
            )
            Text(
                text = if (minutes > 0) "min" else "sec",
                style = MaterialTheme.typography.bodySmall,
                color = Ash
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ✦  LIVE METRICS ROW
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun HrvLiveMetricsRow(
    hrBpm: Int?,
    rmssd: Double?,
    sdnn: Double?,
    rrCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Graphite)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricCell(
            value = hrBpm?.toString() ?: "—",
            label = "HR",
            unit = "bpm",
            highlight = true
        )
        ThinDivider()
        MetricCell(
            value = if (rmssd != null && rmssd > 0) String.format("%.0f", rmssd) else "—",
            label = "RMSSD",
            unit = "ms"
        )
        ThinDivider()
        MetricCell(
            value = if (sdnn != null && sdnn > 0) String.format("%.0f", sdnn) else "—",
            label = "SDNN",
            unit = "ms"
        )
        ThinDivider()
        MetricCell(
            value = rrCount.toString(),
            label = "BEATS",
            unit = ""
        )
    }
}

@Composable
private fun MetricCell(value: String, label: String, unit: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                fontSize = 20.sp
            ),
            color = if (highlight) Bone else Silver
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Ash,
                letterSpacing = 1.sp
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = Ash.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ThinDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(Charcoal)
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ✦  SCROLLING RR INTERVAL CHART
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RrIntervalChart(rrIntervals: List<Double>, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "chart_shimmer")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Ink, Charcoal.copy(alpha = 0.3f), Ink)
                )
            )
    ) {
        if (rrIntervals.size >= 2) {
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp)) {
                drawRrChart(rrIntervals, shimmerPhase)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "awaiting heartbeats…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

private fun DrawScope.drawRrChart(rrIntervals: List<Double>, shimmerPhase: Float) {
    val n = rrIntervals.size
    if (n < 2) return

    val w = size.width
    val h = size.height

    val minRr = rrIntervals.min()
    val maxRr = rrIntervals.max()
    val range = (maxRr - minRr).coerceAtLeast(50.0)
    val paddedMin = minRr - range * 0.15
    val paddedMax = maxRr + range * 0.15
    val yRange = paddedMax - paddedMin

    fun xAt(index: Int): Float = (index.toFloat() / (n - 1).toFloat()) * w
    fun yAt(value: Double): Float = h - ((value - paddedMin) / yRange * h).toFloat()

    val points = rrIntervals.mapIndexed { i, rr -> Offset(xAt(i), yAt(rr)) }

    val glowPath = Path()
    buildCatmullRomPath(glowPath, points)
    drawPath(
        path = glowPath,
        color = ChartGlow.copy(alpha = 0.15f),
        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    val mainPath = Path()
    buildCatmullRomPath(mainPath, points)
    drawPath(
        path = mainPath,
        color = ChartLine,
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    points.forEachIndexed { i, pt ->
        val fadeAlpha = (i.toFloat() / (n * 0.2f)).coerceIn(0f, 1f)
        val shimmerDist = kotlin.math.abs(i.toFloat() / n - shimmerPhase)
        val shimmerBoost = (1f - (shimmerDist * 4f).coerceIn(0f, 1f)) * 0.4f
        val dotAlpha = (0.3f + shimmerBoost) * fadeAlpha
        val dotRadius = (1.8f + shimmerBoost * 2f).dp.toPx()
        drawCircle(color = ChartDot.copy(alpha = dotAlpha), radius = dotRadius, center = pt)
    }

    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Ink, Ink.copy(alpha = 0f)),
            startX = 0f,
            endX = w * 0.15f
        ),
        size = size
    )
}

private fun buildCatmullRomPath(path: Path, points: List<Offset>) {
    if (points.size < 2) return
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) { path.lineTo(points[1].x, points[1].y); return }

    val extended = buildList {
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
    val segments = 12

    for (i in 1 until extended.size - 2) {
        val p0 = extended[i - 1]; val p1 = extended[i]
        val p2 = extended[i + 1]; val p3 = extended[i + 2]
        for (s in 1..segments) {
            val t = s.toFloat() / segments
            val t2 = t * t; val t3 = t2 * t
            val x = tension * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3)
            val y = tension * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)
            path.lineTo(x, y)
        }
    }
}

private fun interpretationColor(interpretation: ReadinessInterpretation) = when (interpretation) {
    ReadinessInterpretation.OPTIMAL      -> ReadinessGreen
    ReadinessInterpretation.ELEVATED     -> ReadinessBlue
    ReadinessInterpretation.REDUCED      -> ReadinessOrange
    ReadinessInterpretation.LOW          -> ReadinessRed
    ReadinessInterpretation.OVERREACHING -> ReadinessRed
}
