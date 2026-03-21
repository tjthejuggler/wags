package com.example.wags.ui.breathing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.ui.common.RrIntervalChart
import com.example.wags.ui.common.RmssdChart
import com.example.wags.ui.common.StripChartColors
import com.example.wags.ui.theme.*

// ── Monochrome palette ────────────────────────────────────────────────────────
private val RsBone      = Color(0xFFE8E8E8)
private val RsSilver    = Color(0xFFB0B0B0)
private val RsAsh       = Color(0xFF707070)
private val RsGraphite  = Color(0xFF383838)
private val RsCharcoal  = Color(0xFF1C1C1C)
private val RsInk       = Color(0xFF0A0A0A)
private val RsChartLine = Color(0xFFD0D0D0)
private val RsChartDot  = Color(0xFFFFFFFF)
private val RsChartGlow = Color(0xFF909090)
private val RsGold      = Color(0xFFFFD700)

// Coherence zone colours
private val RsZoneRed   = Color(0xFFE53935)
private val RsZoneBlue  = Color(0xFF42A5F5)
private val RsZoneGreen = Color(0xFF66BB6A)

// RMSSD chart accent — warm amber so it's visually distinct from the RR chart
private val RmssdColors = StripChartColors(
    lineColor = Color(0xFFFFB300),
    dotColor  = Color(0xFFFFB300),
    glowColor = Color(0xFF7A5500)
)
private const val RS_CHART_WINDOW_MS = 20_000.0

private fun rsCoherenceZoneColor(ratio: Float): Color = when {
    ratio >= 3f -> RsZoneGreen
    ratio >= 1f -> RsZoneBlue
    else        -> RsZoneRed
}

