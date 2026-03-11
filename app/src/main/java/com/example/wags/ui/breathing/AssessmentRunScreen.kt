package com.example.wags.ui.breathing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pacer visual
            if (protocol.isStepped()) {
                SteppedPacerCircle(
                    refWave = uiState.refWave,
                    phase   = uiState.phase
                )
            } else {
                SlidingWindowBar(refWave = uiState.refWave)
            }

            // HUD
            AssessmentHud(
                phase            = uiState.phase,
                currentBpm       = uiState.currentBpm,
                remainingSeconds = uiState.remainingSeconds
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
// Stepped pacer circle
// ---------------------------------------------------------------------------

@Composable
private fun SteppedPacerCircle(refWave: Float, phase: String) {
    val animatedScale by animateFloatAsState(
        targetValue  = 0.6f + 0.4f * refWave.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label        = "pacerScale"
    )
    val isInhale = refWave > 0.5f
    val color = if (isInhale) PacerInhale else PacerExhale

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(240.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2f
            drawCircle(
                color  = color.copy(alpha = 0.12f),
                radius = maxRadius
            )
            drawCircle(
                color  = color,
                radius = maxRadius * animatedScale
            )
        }
        Text(
            text  = if (phase == "BASELINE" || phase == "WASHOUT") phase else if (isInhale) "INHALE" else "EXHALE",
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
    }
}

// ---------------------------------------------------------------------------
// Sliding window horizontal bar
// ---------------------------------------------------------------------------

@Composable
private fun SlidingWindowBar(refWave: Float) {
    val animatedFill by animateFloatAsState(
        targetValue   = refWave.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label         = "slidingBarFill"
    )
    val color = if (animatedFill > 0.5f) PacerInhale else PacerExhale

    Column(
        modifier            = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text  = if (animatedFill > 0.5f) "INHALE" else "EXHALE",
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Track
                drawRect(color = color.copy(alpha = 0.15f))
                // Fill
                drawRect(
                    color = color,
                    size  = size.copy(width = size.width * animatedFill)
                )
            }
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
