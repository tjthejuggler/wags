package com.example.wags.ui.breathing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
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
import com.example.wags.ui.theme.*

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
    RfProtocol.EXPRESS       -> "Express Sweep"
    RfProtocol.STANDARD      -> "Standard Sweep"
    RfProtocol.DEEP          -> "Deep Calibration"
    RfProtocol.TARGETED      -> "Targeted Sweep"
    RfProtocol.CONTINUOUS    -> "Continuous Sweep"
    RfProtocol.SLIDING_WINDOW -> "Sliding Window"
}

private fun RfProtocol.isStepped(): Boolean = this != RfProtocol.SLIDING_WINDOW

// ---------------------------------------------------------------------------
// Coherence zone colors — greyscale
// ---------------------------------------------------------------------------

private val CoherenceZoneRed   = Color(0xFF606060)   // dim grey  = low coherence
private val CoherenceZoneBlue  = Color(0xFF909090)   // mid grey  = medium coherence
private val CoherenceZoneGreen = Color(0xFFD0D0D0)   // light grey = high coherence

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

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentRunScreen(
    protocol: RfProtocol,
    onNavigateBack: () -> Unit,
    onSessionComplete: (sessionId: Long) -> Unit,
    vibrationEnabled: Boolean = false,
    viewModel: AssessmentRunViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isActive = uiState.phase != "IDLE" && uiState.phase != "COMPLETE"

    SessionBackHandler(enabled = isActive, onConfirm = onNavigateBack)
    KeepScreenOn(enabled = isActive)

    // Navigate once when complete — play end-of-session sound
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            WagsFeedback.sessionEnd(context)
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

    // Animate background tint based on coherence zone
    val zoneColor by animateColorAsState(
        targetValue = coherenceZoneColor(uiState.liveCoherenceRatio),
        animationSpec = tween(durationMillis = 1000),
        label = "zone_color"
    )

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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Vibration callback — only fires when toggle is on
            val vibrationCallback: ((Boolean) -> Unit)? = if (vibrationEnabled) {
                { inhaling ->
                    if (inhaling) WagsFeedback.breathInhale(context)
                    else WagsFeedback.breathExhale(context)
                }
            } else null

            // Pacer visual — unified circle for all protocols
            if (protocol.isStepped()) {
                val overlayLabel = when {
                    uiState.phase == "BASELINE" || uiState.phase == "BREATHE NATURALLY" -> uiState.phase
                    else -> null
                }
                BreathingPacerCircle(
                    progress = uiState.refWave,
                    isInhaling = uiState.isInhaling,
                    size = 200.dp,
                    overlayLabel = overlayLabel,
                    onPhaseTransition = vibrationCallback
                )
            } else {
                BreathingPacerCircle(
                    progress = uiState.refWave,
                    isInhaling = uiState.isInhaling,
                    size = 200.dp,
                    onPhaseTransition = vibrationCallback
                )
            }

            // HUD
            AssessmentHud(
                phase            = uiState.phase,
                currentBpm       = uiState.currentBpm,
                remainingSeconds = uiState.remainingSeconds
            )

            // Coherence Zone Traffic Light
            CoherenceZoneIndicator(
                coherenceRatio = uiState.liveCoherenceRatio,
                zoneColor = zoneColor
            )

            // Live stats row
            LiveStatsRow(
                hr = uiState.liveHr,
                rrCount = uiState.rrCount,
                coherenceRatio = uiState.liveCoherenceRatio
            )

            // Overall progress
            LinearProgressIndicator(
                progress         = { uiState.progress },
                modifier         = Modifier.fillMaxWidth(),
                color            = TextSecondary,
                trackColor       = SurfaceVariant
            )

            // Quality warning card
            val warning = uiState.qualityWarning
            if (warning != null) {
                QualityWarningCard(message = warning)
            }

            // ── RR interval scrolling chart ───────────────────────────────
            AsmChartLabel("RR INTERVAL")
            RrIntervalChart(
                rrIntervals = uiState.liveRrIntervals,
                windowMs = ASM_CHART_WINDOW_MS,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )

            // ── RMSSD scrolling chart ─────────────────────────────────────
            AsmChartLabel("RMSSD")
            RmssdChart(
                rrIntervals = uiState.liveRrIntervals,
                windowMs = ASM_CHART_WINDOW_MS,
                colors = AsmRmssdColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )

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

// ---------------------------------------------------------------------------
// Coherence Zone Indicator (Traffic Light)
// ---------------------------------------------------------------------------

@Composable
private fun CoherenceZoneIndicator(
    coherenceRatio: Float,
    zoneColor: Color
) {
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
            // Glowing dot
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

// ---------------------------------------------------------------------------
// Live Stats Row
// ---------------------------------------------------------------------------

@Composable
private fun LiveStatsRow(
    hr: Int?,
    rrCount: Int,
    coherenceRatio: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCell(value = hr?.toString() ?: "—", label = "HR", unit = "bpm")
        StatCell(value = rrCount.toString(), label = "BEATS", unit = "")
        StatCell(value = "%.1f".format(coherenceRatio), label = "COHERENCE", unit = "ratio")
    }
}

@Composable
private fun StatCell(value: String, label: String, unit: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 60.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.sp
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextSecondary.copy(alpha = 0.6f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// HUD
// ---------------------------------------------------------------------------

@Composable
private fun AssessmentHud(
    phase: String,
    currentBpm: Float,
    remainingSeconds: Int
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text  = phase,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )
        if (currentBpm > 0f) {
            Text(
                text  = "%.1f BPM".format(currentBpm),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }
        if (remainingSeconds > 0) {
            val mins = remainingSeconds / 60
            val secs = remainingSeconds % 60
            Text(
                text  = "%d:%02d remaining".format(mins, secs),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
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

// ---------------------------------------------------------------------------
// Chart section label
// ---------------------------------------------------------------------------

@Composable
private fun AsmChartLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 2.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp)
    )
}