private fun rsCoherenceZoneLabel(ratio: Float): String = when {
    ratio >= 3f -> "HIGH COHERENCE"
    ratio >= 1f -> "MEDIUM COHERENCE"
    else        -> "LOW COHERENCE"
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  SCREEN
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResonanceSessionScreen(
    onNavigateBack: () -> Unit,
    viewModel: BreathingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    // True once the phase has moved past IDLE (i.e. PREPARING or beyond).
    // We use this to distinguish "initial IDLE before session starts" from
    // "IDLE after session was stopped/completed".
    var sessionEverActive by remember { mutableStateOf(false) }

    LaunchedEffect(state.sessionPhase) {
        when (state.sessionPhase) {
            BreathingSessionPhase.PREPARING,
            BreathingSessionPhase.BREATHING -> sessionEverActive = true
            BreathingSessionPhase.COMPLETE  -> {
                sessionEverActive = true
                onNavigateBack()
            }
            BreathingSessionPhase.IDLE -> {
                if (sessionEverActive) onNavigateBack()
            }
        }
    }

    // Kick off the session on first composition
    LaunchedEffect(Unit) {
        viewModel.startSession(deviceId)
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Resonance Breathing", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopSession()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Stop & back",
                            tint = EcgCyan
                        )
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.sessionPhase) {
                BreathingSessionPhase.PREPARING -> {
                    RsPreparationContent(
                        countdownSeconds = state.prepCountdownSeconds,
                        breathingRateBpm = state.breathingRateBpm,
                        onCancel = {
                            viewModel.stopSession()
                            onNavigateBack()
                        }
                    )
                }

                BreathingSessionPhase.BREATHING -> {
                    // ── Pacer circle ──────────────────────────────────────────
                    BreathingPacerCircle(
                        progress = state.pacerRadius,
                        isInhaling = state.isInhaling,
                        size = 200.dp
                    )

                    // ── Coherence zone traffic light ──────────────────────────
                    RsCoherenceZoneIndicator(coherenceRatio = state.liveCoherenceRatio)

                    // ── Stats row (points / time / coherence) ─────────────────
                    RsSessionStatsRow(
                        points = state.sessionPoints,
                        elapsedSeconds = state.sessionElapsedSeconds,
                        coherenceRatio = state.liveCoherenceRatio
                    )

                    // ── Live HRV metrics ──────────────────────────────────────
                    RsHrvMetricsRow(
                        hrBpm  = state.liveHr,
                        rmssd  = state.liveRmssd?.toDouble(),
                        sdnn   = state.liveSdnn?.toDouble(),
                        rrCount = state.rrCount
                    )

                    // ── RR interval scrolling chart (top) ─────────────────────
                    RsChartLabel("RR INTERVAL")
                    RrIntervalChart(
                        rrIntervals = state.liveRrIntervals,
                        windowMs = RS_CHART_WINDOW_MS,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )

                    // ── RMSSD scrolling chart (bottom) ────────────────────────
                    RsChartLabel("RMSSD")
                    RmssdChart(
                        rrIntervals = state.liveRrIntervals,
                        windowMs = RS_CHART_WINDOW_MS,
                        colors = RmssdColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )

                    // ── Stop button ───────────────────────────────────────────
                    OutlinedButton(
                        onClick = {
                            viewModel.stopSession()
                            onNavigateBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Stop Session") }
                }

                // IDLE / COMPLETE handled by LaunchedEffect → pop back
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = EcgCyan)
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  PREPARATION COUNTDOWN
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsPreparationContent(
    countdownSeconds: Int,
    breathingRateBpm: Float,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "GET READY",
            style = MaterialTheme.typography.titleMedium,
            color = RsSilver,
            letterSpacing = 6.sp
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(80.dp))
                .background(RsGraphite)
        ) {
            Text(
                text = "$countdownSeconds",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Thin,
                    fontSize = 72.sp
                ),
                color = RsBone,
                modifier = Modifier.align(Alignment.Center)
            )
        }

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
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RsAsh),
            border = BorderStroke(1.dp, RsCharcoal)
        ) {
            Text("Cancel", style = MaterialTheme.typography.bodySmall, letterSpacing = 2.sp)
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  COHERENCE ZONE INDICATOR
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsCoherenceZoneIndicator(coherenceRatio: Float) {
    val zoneColor by animateColorAsState(
        targetValue = rsCoherenceZoneColor(coherenceRatio),
        animationSpec = tween(durationMillis = 1000),
        label = "rs_zone_color"
    )
    val label = rsCoherenceZoneLabel(coherenceRatio)

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
//  SESSION STATS ROW
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsSessionStatsRow(points: Float, elapsedSeconds: Int, coherenceRatio: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RsStatCell(value = "%.0f".format(points),          label = "POINTS",    color = RsGold)
        RsStatCell(value = rsFmtDuration(elapsedSeconds),  label = "TIME",      color = RsBone)
        RsStatCell(value = "%.1f".format(coherenceRatio),  label = "COHERENCE", color = EcgCyan)
    }
}

@Composable
private fun RsStatCell(value: String, label: String, color: Color) {
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
            color = RsAsh,
            letterSpacing = 1.sp
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  LIVE HRV METRICS ROW
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsHrvMetricsRow(hrBpm: Int?, rmssd: Double?, sdnn: Double?, rrCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RsGraphite)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RsMetricCell(value = hrBpm?.toString() ?: "—",                                  label = "HR",    unit = "bpm",  highlight = true)
        RsThinDivider()
        RsMetricCell(value = if (rmssd != null && rmssd > 0) "%.0f".format(rmssd) else "—", label = "RMSSD", unit = "ms")
        RsThinDivider()
        RsMetricCell(value = if (sdnn  != null && sdnn  > 0) "%.0f".format(sdnn)  else "—", label = "SDNN",  unit = "ms")
        RsThinDivider()
        RsMetricCell(value = rrCount.toString(),                                         label = "BEATS", unit = "")
    }
}

@Composable
private fun RsMetricCell(value: String, label: String, unit: String, highlight: Boolean = false) {
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
            color = if (highlight) RsBone else RsSilver
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = RsAsh,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = RsAsh.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RsThinDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(RsCharcoal)
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  CHART LABEL
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsChartLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = RsAsh,
        letterSpacing = 2.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp)
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  HELPERS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private fun rsFmtDuration(seconds: Int): String {
    val m = seconds / 60; val s = seconds % 60
    return "%d:%02d".format(m, s)
}
