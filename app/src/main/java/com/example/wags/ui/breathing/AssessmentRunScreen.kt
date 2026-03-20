package com.example.wags.ui.breathing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
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
import com.example.wags.domain.usecase.breathing.RfProtocol
import com.example.wags.ui.theme.*

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
// Coherence zone colors
// ---------------------------------------------------------------------------

private val CoherenceZoneRed = Color(0xFFE53935)
private val CoherenceZoneBlue = Color(0xFF42A5F5)
private val CoherenceZoneGreen = Color(0xFF66BB6A)

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
    viewModel: AssessmentRunViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate once when complete
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            val id = uiState.sessionId ?: return@LaunchedEffect
            onSessionComplete(id)
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pacer visual — unified circle for all protocols
            if (protocol.isStepped()) {
                val overlayLabel = when {
                    uiState.phase == "BASELINE" || uiState.phase == "WASHOUT" -> uiState.phase
                    else -> null
                }
                BreathingPacerCircle(
                    progress = uiState.refWave,
                    isInhaling = uiState.isInhaling,
                    size = 220.dp,
                    overlayLabel = overlayLabel
                )
            } else {
                BreathingPacerCircle(
                    progress = uiState.refWave,
                    isInhaling = uiState.isInhaling,
                    size = 220.dp
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
                color            = EcgCyan,
                trackColor       = SurfaceVariant
            )

            // Quality warning card
            val warning = uiState.qualityWarning
            if (warning != null) {
                QualityWarningCard(message = warning)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Cancel button
            OutlinedButton(
                onClick = {
                    viewModel.cancel()
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
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
            color = EcgCyan
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
            containerColor = Color(0xFF3A2800)  // amber-dark background
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
                tint               = ReadinessOrange,
                modifier           = Modifier.size(20.dp)
            )
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = ReadinessOrange
            )
        }
    }
}
