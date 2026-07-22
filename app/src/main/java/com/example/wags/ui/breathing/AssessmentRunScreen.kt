package com.example.wags.ui.breathing

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.breathing.RfProtocol
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.RrIntervalChart
import com.example.wags.ui.common.RmssdChart
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.common.StripChartColors
import com.example.wags.ui.common.WagsFeedback
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.common.BackgroundLineChart
import com.example.wags.ui.theme.*

// ── Monochrome palette ────────────────────────────────────────────────────────
private val AsmBone      = Color(0xFFE8E8E8)
private val AsmSilver    = Color(0xFFB0B0B0)
private val AsmAsh       = Color(0xFF707070)
private val AsmGraphite  = Color(0xFF383838)
private val AsmCharcoal  = Color(0xFF1C1C1C)
private val AsmInk       = Color(0xFF0A0A0A)
private val AsmGold      = Color(0xFFD0D0D0)   // light grey (replaces gold)
private val AsmZoneBlue  = Color(0xFF909090)   // mid grey  = medium coherence

// RMSSD chart — slightly lighter grey to distinguish from RR chart
private val AsmRmssdColors = StripChartColors(
    lineColor = Color(0xFFB0B0B0),
    dotColor  = Color(0xFFD0D0D0),
    glowColor = Color(0xFF505050)
)
private const val ASM_CHART_WINDOW_MS = 20_000.0

// ---------------------------------------------------------------------------
// Protocol display names
// ---------------------------------------------------------------------------

private fun RfProtocol.displayName(): String = when (this) {
    RfProtocol.EXPRESS        -> "Express Sweep"
    RfProtocol.STANDARD      -> "Standard Sweep"
    RfProtocol.DEEP          -> "Deep Calibration"
    RfProtocol.TARGETED      -> "Targeted Sweep"
    RfProtocol.CONTINUOUS    -> "Continuous Sweep"
    RfProtocol.SLIDING_WINDOW -> "Sliding Window"
    RfProtocol.CUSTOM        -> "Custom Assessment"
    RfProtocol.BEST_RATES    -> "Best Rates"
}

