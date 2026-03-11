package com.example.wags.ui.breathing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathingScreen(
    navController: NavController,
    onNavigateToRfAssessment: () -> Unit = {},
    viewModel: BreathingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Resonance Breathing") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
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
            // Breathing pacer circle
            BreathingPacer(
                radius = state.pacerRadius,
                phaseLabel = state.breathPhaseLabel
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

            // Session controls
            if (state.isSessionActive) {
                OutlinedButton(
                    onClick = { viewModel.stopSession() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Stop Session") }
            } else {
                Button(
                    onClick = { viewModel.startSession(deviceId) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start Session") }
            }

            HorizontalDivider(color = SurfaceVariant)

            // RF Assessment navigation
            Button(
                onClick = onNavigateToRfAssessment,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RF Assessment")
            }
        }
    }
}

@Composable
private fun BreathingPacer(radius: Float, phaseLabel: String) {
    val minRadius = 60f
    val maxRadius = 120f
    val currentRadius = minRadius + (maxRadius - minRadius) * radius.coerceIn(0f, 1f)
    val color = if (phaseLabel == "INHALE") PacerInhale else PacerExhale

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(260.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = color.copy(alpha = 0.15f),
                radius = maxRadius.dp.toPx()
            )
            drawCircle(
                color = color,
                radius = currentRadius.dp.toPx()
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(phaseLabel, style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}

@Composable
private fun CoherenceDisplay(score: Float) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
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

@Composable
private fun BreathingControls(
    rateBpm: Float,
    ieRatio: Float,
    onRateChange: (Float) -> Unit,
    onIeRatioChange: (Float) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

private fun coherenceColor(score: Float) = when {
    score >= 3f -> CoherenceGreen
    score >= 2f -> CoherenceYellow
    score >= 1f -> CoherenceOrange
    else -> CoherenceRed
}
