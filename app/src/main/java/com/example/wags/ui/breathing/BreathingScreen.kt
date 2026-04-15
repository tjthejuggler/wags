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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.common.RrIntervalChart
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.domain.usecase.breathing.ResonanceRateRecommender
import com.example.wags.ui.theme.*
import kotlin.math.roundToInt

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

// ── Coherence zone colors — greyscale ────────────────────────────────────────
private val CoherenceZoneRed   = Color(0xFF606060)   // dim grey  = low coherence
private val CoherenceZoneBlue  = Color(0xFF909090)   // mid grey  = medium coherence
private val CoherenceZoneGreen = Color(0xFFD0D0D0)   // light grey = high coherence
private val GoldAccent         = Color(0xFFD0D0D0)   // light grey (replaces gold)

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
    onNavigateToSession: (vibration: Boolean, duration: Int, infinity: Boolean, rate: Float) -> Unit = { _, _, _, _ -> },
    onNavigateToRateRecommendation: () -> Unit = {},
    viewModel: BreathingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"
    val context = LocalContext.current

    // Vibration toggle — persisted to SharedPreferences so it survives app restarts
    val prefs = remember { context.getSharedPreferences("apnea_prefs", android.content.Context.MODE_PRIVATE) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("breathing_vibration", false)) }

    // Restore last-used session duration from prefs so it survives app restarts
    LaunchedEffect(Unit) {
        val saved = prefs.getInt("breathing_duration_minutes", -1)
        if (saved > 0) viewModel.setSessionDuration(saved)
    }

    val isActive = state.sessionPhase != BreathingSessionPhase.IDLE &&
            state.sessionPhase != BreathingSessionPhase.COMPLETE

    SessionBackHandler(enabled = isActive) { navController.popBackStack() }
    KeepScreenOn(enabled = isActive)

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
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2, onClick = { navController.navigate(WagsRoutes.SETTINGS) })
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
        ) {
            // ── Advice Banner ───────────────────────────────────────────────
            AdviceBanner(section = AdviceSection.BREATHING)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Only the IDLE phase lives on this hub screen now.
            // PREPARING / BREATHING / COMPLETE all happen on ResonanceSessionScreen.
            BreathingPacerCircle(
                progress = state.pacerRadius,
                isInhaling = state.isInhaling,
                size = 200.dp
            )

            BreathingControls(
                rateBpm = state.breathingRateBpm,
                ieRatio = state.ieRatio,
                onRateChange = { viewModel.setBreathingRate(it) },
                onIeRatioChange = { viewModel.setIeRatio(it) }
            )

            // ── Recommended rate info + link to explanation ──────────────────
            if (state.bestRateBpm != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Suggested: %.2f BPM".format(state.bestRateBpm),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                "Based on ${ResonanceRateRecommender.LOOKBACK_DAYS}-day history",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        TextButton(onClick = onNavigateToRateRecommendation) {
                            Text("Why?", color = EcgCyan)
                        }
                    }
                }
            }

            // ── Duration controls ──────────────────────────────────────────
            DurationControls(
                durationMinutes = state.sessionDurationMinutes,
                infinityMode = state.infinityMode,
                onDurationChange = {
                    viewModel.setSessionDuration(it)
                    prefs.edit().putInt("breathing_duration_minutes", it).apply()
                },
                onInfinityToggle = { viewModel.setInfinityMode(it) }
            )

            // ── HR device gate ────────────────────────────────────────────────
            if (!state.isHrDeviceConnected) {
                HrRequiredBanner(
                    message = "Connect a Polar H10, Verity Sense, or pulse oximeter to start a session or assessment."
                )
            }

            // Start session row — button + vibration toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onNavigateToSession(vibrationEnabled, state.sessionDurationMinutes, state.infinityMode, state.breathingRateBpm) },
                    modifier = Modifier.weight(1f),
                    enabled = state.isHrDeviceConnected
                ) { Text("Start Session") }

                IconButton(
                    onClick = {
                        vibrationEnabled = !vibrationEnabled
                        prefs.edit().putBoolean("breathing_vibration", vibrationEnabled).apply()
                    }
                ) {
                    Text(
                        text = "〰",
                        color = if (vibrationEnabled) TextPrimary else TextDisabled,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            HorizontalDivider(color = SurfaceVariant)

            Button(
                onClick = onNavigateToRfAssessment,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isHrDeviceConnected
            ) {
                Text("RF Assessment")
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
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
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
                text = "%.2f".format(summary.meanCoherenceRatio),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
            Text(
                text = "mean coherence",
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

            // Zone threshold lines — greyscale
            val highY = h - (3f / maxVal * h).coerceIn(0f, h)
            val medY = h - (1f / maxVal * h).coerceIn(0f, h)
            drawLine(Color(0xFF505050), Offset(0f, highY), Offset(w, highY), strokeWidth = 1f)
            drawLine(Color(0xFF383838), Offset(0f, medY), Offset(w, medY), strokeWidth = 1f)

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
    // Rate: 4.00–7.00 BPM in 0.01 steps → 300 intervals = 299 steps
    // Ratio: 0.5–3.0 in 0.1 steps → 25 intervals = 24 steps
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Breathing Rate: ${String.format("%.2f", rateBpm)} BPM",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = rateBpm,
                onValueChange = { raw ->
                    // Snap to 0.01 precision
                    val snapped = (raw * 100).roundToInt() / 100f
                    onRateChange(snapped.coerceIn(4f, 7f))
                },
                valueRange = 4f..7f,
                steps = 299   // (7.00 - 4.00) / 0.01 - 1 = 299
            )
            Text(
                "I:E Ratio: 1:${String.format("%.1f", ieRatio)}",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = ieRatio,
                onValueChange = { raw ->
                    // Snap to 0.1 precision
                    val snapped = (raw * 10).roundToInt() / 10f
                    onIeRatioChange(snapped.coerceIn(0.5f, 3f))
                },
                valueRange = 0.5f..3f,
                steps = 24    // (3.0 - 0.5) / 0.1 - 1 = 24
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  HR REQUIRED BANNER
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun HrRequiredBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "HR Device Required",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

private fun coherenceColor(score: Float) = when {
    score >= 3f -> CoherenceGreen
    score >= 2f -> CoherenceYellow
    score >= 1f -> CoherenceOrange
    else -> CoherenceRed
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  DURATION CONTROLS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun DurationControls(
    durationMinutes: Int,
    infinityMode: Boolean,
    onDurationChange: (Int) -> Unit,
    onInfinityToggle: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (infinityMode) "Duration: ∞ (no limit)"
                           else "Duration: $durationMinutes min",
                    style = MaterialTheme.typography.bodyLarge
                )
                IconButton(onClick = { onInfinityToggle(!infinityMode) }) {
                    Text(
                        text = "∞",
                        color = if (infinityMode) EcgCyan else TextDisabled,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = if (infinityMode) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            if (!infinityMode) {
                Slider(
                    value = durationMinutes.toFloat(),
                    onValueChange = { onDurationChange(it.roundToInt()) },
                    valueRange = 1f..30f,
                    steps = 28  // 1 to 30 in 1-minute steps = 29 intervals = 28 steps
                )
            }
        }
    }
}