private fun RfProtocol.isStepped(): Boolean = this != RfProtocol.SLIDING_WINDOW

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentRunScreen(
    protocol: RfProtocol,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onSessionComplete: (sessionId: Long) -> Unit,
    vibrationEnabled: Boolean = false,
    colorsEnabled: Boolean = false,
    customDurationMinutes: Int = 0,
    initialPosture: String = "LAYING",
    viewModel: AssessmentRunViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    // Set initial posture from navigation parameter
    LaunchedEffect(Unit) {
        try {
            val posture = com.example.wags.domain.model.Posture.valueOf(initialPosture)
            viewModel.setPosture(posture)
        } catch (e: Exception) {
            // If posture string is invalid, keep default LAYING
        }
    }

    val isActive = uiState.phase != "IDLE" && uiState.phase != "COMPLETE"

    // Keep screen on during COMPLETE too so the user can review results
    val keepScreenOn = isActive || uiState.phase == "COMPLETE"

    SessionBackHandler(enabled = isActive, onConfirm = onNavigateBack)
    KeepScreenOn(enabled = keepScreenOn)

    // Navigate once when complete — play end-of-session sound
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            WagsFeedback.sessionEnd(context)

            // Set apnea prep type to RESONANCE so the next free hold is tagged correctly
            val apneaPrefs = context.getSharedPreferences("apnea_prefs", android.content.Context.MODE_PRIVATE)
            apneaPrefs.edit().putString("setting_prep_type", "RESONANCE").apply()

            val id = uiState.sessionId ?: return@LaunchedEffect
            onSessionComplete(id)
        }
    }

    // Fire longer vibration when "breathe naturally" phase starts
    LaunchedEffect(uiState.phase) {
        if (vibrationEnabled && (uiState.phase == "BASELINE" || uiState.phase == "BREATHE NATURALLY")) {
            WagsFeedback.breathNaturallyStart(context)
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(protocol.displayName()) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Cancel"
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
        // Vibration callback — only fires when toggle is on
        val vibrationCallback: ((Boolean) -> Unit)? = if (vibrationEnabled) {
            { inhaling ->
                if (inhaling) WagsFeedback.breathInhale(context)
                else WagsFeedback.breathExhale(context)
            }
        } else null

        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            // ── Landscape: breathing circle centered background, charts on top ──
            Box(modifier = Modifier.fillMaxSize()) {
                // Background breathing circle (drawn first, so behind everything)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(280.dp)
                        .align(Alignment.Center)
                ) {
                    val overlayLabel = when {
                        uiState.phase == "BASELINE" || uiState.phase == "BREATHE NATURALLY" -> uiState.phase
                        else -> null
                    }
                    BreathingPacerCircle(
                        progress = uiState.refWave,
                        isInhaling = uiState.isInhaling,
                        size = 280.dp,
                        overlayLabel = if (protocol.isStepped()) overlayLabel else null,
                        useColors = colorsEnabled,
                        breathCycleCount = uiState.breathCycleCount,
                        onPhaseTransition = vibrationCallback
                    )
                }

                // Foreground content (drawn last, so on top)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color.Transparent),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AsmSessionStatsRow(
                            currentBpm = uiState.currentBpm,
                            remainingSeconds = uiState.remainingSeconds,
                            coherenceRatio = uiState.liveCoherenceRatio
                        )

                        AsmHrvMetricsRow(
                            hrBpm  = uiState.liveHr,
                            rmssd  = uiState.liveRmssd?.toDouble(),
                            sdnn   = uiState.liveSdnn?.toDouble(),
                            rrCount = uiState.rrCount
                        )
                    }

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = TextSecondary,
                        trackColor = SurfaceVariant
                    )

                    // Phase label
                    Text(
                        text = uiState.phase,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    // Quality warning card
                    val warning = uiState.qualityWarning
                    if (warning != null) {
                        QualityWarningCard(message = warning)
                    }

                    // Charts row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Coherence chart
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(AsmInk, AsmCharcoal.copy(alpha = 0.3f), AsmInk)
                                        )
                                    )
                            ) {
                                // Background: long-term coherence
                                if (uiState.coherenceHistory.size >= 2) {
                                    BackgroundLineChart(
                                        data = uiState.coherenceHistory,
                                        color = AsmZoneBlue.copy(alpha = 0.3f),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                
                                // Foreground: short-term coherence
                                if (uiState.coherenceHistory.size >= 2) {
                                    AsmCoherenceChart(
                                        history = uiState.coherenceHistory,
                                        showLabel = false,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "awaiting data…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AsmAsh.copy(alpha = 0.5f),
                                            letterSpacing = 2.sp
                                        )
                                    }
                                }
                            }
                        }

                        // RR Interval chart
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            ) {
                                // Background: long-term RR
                                if (uiState.allSessionRrIntervals.size >= 2) {
                                    BackgroundLineChart(
                                        data = uiState.allSessionRrIntervals.map { it.toFloat() },
                                        color = AsmAsh.copy(alpha = 0.3f),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                
                                // Foreground: short-term RR
                                RrIntervalChart(
                                    rrIntervals = uiState.liveRrIntervals,
                                    windowMs = ASM_CHART_WINDOW_MS,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // RMSSD chart
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            ) {
                                // Background: long-term RMSSD
                                if (uiState.rmssdHistory.size >= 2) {
                                    BackgroundLineChart(
                                        data = uiState.rmssdHistory,
                                        color = AsmRmssdColors.lineColor.copy(alpha = 0.3f),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                
                                // Foreground: short-term RMSSD
                                RmssdChart(
                                    rrIntervals = uiState.liveRrIntervals,
                                    windowMs = ASM_CHART_WINDOW_MS,
                                    colors = AsmRmssdColors,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // End Early & Save button
                    val canFinishEarly = uiState.completedEpochCount >= 1 && !uiState.isComplete
                    Button(
                        onClick = { viewModel.finishEarly() },
                        enabled = canFinishEarly,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor   = Color.White,
                            disabledContainerColor = SurfaceVariant,
                            disabledContentColor   = TextSecondary
                        )
                    ) {
                        val epochLabel = if (uiState.completedEpochCount > 0)
                            "End Early & Save (${uiState.completedEpochCount} test${if (uiState.completedEpochCount == 1) "" else "s"} done)"
                        else
                            "End Early & Save (complete a test first)"
                        Text(epochLabel)
                    }

                    // Cancel button
                    OutlinedButton(
                        onClick = {
                            viewModel.cancel()
                            onNavigateBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Cancel Assessment")
                    }
                }
            }
        } else {
            // ── Portrait: breathing circle centered background, charts on top ──
            Box(modifier = Modifier.fillMaxSize()) {
                // Background breathing circle (drawn first, so behind everything)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(300.dp)
                        .align(Alignment.Center)
                ) {
                    val overlayLabel = when {
                        uiState.phase == "BASELINE" || uiState.phase == "BREATHE NATURALLY" -> uiState.phase
                        else -> null
                    }
                    BreathingPacerCircle(
                        progress = uiState.refWave,
                        isInhaling = uiState.isInhaling,
                        size = 300.dp,
                        overlayLabel = if (protocol.isStepped()) overlayLabel else null,
                        useColors = colorsEnabled,
                        breathCycleCount = uiState.breathCycleCount,
                        onPhaseTransition = vibrationCallback
                    )
                }

                // Foreground content (drawn last, so on top)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color.Transparent),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Top stats row
                    AsmSessionStatsRow(
                        currentBpm = uiState.currentBpm,
                        remainingSeconds = uiState.remainingSeconds,
                        coherenceRatio = uiState.liveCoherenceRatio
                    )

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = TextSecondary,
                        trackColor = SurfaceVariant
                    )

                    // Phase label
                    Text(
                        text = uiState.phase,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    // Live HRV metrics
                    AsmHrvMetricsRow(
                        hrBpm  = uiState.liveHr,
                        rmssd  = uiState.liveRmssd?.toDouble(),
                        sdnn   = uiState.liveSdnn?.toDouble(),
                        rrCount = uiState.rrCount
                    )

                    // Quality warning card
                    val warning = uiState.qualityWarning
                    if (warning != null) {
                        QualityWarningCard(message = warning)
                    }

                    // Coherence chart
                    AsmChartLabel("COHERENCE")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(AsmInk, AsmCharcoal.copy(alpha = 0.3f), AsmInk)
                                )
                            )
                    ) {
                        // Background: long-term coherence
                        if (uiState.coherenceHistory.size >= 2) {
                            BackgroundLineChart(
                                data = uiState.coherenceHistory,
                                color = AsmZoneBlue.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        // Foreground: short-term coherence
                        if (uiState.coherenceHistory.size >= 2) {
                            AsmCoherenceChart(
                                history = uiState.coherenceHistory,
                                showLabel = false,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "awaiting data…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AsmAsh.copy(alpha = 0.5f),
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }

                    // RR Interval chart
                    AsmChartLabel("RR INTERVAL")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        // Background: long-term RR
                        if (uiState.allSessionRrIntervals.size >= 2) {
                            BackgroundLineChart(
                                data = uiState.allSessionRrIntervals.map { it.toFloat() },
                                color = AsmAsh.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        // Foreground: short-term RR
                        RrIntervalChart(
                            rrIntervals = uiState.liveRrIntervals,
                            windowMs = ASM_CHART_WINDOW_MS,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // RMSSD chart
                    AsmChartLabel("RMSSD")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        // Background: long-term RMSSD
                        if (uiState.rmssdHistory.size >= 2) {
                            BackgroundLineChart(
                                data = uiState.rmssdHistory,
                                color = AsmRmssdColors.lineColor.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        // Foreground: short-term RMSSD
                        RmssdChart(
                            rrIntervals = uiState.liveRrIntervals,
                            windowMs = ASM_CHART_WINDOW_MS,
                            colors = AsmRmssdColors,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // End Early & Save button
                    val canFinishEarly = uiState.completedEpochCount >= 1 && !uiState.isComplete
                    Button(
                        onClick = { viewModel.finishEarly() },
                        enabled = canFinishEarly,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor   = Color.White,
                            disabledContainerColor = SurfaceVariant,
                            disabledContentColor   = TextSecondary
                        )
                    ) {
                        val epochLabel = if (uiState.completedEpochCount > 0)
                            "End Early & Save (${uiState.completedEpochCount} test${if (uiState.completedEpochCount == 1) "" else "s"} done)"
                        else
                            "End Early & Save (complete a test first)"
                        Text(epochLabel)
                    }

                    // Cancel button
                    OutlinedButton(
                        onClick = {
                            viewModel.cancel()
                            onNavigateBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Cancel Assessment")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session Stats Row
// ---------------------------------------------------------------------------

@Composable
private fun AsmSessionStatsRow(currentBpm: Float, remainingSeconds: Int, coherenceRatio: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsmStatCell(value = "%.2f".format(currentBpm), label = "BPM",       color = AsmGold)
        AsmStatCell(value = asmFmtDuration(remainingSeconds),   label = "TIME",      color = AsmBone)
        AsmStatCell(value = "%.1f".format(coherenceRatio),   label = "COHERENCE", color = EcgCyan)
    }
}

@Composable
private fun AsmStatCell(value: String, label: String, color: Color) {
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
            color = TextSecondary,
            letterSpacing = 1.sp
        )
    }
}

private fun asmFmtDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

// ---------------------------------------------------------------------------
// HRV Metrics Row
// ---------------------------------------------------------------------------

@Composable
private fun AsmHrvMetricsRow(hrBpm: Int?, rmssd: Double?, sdnn: Double?, rrCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AsmGraphite)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsmMetricCell(value = hrBpm?.toString() ?: "—",                                  label = "HR",    unit = "bpm",  highlight = true)
        AsmThinDivider()
        AsmMetricCell(value = if (rmssd != null && rmssd > 0) "%.0f".format(rmssd) else "—", label = "RMSSD", unit = "ms")
        AsmThinDivider()
        AsmMetricCell(value = if (sdnn  != null && sdnn  > 0) "%.0f".format(sdnn)  else "—", label = "SDNN",  unit = "ms")
        AsmThinDivider()
        AsmMetricCell(value = rrCount.toString(),                                         label = "BEATS", unit = "")
    }
}

@Composable
private fun AsmMetricCell(value: String, label: String, unit: String, highlight: Boolean = false) {
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
            color = if (highlight) AsmBone else AsmSilver
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = AsmAsh,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = AsmAsh.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AsmThinDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(AsmCharcoal)
    )
}

// ---------------------------------------------------------------------------
// Chart section label
// ---------------------------------------------------------------------------

@Composable
private fun AsmChartLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AsmAsh,
        letterSpacing = 2.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp)
    )
}

// ---------------------------------------------------------------------------
// Coherence Chart
// ---------------------------------------------------------------------------

@Composable
private fun AsmCoherenceChart(
    history: List<Float>,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (history.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val maxVal = history.max().coerceAtLeast(1f)

            // Zone threshold lines
            val highThreshold = 3f
            val mediumThreshold = 1f

            // Draw threshold lines
            val highY = h - (highThreshold / maxVal * h).coerceIn(0f, h)
            val mediumY = h - (mediumThreshold / maxVal * h).coerceIn(0f, h)

            drawLine(
                color = AsmZoneBlue.copy(alpha = 0.3f),
                start = Offset(0f, highY),
                end = Offset(w, highY),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = AsmZoneBlue.copy(alpha = 0.2f),
                start = Offset(0f, mediumY),
                end = Offset(w, mediumY),
                strokeWidth = 1.dp.toPx()
            )

            // Draw coherence line
            val path = Path()
            history.forEachIndexed { idx, value ->
                val x = idx.toFloat() / (history.size - 1) * w
                val y = h - (value / maxVal * h).coerceIn(0f, h)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path,
                AsmGold,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw dots at each point
            history.forEachIndexed { idx, value ->
                val x = idx.toFloat() / (history.size - 1) * w
                val y = h - (value / maxVal * h).coerceIn(0f, h)
                drawCircle(
                    color = AsmSilver,
                    radius = 3.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Quality warning card
// ---------------------------------------------------------------------------

@Composable
private fun QualityWarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = SurfaceVariant
        )
    ) {
        Row(
            modifier            = Modifier.padding(12.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Warning,
                contentDescription = "Warning",
                tint               = TextSecondary,
                modifier           = Modifier.size(20.dp)
            )
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
