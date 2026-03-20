package com.example.wags.ui.breathing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.theme.*

// ── Monochrome palette (shared with ReadinessScreen) ──────────────────────────
private val Bone       = Color(0xFFE8E8E8)
private val Silver     = Color(0xFFB0B0B0)
private val Ash        = Color(0xFF707070)
private val Graphite   = Color(0xFF383838)
private val Charcoal   = Color(0xFF1C1C1C)
private val Ink        = Color(0xFF0A0A0A)
private val ChartLine  = Color(0xFFD0D0D0)
private val ChartDot   = Color(0xFFFFFFFF)
private val ChartGlow  = Color(0xFF909090)

// ── Coherence zone colors ─────────────────────────────────────────────────────
private val CoherenceZoneRed = Color(0xFFE53935)
private val CoherenceZoneBlue = Color(0xFF42A5F5)
private val CoherenceZoneGreen = Color(0xFF66BB6A)
private val GoldAccent = Color(0xFFFFD700)

private fun coherenceZoneColor(ratio: Float): Color = when {
    ratio >= 3f -> CoherenceZoneGreen
    ratio >= 1f -> CoherenceZoneBlue
    else -> CoherenceZoneRed
}

private fun coherenceZoneLabel(ratio: Float): String = when {
    ratio >= 3f -> "HIGH COHERENCE"
    ratio >= 1f -> "MEDIUM COHERENCE"
    else -> "LOW COHERENCE"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathingScreen(
    navController: NavController,
    onNavigateToRfAssessment: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    viewModel: BreathingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Resonance Breathing", style = MaterialTheme.typography.titleMedium) },
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.sessionPhase) {
                BreathingSessionPhase.IDLE -> {
                    // Breathing pacer circle (static preview)
                    BreathingPacerCircle(
                        progress = state.pacerRadius,
                        isInhaling = state.isInhaling,
                        size = 200.dp
                    )

                    // Coherence score
                    CoherenceDisplay(score = state.coherenceScore)

                    // Rate & ratio controls
                    BreathingControls(
                        rateBpm = state.breathingRateBpm,
                        ieRatio = state.ieRatio,
                        onRateChange = { viewModel.setBreathingRate(it) },
                        onIeRatioChange = { viewModel.setIeRatio(it) }
                    )

                    // Start session button
                    Button(
                        onClick = { viewModel.startSession(deviceId) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start Session") }

                    HorizontalDivider(color = SurfaceVariant)

                    // RF Assessment navigation
                    Button(
                        onClick = onNavigateToRfAssessment,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("RF Assessment")
                    }
                }

                BreathingSessionPhase.PREPARING -> {
                    PreparationContent(
                        countdownSeconds = state.prepCountdownSeconds,
                        breathingRateBpm = state.breathingRateBpm,
                        onCancel = { viewModel.stopSession() }
                    )
                }

                BreathingSessionPhase.BREATHING -> {
                    // Animated breathing pacer circle
                    BreathingPacerCircle(
                        progress = state.pacerRadius,
                        isInhaling = state.isInhaling
                    )

                    // Coherence Zone Traffic Light
                    CoherenceZoneIndicator(
                        coherenceRatio = state.liveCoherenceRatio
                    )

                    // Session points & timer row
                    SessionStatsRow(
                        points = state.sessionPoints,
                        elapsedSeconds = state.sessionElapsedSeconds,
                        coherenceRatio = state.liveCoherenceRatio
                    )

                    // Live HRV metrics row
                    HrvLiveMetricsRow(
                        hrBpm = state.liveHr,
                        rmssd = state.liveRmssd?.toDouble(),
                        sdnn = state.liveSdnn?.toDouble(),
                        rrCount = state.rrCount
                    )

                    // Coherence over time mini-chart
                    if (state.coherenceHistory.size >= 2) {
                        CoherenceHistoryChart(
                            history = state.coherenceHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                        )
                    }

                    // Scrolling RR chart
                    RrIntervalChart(
                        rrIntervals = state.liveRrIntervals,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )

                    // Stop session button
                    OutlinedButton(
                        onClick = { viewModel.stopSession() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Stop Session") }
                }

                BreathingSessionPhase.COMPLETE -> {
                    SessionCompleteContent(
                        summary = state.sessionSummary,
                        onDismiss = { viewModel.dismissSessionComplete() }
                    )
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  SESSION COMPLETE CONTENT (inline in BreathingScreen)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun SessionCompleteContent(
    summary: SessionSummary?,
    onDismiss: () -> Unit
) {
    if (summary == null) {
        Text("No session data available.", color = TextSecondary)
        Button(onClick = onDismiss) { Text("Back") }
        return
    }

    // Hero card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2818)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SESSION COMPLETE",
                style = MaterialTheme.typography.labelMedium,
                color = GoldAccent,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%.0f".format(summary.totalPoints),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
            Text(
                text = "points earned",
                style = MaterialTheme.typography.titleMedium,
                color = Silver
            )
            Text(
                text = "%.1f BPM  •  %s".format(
                    summary.breathingRateBpm,
                    formatDuration(summary.durationSeconds)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Ash
            )
        }
    }

    // Coherence zone breakdown
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Coherence Zone Breakdown",
                style = MaterialTheme.typography.titleSmall,
                color = Silver,
                letterSpacing = 2.sp
            )
            val totalSec = (summary.timeInHighCoherence + summary.timeInMediumCoherence + summary.timeInLowCoherence)
                .coerceAtLeast(1)
            ZoneBar(
                label = "HIGH",
                seconds = summary.timeInHighCoherence,
                totalSeconds = totalSec,
                color = CoherenceZoneGreen
            )
            ZoneBar(
                label = "MEDIUM",
                seconds = summary.timeInMediumCoherence,
                totalSeconds = totalSec,
                color = CoherenceZoneBlue
            )
            ZoneBar(
                label = "LOW",
                seconds = summary.timeInLowCoherence,
                totalSeconds = totalSec,
                color = CoherenceZoneRed
            )
        }
    }

    // Coherence over time chart
    if (summary.coherenceHistory.size >= 2) {
        Text(
            "Coherence Over Time",
            style = MaterialTheme.typography.titleSmall,
            color = Silver,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        CoherenceHistoryChart(
            history = summary.coherenceHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
    }

    // Metrics table
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SessionMetricRow("Duration", formatDuration(summary.durationSeconds))
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionMetricRow("Total Beats", "${summary.totalBeats}")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionMetricRow("Mean Coherence", "%.1f".format(summary.meanCoherenceRatio))
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionMetricRow("Max Coherence", "%.1f".format(summary.maxCoherenceRatio))
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionMetricRow("Mean RMSSD", "%.1f ms".format(summary.meanRmssdMs))
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionMetricRow("Mean SDNN", "%.1f ms".format(summary.meanSdnnMs))
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionMetricRow("Artifact %", "%.1f%%".format(summary.artifactPercent))
        }
    }

    // Done button
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
    ) {
        Text("Done")
    }
}

@Composable
private fun ZoneBar(label: String, seconds: Int, totalSeconds: Int, color: Color) {
    val fraction = seconds.toFloat() / totalSeconds.coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.width(60.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Graphite)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.7f))
            )
        }
        Text(
            text = formatDuration(seconds),
            style = MaterialTheme.typography.labelSmall,
            color = Ash,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SessionMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = EcgCyan,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  COHERENCE ZONE INDICATOR (Traffic Light)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CoherenceZoneIndicator(coherenceRatio: Float) {
    val zoneColor by animateColorAsState(
        targetValue = coherenceZoneColor(coherenceRatio),
        animationSpec = tween(durationMillis = 1000),
        label = "zone_color"
    )
    val label = coherenceZoneLabel(coherenceRatio)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = zoneColor.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(zoneColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = zoneColor,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Ratio: %.1f".format(coherenceRatio),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  SESSION STATS ROW (points, timer, coherence)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun SessionStatsRow(points: Float, elapsedSeconds: Int, coherenceRatio: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SessionStatCell(value = "%.0f".format(points), label = "POINTS", color = GoldAccent)
        SessionStatCell(value = formatDuration(elapsedSeconds), label = "TIME", color = Bone)
        SessionStatCell(value = "%.1f".format(coherenceRatio), label = "COHERENCE", color = EcgCyan)
    }
}

@Composable
private fun SessionStatCell(value: String, label: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 60.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Ash,
            letterSpacing = 1.sp
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  COHERENCE HISTORY MINI-CHART
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CoherenceHistoryChart(history: List<Float>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Ink, Charcoal.copy(alpha = 0.3f), Ink)))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (history.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            val maxVal = history.max().coerceAtLeast(1f)

            // Zone threshold lines
            val highY = h - (3f / maxVal * h).coerceIn(0f, h)
            val medY = h - (1f / maxVal * h).coerceIn(0f, h)
            drawLine(CoherenceZoneGreen.copy(alpha = 0.3f), Offset(0f, highY), Offset(w, highY), strokeWidth = 1f)
            drawLine(CoherenceZoneBlue.copy(alpha = 0.3f), Offset(0f, medY), Offset(w, medY), strokeWidth = 1f)

            // Draw coherence line
            val path = Path()
            history.forEachIndexed { idx, value ->
                val x = idx.toFloat() / (history.size - 1) * w
                val y = h - (value / maxVal * h).coerceIn(0f, h)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                EcgCyan,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Dots at each sample
            history.forEachIndexed { idx, value ->
                val x = idx.toFloat() / (history.size - 1) * w
                val y = h - (value / maxVal * h).coerceIn(0f, h)
                val dotColor = when {
                    value >= 3f -> CoherenceZoneGreen
                    value >= 1f -> CoherenceZoneBlue
                    else -> CoherenceZoneRed
                }
                drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(x, y))
            }
        }

        // Label
        Text(
            text = "Coherence Ratio Over Time",
            style = MaterialTheme.typography.labelSmall,
            color = Ash,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  PREPARATION COUNTDOWN
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun PreparationContent(
    countdownSeconds: Int,
    breathingRateBpm: Float,
    onCancel: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "prep_breathe")
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "GET READY",
            style = MaterialTheme.typography.titleMedium,
            color = Silver,
            letterSpacing = 6.sp
        )

        // Large countdown number
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(80.dp))
                .background(Graphite)
        ) {
            Text(
                text = "$countdownSeconds",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Thin,
                    fontSize = 72.sp
                ),
                color = Bone,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Instruction: we will start with an inhale
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Starting with INHALE",
                    style = MaterialTheme.typography.titleMedium,
                    color = PacerInhale,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Breathe normally until the countdown ends.\n" +
                        "Rate: ${String.format("%.1f", breathingRateBpm)} BPM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(16.dp))

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
//  COHERENCE DISPLAY
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CoherenceDisplay(score: Float) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Coherence Score", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format("%.1f", score),
                style = MaterialTheme.typography.headlineMedium,
                color = coherenceColor(score)
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  BREATHING CONTROLS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun BreathingControls(
    rateBpm: Float,
    ieRatio: Float,
    onRateChange: (Float) -> Unit,
    onIeRatioChange: (Float) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Breathing Rate: ${String.format("%.1f", rateBpm)} BPM",
                style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = rateBpm,
                onValueChange = onRateChange,
                valueRange = 4f..7f,
                steps = 11
            )
            Text("I:E Ratio: 1:${String.format("%.1f", ieRatio)}",
                style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = ieRatio,
                onValueChange = onIeRatioChange,
                valueRange = 0.5f..3f,
                steps = 9
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  LIVE HRV METRICS ROW (same style as ReadinessScreen)
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 48.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                fontSize = 20.sp
            ),
            color = if (highlight) Bone else Silver
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = Ash,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ash.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
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
//  TIME-BASED STRIP CHART FOR RR INTERVALS
//  (Same smooth Catmull-Rom chart as ReadinessScreen)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private const val CHART_WINDOW_MS = 30_000.0

private data class TimedBeat(val timeMs: Double, val valueMs: Double)

@Stable
private class StripChartState {
    val beats = mutableStateListOf<TimedBeat>()
    var cumulativeTimeMs by mutableDoubleStateOf(0.0)
        private set
    var firstBeatNanos: Long = 0L
        private set
    var started by mutableStateOf(false)
        private set
    private var lastSourceSize = 0
    private var lastSourceTail = 0.0

    fun ingest(source: List<Double>, nowNanos: Long) {
        if (source.isEmpty()) return
        if (!started) {
            started = true
            firstBeatNanos = nowNanos
        }
        val newBeatsCount: Int
        if (beats.isEmpty()) {
            newBeatsCount = source.size
        } else {
            val lastKnownValue = lastSourceTail
            var overlapIndex = -1
            for (i in source.size - 1 downTo 0) {
                if (source[i] == lastKnownValue) {
                    overlapIndex = i
                    break
                }
            }
            newBeatsCount = if (overlapIndex >= 0) {
                source.size - overlapIndex - 1
            } else {
                source.size
            }
        }
        if (newBeatsCount <= 0) {
            lastSourceSize = source.size
            lastSourceTail = source.last()
            return
        }
        val startIdx = source.size - newBeatsCount
        for (i in startIdx until source.size) {
            val rrMs = source[i]
            if (beats.isNotEmpty()) {
                cumulativeTimeMs += rrMs
            }
            beats.add(TimedBeat(timeMs = cumulativeTimeMs, valueMs = rrMs))
        }
        lastSourceSize = source.size
        lastSourceTail = source.last()
        val cutoff = cumulativeTimeMs - CHART_WINDOW_MS * 2
        while (beats.size > 2 && beats.first().timeMs < cutoff) {
            beats.removeAt(0)
        }
    }
}

@Composable
private fun RrIntervalChart(rrIntervals: List<Double>, modifier: Modifier = Modifier) {
    val state = remember { StripChartState() }
    var cursorTimeMs by remember { mutableDoubleStateOf(0.0) }

    val sourceFingerprint = remember(rrIntervals) {
        if (rrIntervals.isEmpty()) 0L
        else rrIntervals.size.toLong() * 1_000_000L + rrIntervals.last().toLong()
    }
    LaunchedEffect(sourceFingerprint) {
        state.ingest(rrIntervals, System.nanoTime())
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                if (state.started) {
                    cursorTimeMs = (nanos - state.firstBeatNanos) / 1_000_000.0
                }
            }
        }
    }

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

    val beats = state.beats
    val targetMinRr = if (beats.isEmpty()) 600.0 else beats.minOf { it.valueMs }
    val targetMaxRr = if (beats.isEmpty()) 1000.0 else beats.maxOf { it.valueMs }
    val targetRange = (targetMaxRr - targetMinRr).coerceAtLeast(50.0)
    val targetPaddedMin = targetMinRr - targetRange * 0.15
    val targetPaddedMax = targetMaxRr + targetRange * 0.15

    val animatedPaddedMin by animateFloatAsState(
        targetValue = targetPaddedMin.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "y_min"
    )
    val animatedPaddedMax by animateFloatAsState(
        targetValue = targetPaddedMax.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "y_max"
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
        if (beats.size >= 2) {
            val beatSnapshot = beats.toList()
            val cursor = cursorTimeMs
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp)) {
                drawStripChart(
                    beats = beatSnapshot,
                    cursorTimeMs = cursor,
                    shimmerPhase = shimmerPhase,
                    paddedMin = animatedPaddedMin.toDouble(),
                    paddedMax = animatedPaddedMax.toDouble()
                )
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

private fun DrawScope.drawStripChart(
    beats: List<TimedBeat>,
    cursorTimeMs: Double,
    shimmerPhase: Float,
    paddedMin: Double,
    paddedMax: Double
) {
    if (beats.size < 2) return
    val w = size.width
    val h = size.height
    val yRange = (paddedMax - paddedMin).coerceAtLeast(1.0)
    val windowEnd = cursorTimeMs
    val windowStart = cursorTimeMs - CHART_WINDOW_MS

    fun xAt(timeMs: Double): Float =
        ((timeMs - windowStart) / CHART_WINDOW_MS * w).toFloat()
    fun yAt(valueMs: Double): Float =
        h - ((valueMs - paddedMin) / yRange * h).toFloat()

    val visibleBeats = beats.filter { it.timeMs >= windowStart - 2000 && it.timeMs <= windowEnd + 2000 }
    if (visibleBeats.size < 2) return
    val points = visibleBeats.map { Offset(xAt(it.timeMs), yAt(it.valueMs)) }

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

    points.forEachIndexed { _, pt ->
        if (pt.x < -10f || pt.x > w + 10f) return@forEachIndexed
        val normalizedX = (pt.x / w).coerceIn(0f, 1f)
        val fadeAlpha = if (normalizedX < 0.15f) normalizedX / 0.15f else 1f
        val shimmerDist = kotlin.math.abs(normalizedX - shimmerPhase)
        val shimmerBoost = (1f - (shimmerDist * 4f).coerceIn(0f, 1f)) * 0.4f
        val dotAlpha = (0.3f + shimmerBoost) * fadeAlpha
        val dotRadius = (1.8f + shimmerBoost * 2f).dp.toPx()
        drawCircle(color = ChartDot.copy(alpha = dotAlpha), radius = dotRadius, center = pt)
    }

    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Ink, Ink.copy(alpha = 0f)),
            startX = 0f,
            endX = w * 0.12f
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

private fun coherenceColor(score: Float) = when {
    score >= 3f -> CoherenceGreen
    score >= 2f -> CoherenceYellow
    score >= 1f -> CoherenceOrange
    else -> CoherenceRed
}
