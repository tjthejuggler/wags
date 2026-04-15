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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.RrIntervalChart
import com.example.wags.ui.common.RmssdChart
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.common.StripChartColors
import com.example.wags.ui.common.WagsFeedback
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.common.LiveSensorActionsCallback
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
private val RsGold      = Color(0xFFD0D0D0)   // light grey (replaces gold)

// Coherence zone colours — greyscale
private val RsZoneRed   = Color(0xFF606060)   // dim grey  = low coherence
private val RsZoneBlue  = Color(0xFF909090)   // mid grey  = medium coherence
private val RsZoneGreen = Color(0xFFD0D0D0)   // light grey = high coherence

// RMSSD chart — slightly lighter grey to distinguish from RR chart
private val RmssdColors = StripChartColors(
    lineColor = Color(0xFFB0B0B0),
    dotColor  = Color(0xFFD0D0D0),
    glowColor = Color(0xFF505050)
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
    onNavigateToSettings: () -> Unit = {},
    vibrationEnabled: Boolean = false,
    durationMinutes: Int = 5,
    infinityMode: Boolean = false,
    breathingRate: Float = 5.5f,
    viewModel: BreathingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deviceId = "PLACEHOLDER_H10_ID"
    val apneaPrefs = remember {
        context.getSharedPreferences("apnea_prefs", android.content.Context.MODE_PRIVATE)
    }

    val isActive = state.sessionPhase == BreathingSessionPhase.PREPARING ||
            state.sessionPhase == BreathingSessionPhase.BREATHING

    // Keep screen on during COMPLETE too, so it doesn't black out before navigation
    val keepScreenOn = isActive || state.sessionPhase == BreathingSessionPhase.COMPLETE

    // Back gesture/button → cancel without saving (after user confirms discard dialog)
    SessionBackHandler(enabled = isActive, onConfirm = { viewModel.cancelSession() })
    KeepScreenOn(enabled = keepScreenOn)

    // True once the phase has moved past IDLE (i.e. PREPARING or beyond).
    // We use this to distinguish "initial IDLE before session starts" from
    // "IDLE after session was stopped/completed".
    var sessionEverActive by remember { mutableStateOf(false) }

    // Play the end-chime once when the session completes
    var chimePlayed by remember { mutableStateOf(false) }

    LaunchedEffect(state.sessionPhase) {
        when (state.sessionPhase) {
            BreathingSessionPhase.PREPARING,
            BreathingSessionPhase.BREATHING -> sessionEverActive = true
            BreathingSessionPhase.COMPLETE  -> {
                sessionEverActive = true
                if (!chimePlayed) {
                    WagsFeedback.sessionEnd(context)
                    chimePlayed = true
                }
                // Set apnea prep type to RESONANCE so the next free hold is tagged correctly
                apneaPrefs.edit().putString("setting_prep_type", "RESONANCE").apply()
                // Do NOT auto-navigate — let the user review and leave manually
            }
            BreathingSessionPhase.IDLE -> {
                if (sessionEverActive) onNavigateBack()
            }
        }
    }

    // Kick off the session on first composition — apply the rate from the hub screen
    LaunchedEffect(Unit) {
        viewModel.setBreathingRate(breathingRate)
        viewModel.setSessionDuration(durationMinutes)
        viewModel.setInfinityMode(infinityMode)
        viewModel.startSession(deviceId)
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Resonance Breathing", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    // Back arrow → cancel without saving (shows discard dialog via SessionBackHandler)
                    IconButton(onClick = { viewModel.cancelSession() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Cancel & back",
                            tint = EcgCyan
                        )
                    }
                },
                actions = {
                    LiveSensorActionsCallback(onNavigateToSettings)
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
                        onCancel = { viewModel.cancelSession() }
                    )
                }

                BreathingSessionPhase.BREATHING -> {
                    // Vibration callback — only fires when toggle is on
                    val vibrationCallback: ((Boolean) -> Unit)? = if (vibrationEnabled) {
                        { inhaling ->
                            if (inhaling) WagsFeedback.breathInhale(context)
                            else WagsFeedback.breathExhale(context)
                        }
                    } else null

                    // ── Pacer circle ──────────────────────────────────────────
                    BreathingPacerCircle(
                        progress = state.pacerRadius,
                        isInhaling = state.isInhaling,
                        size = 200.dp,
                        onPhaseTransition = vibrationCallback
                    )

                    // ── Stats row (rate / time / coherence) ─────────────────
                    RsSessionStatsRow(
                        breathingRateBpm = state.breathingRateBpm,
                        elapsedSeconds = state.sessionElapsedSeconds,
                        coherenceRatio = state.liveCoherenceRatio
                    )

                    // ── Remaining time + progress bar (when timer is active) ─
                    if (!state.infinityMode) {
                        val totalSeconds = durationMinutes * 60
                        val progress = if (totalSeconds > 0)
                            (state.sessionElapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
                        else 0f

                        LinearProgressIndicator(
                            progress         = { progress },
                            modifier         = Modifier.fillMaxWidth(),
                            color            = TextSecondary,
                            trackColor       = SurfaceVariant
                        )

                        if (state.sessionRemainingSeconds > 0) {
                            Text(
                                text = "${rsFmtDuration(state.sessionRemainingSeconds)} remaining",
                                style = MaterialTheme.typography.bodySmall,
                                color = RsAsh,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

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

                    // ── End Session button (saves the session) ───────────────
                    OutlinedButton(
                        onClick = { viewModel.stopSession() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) { Text("End Session") }
                }

                BreathingSessionPhase.COMPLETE -> {
                    // Session finished — show summary and let the user leave manually
                    RsSessionCompleteContent(
                        elapsedSeconds = state.sessionElapsedSeconds,
                        coherenceRatio = state.liveCoherenceRatio,
                        breathingRateBpm = state.breathingRateBpm,
                        onDone = onNavigateBack
                    )
                }

                // IDLE before session starts or after cancel → pop back via LaunchedEffect
                BreathingSessionPhase.IDLE -> {
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
//  SESSION COMPLETE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsSessionCompleteContent(
    elapsedSeconds: Int,
    coherenceRatio: Float,
    breathingRateBpm: Float,
    onDone: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "SESSION COMPLETE",
            style = MaterialTheme.typography.titleMedium,
            color = RsBone,
            letterSpacing = 6.sp
        )

        // Summary card
        Card(
            colors = CardDefaults.cardColors(containerColor = RsGraphite),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RsStatCell(value = rsFmtDuration(elapsedSeconds), label = "DURATION", color = RsBone)
                RsStatCell(value = "%.1f".format(breathingRateBpm), label = "BREATHING RATE", color = RsGold)
                RsStatCell(value = "%.1f".format(coherenceRatio), label = "COHERENCE", color = EcgCyan)
            }
        }

        Text(
            "Session saved. Take your time.",
            style = MaterialTheme.typography.bodyMedium,
            color = RsAsh,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(0.6f),
            colors = ButtonDefaults.buttonColors(containerColor = RsGraphite, contentColor = RsBone)
        ) {
            Text("Done", letterSpacing = 2.sp)
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
//  SESSION STATS ROW
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun RsSessionStatsRow(breathingRateBpm: Float, elapsedSeconds: Int, coherenceRatio: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RsStatCell(value = "%.1f".format(breathingRateBpm), label = "BPM",       color = RsGold)
        RsStatCell(value = rsFmtDuration(elapsedSeconds),   label = "TIME",      color = RsBone)
        RsStatCell(value = "%.1f".format(coherenceRatio),   label = "COHERENCE", color = EcgCyan)
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
