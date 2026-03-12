package com.example.wags.ui.morning

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.readiness.MorningReadinessFsm
import com.example.wags.domain.usecase.readiness.MorningReadinessState
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReadinessScreen(
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: MorningReadinessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // No HRM dialog
    if (uiState.noHrmDialogVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNoHrmDialog() },
            title = { Text("Heart Rate Monitor Required", color = TextPrimary) },
            text = {
                Text(
                    "Please connect your Polar H10 heart rate monitor before starting the Morning Readiness test.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissNoHrmDialog() }) {
                    Text("OK", color = EcgCyan)
                }
            },
            containerColor = SurfaceDark
        )
    }

    // Stand alert: play tone + vibrate when flag is set
    LaunchedEffect(uiState.triggerStandAlert) {
        if (uiState.triggerStandAlert) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 800)
            } catch (_: Exception) { }
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)
                }
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 300, 100, 300, 100, 500),
                        -1
                    )
                )
            } catch (_: Exception) { }
            viewModel.acknowledgeStandAlert()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Morning Readiness", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = uiState.liveHr, liveSpO2 = uiState.liveSpO2)
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
            when (uiState.fsmState) {
                MorningReadinessState.IDLE ->
                    IdleContent(onStart = { viewModel.startSession() })
                MorningReadinessState.INIT ->
                    InitContent(uiState)
                MorningReadinessState.SUPINE_HRV ->
                    SupineHrvContent(uiState)
                MorningReadinessState.STAND_PROMPT ->
                    StandPromptContent()
                MorningReadinessState.STANDING ->
                    StandingContent(uiState)
                MorningReadinessState.QUESTIONNAIRE ->
                    QuestionnaireContent(uiState, viewModel)
                MorningReadinessState.CALCULATING ->
                    CalculatingContent()
                MorningReadinessState.COMPLETE ->
                    MorningReadinessResultScreen(
                        result = uiState.result!!,
                        onReset = { viewModel.reset() }
                    )
                MorningReadinessState.ERROR ->
                    ErrorContent(
                        errorMessage = uiState.errorMessage,
                        onRetry = { viewModel.reset() }
                    )
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Morning Readiness Test",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Before you begin:", style = MaterialTheme.typography.titleMedium, color = EcgCyan)
                Text("1. Connect your Polar H10 heart rate monitor", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text("2. Lie down flat on your back", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text("3. Relax and breathe normally", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text("4. The test takes approximately 5 minutes", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
        ) {
            Text("Start Morning Readiness Test")
        }
    }
}

@Composable
private fun InitContent(uiState: MorningReadinessUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Get Ready", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
        CountdownCircle(
            seconds = uiState.remainingSeconds,
            totalSeconds = MorningReadinessFsm.INIT_DURATION_SECONDS,
            color = EcgCyanDim
        )
        Text(
            "Lie down flat on your back and relax",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            "The test will begin automatically",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
        PulsingDot()
    }
}

@Composable
private fun SupineHrvContent(uiState: MorningReadinessUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Recording Supine Data", style = MaterialTheme.typography.headlineSmall, color = EcgCyan)
        CountdownCircle(seconds = uiState.remainingSeconds, totalSeconds = MorningReadinessFsm.SUPINE_HRV_SECONDS, color = EcgCyan)
        if (uiState.liveRmssd > 0.0) {
            Text(
                "Live RMSSD: ${String.format("%.1f", uiState.liveRmssd)} ms",
                style = MaterialTheme.typography.bodyLarge,
                color = EcgCyan
            )
        }
        Text(
            "RR intervals: ${uiState.rrCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            "Stay still and breathe normally",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StandPromptContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "stand_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            "STAND UP NOW\nAND REMAIN STILL",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = ReadinessRed,
            textAlign = TextAlign.Center,
            modifier = Modifier.scale(scale)
        )
        Text(
            "Stand up immediately and stay as still as possible",
            style = MaterialTheme.typography.bodyLarge,
            color = ReadinessOrange,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StandingContent(uiState: MorningReadinessUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Recording Standing Data", style = MaterialTheme.typography.headlineSmall, color = EcgCyan)
        CountdownCircle(
            seconds = uiState.remainingSeconds,
            totalSeconds = MorningReadinessFsm.STANDING_SECONDS,
            color = ReadinessOrange
        )
        if (uiState.peakStandHr > 0) {
            Text(
                "Peak HR: ${uiState.peakStandHr} bpm",
                style = MaterialTheme.typography.headlineMedium,
                color = ReadinessOrange,
                fontWeight = FontWeight.Bold
            )
        }
        if (uiState.liveRmssd > 0.0) {
            Text(
                "Live RMSSD: ${String.format("%.1f", uiState.liveRmssd)} ms",
                style = MaterialTheme.typography.bodyLarge,
                color = EcgCyan
            )
        }
        Text(
            "RR intervals: ${uiState.rrCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            "Remain standing and still",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun QuestionnaireContent(
    uiState: MorningReadinessUiState,
    viewModel: MorningReadinessViewModel
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Hooper Wellness Check", style = MaterialTheme.typography.headlineSmall, color = EcgCyan)
        Text(
            "Rate how you feel right now (1 = worst, 5 = best)",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        HooperSlider(
            label = "Sleep Quality",
            lowLabel = "Poor",
            highLabel = "Excellent",
            value = uiState.hooperSleep,
            onValueChange = { viewModel.updateHooper(it, uiState.hooperFatigue, uiState.hooperSoreness, uiState.hooperStress) }
        )
        HooperSlider(
            label = "Fatigue",
            lowLabel = "Very fatigued",
            highLabel = "No fatigue",
            value = uiState.hooperFatigue,
            onValueChange = { viewModel.updateHooper(uiState.hooperSleep, it, uiState.hooperSoreness, uiState.hooperStress) }
        )
        HooperSlider(
            label = "Muscle Soreness",
            lowLabel = "Very sore",
            highLabel = "No soreness",
            value = uiState.hooperSoreness,
            onValueChange = { viewModel.updateHooper(uiState.hooperSleep, uiState.hooperFatigue, it, uiState.hooperStress) }
        )
        HooperSlider(
            label = "Stress",
            lowLabel = "Very stressed",
            highLabel = "No stress",
            value = uiState.hooperStress,
            onValueChange = { viewModel.updateHooper(uiState.hooperSleep, uiState.hooperFatigue, uiState.hooperSoreness, it) }
        )

        Button(
            onClick = { viewModel.submitHooper() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonSuccess)
        ) {
            Text("Submit & Calculate Results")
        }
    }
}

@Composable
private fun HooperSlider(
    label: String,
    lowLabel: String,
    highLabel: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "$value / 5",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = EcgCyan
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3,
                colors = SliderDefaults.colors(thumbColor = EcgCyan, activeTrackColor = EcgCyan)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                Text(highLabel, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
    }
}

@Composable
private fun CalculatingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = EcgCyan, modifier = Modifier.size(64.dp))
        Text("Analyzing your data...", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

@Composable
internal fun ErrorContent(errorMessage: String?, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Error", style = MaterialTheme.typography.headlineMedium, color = ReadinessRed)
        Text(
            errorMessage ?: "An unknown error occurred",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
        ) {
            Text("Try Again")
        }
    }
}

// ── Shared UI helpers ─────────────────────────────────────────────────────────

@Composable
internal fun CountdownCircle(seconds: Int, totalSeconds: Int, color: Color) {
    val progress = if (totalSeconds > 0) seconds.toFloat() / totalSeconds.toFloat() else 0f
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(140.dp),
            color = color,
            strokeWidth = 8.dp,
            trackColor = SurfaceVariant
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$seconds",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text("sec", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_scale"
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .scale(scale)
            .border(2.dp, EcgCyanDim, CircleShape)
    )
}
