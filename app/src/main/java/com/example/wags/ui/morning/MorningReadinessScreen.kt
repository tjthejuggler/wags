package com.example.wags.ui.morning

import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.readiness.MorningReadinessFsm
import com.example.wags.domain.usecase.readiness.MorningReadinessState
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── Monochrome palette ────────────────────────────────────────────────────────
// Designed for e-ink / greyscale readability while still looking elegant on OLED.
private val Bone = Color(0xFFE8E8E8)          // Primary text — near-white
private val Silver = Color(0xFFB0B0B0)        // Secondary text — mid-grey
private val Ash = Color(0xFF707070)           // Tertiary / disabled — dark grey
private val Graphite = Color(0xFF383838)      // Card / surface fill
private val Charcoal = Color(0xFF1C1C1C)      // Subtle divider / track
private val Ink = Color(0xFF0A0A0A)           // Deep background
private val ChartLine = Color(0xFFD0D0D0)     // Chart stroke — bright enough to pop
private val ChartDot = Color(0xFFFFFFFF)      // Dot accent — pure white
private val ChartGlow = Color(0xFF909090)     // Subtle glow under chart line
private val ArcFill = Color(0xFFCCCCCC)       // Countdown arc — bright
private val ArcTrack = Color(0xFF2A2A2A)      // Countdown arc track — barely visible
private val SkipButtonBg = Color(0xFF8B0000)   // Dark red for skip standing button

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

    // Stand alert: play singing bell + vibrate when flag is set.
    // We use a monotonically-increasing generation counter as the LaunchedEffect key
    // so the effect always re-fires even if the Boolean somehow stays true across
    // recompositions driven by the liveHr/liveSpO2 combine emissions.
    val standAlertGeneration = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(uiState.triggerStandAlert) {
        if (uiState.triggerStandAlert) {
            standAlertGeneration.intValue++
            try {
                // Use applicationContext so MediaPlayer is not tied to the Activity lifecycle.
                val mp = MediaPlayer.create(context.applicationContext, com.example.wags.R.raw.singing_bell)
                if (mp != null) {
                    mp.setOnCompletionListener { it.release() }
                    mp.start()
                }
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
                    StandPromptContent(onSkipStanding = { viewModel.skipStanding() })
                MorningReadinessState.STANDING ->
                    StandingContent(uiState, onSkipStanding = { viewModel.skipStanding() })
                MorningReadinessState.QUESTIONNAIRE ->
                    QuestionnaireContent(uiState, viewModel)
                MorningReadinessState.CALCULATING ->
                    CalculatingContent()
                MorningReadinessState.COMPLETE -> {
                    val result = uiState.result
                    if (result != null) {
                        MorningReadinessResultScreen(
                            result = result,
                            onReset = { viewModel.reset() }
                        )
                    } else {
                        CalculatingContent()
                    }
                }
                MorningReadinessState.ERROR ->
                    ErrorContent(
                        errorMessage = uiState.errorMessage,
                        onRetry = { viewModel.reset() }
                    )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  IDLE — Start screen
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  INIT — Get ready countdown
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun InitContent(uiState: MorningReadinessUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "GET READY",
            style = MaterialTheme.typography.titleMedium,
            color = Silver,
            letterSpacing = 4.sp
        )

        MinimalistArcCountdown(
            remainingSeconds = uiState.remainingSeconds,
            totalSeconds = MorningReadinessFsm.INIT_DURATION_SECONDS
        )

        Text(
            "Lie down flat on your back and relax",
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
            textAlign = TextAlign.Center
        )
        Text(
            "The test will begin automatically",
            style = MaterialTheme.typography.bodyMedium,
            color = Ash,
            textAlign = TextAlign.Center
        )
        PulsingDot()
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  SUPINE HRV — The main recording screen (redesigned)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun SupineHrvContent(uiState: MorningReadinessUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Phase label ──
        Text(
            "SUPINE",
            style = MaterialTheme.typography.titleMedium,
            color = Silver,
            letterSpacing = 6.sp
        )

        // ── Countdown arc ──
        MinimalistArcCountdown(
            remainingSeconds = uiState.remainingSeconds,
            totalSeconds = MorningReadinessFsm.SUPINE_HRV_SECONDS
        )

        Spacer(Modifier.height(4.dp))

        // ── Live metrics row ──
        LiveMetricsRow(
            hrBpm = uiState.liveHr,
            rmssd = uiState.liveRmssd,
            sdnn = uiState.liveSdnn,
            rrCount = uiState.rrCount
        )

        Spacer(Modifier.height(4.dp))

        // ── Scrolling RR chart ──
        RrIntervalChart(
            rrIntervals = uiState.liveRrIntervals,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        )

        // ── Subtle instruction ──
        Text(
            "breathe normally · stay still",
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
            letterSpacing = 2.sp
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  STAND PROMPT
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun StandPromptContent(onSkipStanding: () -> Unit) {
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
        Spacer(Modifier.height(8.dp))
        SkipStandingButton(onSkipStanding)
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  STANDING — Recording standing data (redesigned)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun StandingContent(uiState: MorningReadinessUiState, onSkipStanding: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Phase label ──
        Text(
            "STANDING",
            style = MaterialTheme.typography.titleMedium,
            color = Silver,
            letterSpacing = 6.sp
        )

        // ── Countdown arc ──
        MinimalistArcCountdown(
            remainingSeconds = uiState.remainingSeconds,
            totalSeconds = MorningReadinessFsm.STANDING_SECONDS
        )

        Spacer(Modifier.height(4.dp))

        // ── Live metrics row ──
        LiveMetricsRow(
            hrBpm = uiState.liveHr,
            rmssd = uiState.liveRmssd,
            sdnn = uiState.liveSdnn,
            rrCount = uiState.rrCount,
            peakHr = uiState.peakStandHr.takeIf { it > 0 }
        )

        Spacer(Modifier.height(4.dp))

        // ── Scrolling RR chart ──
        RrIntervalChart(
            rrIntervals = uiState.liveRrIntervals,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        )

        // ── Subtle instruction ──
        Text(
            "remain standing · stay still",
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(8.dp))

        // ── Skip standing button — visible throughout the entire standing phase ──
        SkipStandingButton(onSkipStanding)
    }
}

/**
 * Prominent "Skip Standing" button shown during both STAND_PROMPT and STANDING phases.
 * Large, clearly visible, with explanatory text below.
 */
@Composable
private fun SkipStandingButton(onSkipStanding: () -> Unit) {
    Button(
        onClick = onSkipStanding,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SkipButtonBg,
            contentColor = Bone
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            "SKIP STANDING",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
    Text(
        "End test now — results saved without orthostatic data",
        style = MaterialTheme.typography.bodySmall,
        color = Ash.copy(alpha = 0.7f),
        textAlign = TextAlign.Center
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  QUESTIONNAIRE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
            "Slide to rate how you feel right now",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        HooperSlider(
            label = "Sleep Quality",
            descriptors = listOf("Terrible", "Poor", "Okay", "Good", "Excellent"),
            value = uiState.hooperSleep,
            onValueChange = { viewModel.updateHooper(it, uiState.hooperFatigue, uiState.hooperSoreness, uiState.hooperStress) }
        )
        HooperSlider(
            label = "Fatigue",
            descriptors = listOf("Exhausted", "Very tired", "Somewhat tired", "Slightly tired", "Fully rested"),
            value = uiState.hooperFatigue,
            onValueChange = { viewModel.updateHooper(uiState.hooperSleep, it, uiState.hooperSoreness, uiState.hooperStress) }
        )
        HooperSlider(
            label = "Muscle Soreness",
            descriptors = listOf("Severely sore", "Very sore", "Moderately sore", "Slightly sore", "No soreness"),
            value = uiState.hooperSoreness,
            onValueChange = { viewModel.updateHooper(uiState.hooperSleep, uiState.hooperFatigue, it, uiState.hooperStress) }
        )
        HooperSlider(
            label = "Stress",
            descriptors = listOf("Overwhelmed", "Very stressed", "Somewhat stressed", "Slightly stressed", "No stress"),
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

/**
 * A Hooper wellness slider with:
 *  - A bold label above identifying what is being rated
 *  - A thumb that starts at the centre (3 / 5) and is considered "unset" until moved
 *  - A single description line below the track that reads "unset" (muted) before
 *    the first interaction, then switches to one of five contextual descriptors
 *    that reflect the current thumb position
 *
 * @param label       The dimension being rated (e.g. "Sleep Quality")
 * @param descriptors Exactly 5 strings mapping to positions 1 → 5 (worst → best)
 * @param value       Current slider value in [1, 5]
 * @param onValueChange Callback with the new value
 */
@Composable
private fun HooperSlider(
    label: String,
    descriptors: List<String>,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    require(descriptors.size == 5) { "HooperSlider requires exactly 5 descriptors" }

    // Track whether the user has interacted with this slider yet.
    // Starts false; flips to true on the first drag event.
    var touched by remember { mutableStateOf(false) }

    // Colour of the active track / thumb across five equal zones of 1–100:
    //   1–20  → red      (worst)
    //   21–40 → orange
    //   41–60 → silver   (neutral centre)
    //   61–80 → green
    //   81–100→ green    (best)
    val trackColor = when {
        value <= 20f  -> ReadinessRed
        value <= 40f  -> ReadinessOrange
        value <= 60f  -> Silver
        else          -> ReadinessGreen
    }

    // Map the continuous [1, 100] value to one of the five descriptor strings.
    // Zones: 1–20, 21–40, 41–60, 61–80, 81–100
    val descriptorIndex = when {
        value <= 20f -> 0
        value <= 40f -> 1
        value <= 60f -> 2
        value <= 80f -> 3
        else         -> 4
    }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ── Label row: topic on the left, numeric value on the right ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Bone,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = value.toInt().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (touched) trackColor else Ash
                )
            }

            // ── Slider track ──
            Slider(
                value = value,
                onValueChange = { newVal ->
                    touched = true
                    onValueChange(newVal)
                },
                valueRange = 1f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = if (touched) trackColor else Ash,
                    activeTrackColor = if (touched) trackColor else Ash.copy(alpha = 0.4f),
                    inactiveTrackColor = SurfaceDark
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Description line ──
            // "unset" in muted Ash before first touch; contextual descriptor after
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (!touched) {
                    Text(
                        text = "unset",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ash.copy(alpha = 0.55f),
                        letterSpacing = 1.sp
                    )
                } else {
                    Text(
                        text = descriptors[descriptorIndex],
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = trackColor,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  CALCULATING / ERROR
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ✦  MINIMALIST ARC COUNTDOWN
//  A thin 270° arc that sweeps from full → empty as time elapses.
//  The remaining seconds are displayed in the centre with a subtle
//  breathing animation. No axis labels, no ticks — pure geometry.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun MinimalistArcCountdown(
    remainingSeconds: Int,
    totalSeconds: Int
) {
    val progress = if (totalSeconds > 0) remainingSeconds.toFloat() / totalSeconds.toFloat() else 0f

    // Smooth animated progress so the arc doesn't jump every second
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "arc_progress"
    )

    // Subtle breathing scale on the time text
    val infiniteTransition = rememberInfiniteTransition(label = "countdown_breathe")
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Rotating tick mark at the end of the arc
    val tickAngle = 135f + animatedProgress * 270f // 135° is start, sweeps 270°

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val padding = strokeWidth * 2
            val arcSize = size.minDimension - padding * 2

            // Track — very faint ring
            drawArc(
                color = ArcTrack,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(padding, padding),
                size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Filled arc — bright, shows remaining time
            if (animatedProgress > 0f) {
                drawArc(
                    color = ArcFill,
                    startAngle = 135f,
                    sweepAngle = animatedProgress * 270f,
                    useCenter = false,
                    topLeft = Offset(padding, padding),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Small dot at the leading edge of the arc
            if (animatedProgress > 0.01f) {
                val radius = arcSize / 2f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val angleRad = Math.toRadians(tickAngle.toDouble())
                val dotX = centerX + radius * cos(angleRad).toFloat()
                val dotY = centerY + radius * sin(angleRad).toFloat()
                drawCircle(
                    color = ChartDot,
                    radius = 4.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }

        // Centre text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(breatheScale)
        ) {
            val minutes = remainingSeconds / 60
            val secs = remainingSeconds % 60
            Text(
                text = if (minutes > 0) String.format("%d:%02d", minutes, secs) else "$secs",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Thin,
                    fontSize = if (minutes > 0) 36.sp else 42.sp,
                    letterSpacing = 2.sp
                ),
                color = Bone
            )
            Text(
                text = if (minutes > 0) "min" else "sec",
                style = MaterialTheme.typography.bodySmall,
                color = Ash
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ✦  LIVE METRICS ROW
//  Compact horizontal strip showing HR, RMSSD, SDNN, and beat count.
//  Monochrome-safe: uses brightness hierarchy (white → grey → dark grey).
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun LiveMetricsRow(
    hrBpm: Int?,
    rmssd: Double,
    sdnn: Double,
    rrCount: Int,
    peakHr: Int? = null
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
        // HR
        MetricCell(
            value = hrBpm?.toString() ?: "—",
            label = "HR",
            unit = "bpm",
            highlight = true
        )

        ThinDivider()

        // RMSSD
        MetricCell(
            value = if (rmssd > 0) String.format("%.0f", rmssd) else "—",
            label = "RMSSD",
            unit = "ms"
        )

        ThinDivider()

        // SDNN
        MetricCell(
            value = if (sdnn > 0) String.format("%.0f", sdnn) else "—",
            label = "SDNN",
            unit = "ms"
        )

        // Peak HR (standing only)
        if (peakHr != null) {
            ThinDivider()
            MetricCell(
                value = peakHr.toString(),
                label = "PEAK",
                unit = "bpm",
                highlight = true
            )
        }

        ThinDivider()

        // Beat count
        MetricCell(
            value = rrCount.toString(),
            label = "BEATS",
            unit = ""
        )
    }
}

@Composable
private fun MetricCell(
    value: String,
    label: String,
    unit: String,
    highlight: Boolean = false
) {
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
//  ✦  TIME-BASED STRIP CHART FOR RR INTERVALS
//
//  Behaves like a classic strip-chart recorder / ECG monitor:
//  • Fixed 30-second time window — the X axis always represents 30 s.
//  • Each RR interval is plotted at its cumulative time position.
//  • A continuously-advancing cursor (driven by withFrameNanos) moves the
//    "now" edge smoothly to the right, so the line grows at real-time speed.
//  • Once the line fills the window, old data scrolls off the left edge.
//  • Data arrives in ~500 ms polling chunks but the cursor moves every frame,
//    producing perfectly smooth motion.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Time window the chart displays, in milliseconds. */
private const val CHART_WINDOW_MS = 30_000.0

/**
 * A single RR beat with its cumulative time offset from the first beat.
 * [timeMs] = sum of all preceding RR intervals (0 for the first beat).
 * [valueMs] = the RR interval duration in ms (used for Y axis).
 */
private data class TimedBeat(val timeMs: Double, val valueMs: Double)

/**
 * Holds the strip-chart state. Converts raw RR intervals into time-positioned
 * beats and tracks a smoothly-advancing "now" cursor.
 *
 * The ViewModel provides a **sliding window** of the last ~45 RR intervals
 * from a circular buffer. The list size caps at ~45 and old values drop off
 * the front as new ones are appended. We detect new beats by comparing the
 * last value in our internal buffer with the tail of the source list.
 */
@Stable
private class StripChartState {
    /** All beats with cumulative time positions. */
    val beats = mutableStateListOf<TimedBeat>()

    /** The cumulative time of the last beat (ms from first beat). */
    var cumulativeTimeMs by mutableDoubleStateOf(0.0)
        private set

    /** Wall-clock nanos when the first beat was added — used for cursor. */
    var firstBeatNanos: Long = 0L
        private set

    /** Whether we have received any beats yet. */
    var started by mutableStateOf(false)
        private set

    /** Fingerprint of the last source list we processed: (size, lastValue). */
    private var lastSourceSize = 0
    private var lastSourceTail = 0.0

    /**
     * Ingest beats from the source sliding-window list.
     *
     * The source is a sliding window (last ~45 RR intervals). We figure out
     * how many new beats were appended by comparing the source tail with our
     * previous snapshot. New beats are those at the end of the source list
     * that weren't there before.
     */
    fun ingest(source: List<Double>, nowNanos: Long) {
        if (source.isEmpty()) return
        if (!started) {
            started = true
            firstBeatNanos = nowNanos
        }

        // Determine how many new beats appeared at the end of the source.
        // The source is a sliding window: old beats drop off the front, new
        // ones appear at the back. We find the overlap between our previous
        // snapshot and the current source to identify truly new beats.
        val newBeatsCount: Int
        if (beats.isEmpty()) {
            // First time: ingest everything
            newBeatsCount = source.size
        } else {
            // The source grew by (source.size - lastSourceSize) if it hasn't
            // hit the cap yet. Once capped, each new beat adds 1 and removes 1
            // from the front, so source.size stays the same but content shifts.
            // We detect new beats by finding where our last-known tail value
            // appears in the new source list and taking everything after it.
            val lastKnownValue = lastSourceTail
            // Search backwards from the end for our last known value
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
                // Can't find overlap — source changed completely, ingest all
                source.size
            }
        }

        if (newBeatsCount <= 0) {
            lastSourceSize = source.size
            lastSourceTail = source.last()
            return
        }

        // Append the new beats
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

        // Trim beats that are too old (more than 2× window behind the latest)
        val cutoff = cumulativeTimeMs - CHART_WINDOW_MS * 2
        while (beats.size > 2 && beats.first().timeMs < cutoff) {
            beats.removeAt(0)
        }
    }
}

@Composable
private fun RrIntervalChart(
    rrIntervals: List<Double>,
    modifier: Modifier = Modifier
) {
    val state = remember { StripChartState() }

    // Smoothly advancing cursor time (ms from first beat).
    // Driven by withFrameNanos for 60fps updates.
    var cursorTimeMs by remember { mutableDoubleStateOf(0.0) }

    // Ingest new data whenever the source list changes (content or size).
    // We use a content-based key so we detect changes even when the list
    // size stays the same (sliding window at capacity).
    val sourceFingerprint = remember(rrIntervals) {
        if (rrIntervals.isEmpty()) 0L
        else rrIntervals.size.toLong() * 1_000_000L + rrIntervals.last().toLong()
    }
    LaunchedEffect(sourceFingerprint) {
        state.ingest(rrIntervals, System.nanoTime())
    }

    // Continuous frame loop: advances the cursor at real-time speed
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                if (state.started) {
                    cursorTimeMs = (nanos - state.firstBeatNanos) / 1_000_000.0
                }
            }
        }
    }

    // ── Shimmer ──
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

    // ── Animated Y-range ──
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
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                drawStripChart(
                    beats = beatSnapshot,
                    cursorTimeMs = cursor,
                    shimmerPhase = shimmerPhase,
                    paddedMin = animatedPaddedMin.toDouble(),
                    paddedMax = animatedPaddedMax.toDouble()
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
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

/**
 * Draws the time-based strip chart.
 *
 * The visible window is [windowStart, windowEnd] where:
 *   windowEnd = cursorTimeMs  (the "now" edge is always at the right)
 *   windowStart = cursorTimeMs - CHART_WINDOW_MS
 *
 * Before 30 s of data exists, the left portion of the chart is empty and
 * the line appears partway across, growing toward the right edge.
 * After 30 s, old data scrolls off the left as new data enters from the right.
 */
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

    // The right edge is always "now". The left edge is 30 s before "now".
    // This means early on (cursor < 30s), windowStart is negative and
    // beats at time 0 appear partway across the chart (not at the left edge).
    val windowEnd = cursorTimeMs
    val windowStart = cursorTimeMs - CHART_WINDOW_MS

    fun xAt(timeMs: Double): Float =
        ((timeMs - windowStart) / CHART_WINDOW_MS * w).toFloat()

    fun yAt(valueMs: Double): Float =
        h - ((valueMs - paddedMin) / yRange * h).toFloat()

    // Only draw beats within (or near) the visible window
    val visibleBeats = beats.filter { it.timeMs >= windowStart - 2000 && it.timeMs <= windowEnd + 2000 }
    if (visibleBeats.size < 2) return

    val points = visibleBeats.map { Offset(xAt(it.timeMs), yAt(it.valueMs)) }

    // ── Draw subtle glow line (wider, dimmer) ──
    val glowPath = Path()
    buildCatmullRomPath(glowPath, points)
    drawPath(
        path = glowPath,
        color = ChartGlow.copy(alpha = 0.15f),
        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // ── Draw main line ──
    val mainPath = Path()
    buildCatmullRomPath(mainPath, points)
    drawPath(
        path = mainPath,
        color = ChartLine,
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // ── Draw dots at each data point ──
    points.forEachIndexed { _, pt ->
        if (pt.x < -10f || pt.x > w + 10f) return@forEachIndexed
        val normalizedX = (pt.x / w).coerceIn(0f, 1f)
        // Fade out dots near the left edge
        val fadeAlpha = if (normalizedX < 0.15f) normalizedX / 0.15f else 1f
        val shimmerDist = kotlin.math.abs(normalizedX - shimmerPhase)
        val shimmerBoost = (1f - (shimmerDist * 4f).coerceIn(0f, 1f)) * 0.4f
        val dotAlpha = (0.3f + shimmerBoost) * fadeAlpha
        val dotRadius = (1.8f + shimmerBoost * 2f).dp.toPx()
        drawCircle(
            color = ChartDot.copy(alpha = dotAlpha),
            radius = dotRadius,
            center = pt
        )
    }

    // ── Left-edge fade overlay ──
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Ink, Ink.copy(alpha = 0f)),
            startX = 0f,
            endX = w * 0.12f
        ),
        size = size
    )
}

/**
 * Builds a Catmull-Rom spline path through the given points.
 * This produces a smooth curve that passes through every data point,
 * unlike Bézier which only approximates.
 */
private fun buildCatmullRomPath(path: Path, points: List<Offset>) {
    if (points.size < 2) return

    path.moveTo(points[0].x, points[0].y)

    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return
    }

    // For Catmull-Rom, we need points before and after each segment.
    // We mirror the first and last points to create virtual endpoints.
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

    val tension = 0.5f // Standard Catmull-Rom tension
    val segments = 12   // Interpolation segments per span

    for (i in 1 until extended.size - 2) {
        val p0 = extended[i - 1]
        val p1 = extended[i]
        val p2 = extended[i + 1]
        val p3 = extended[i + 2]

        for (s in 1..segments) {
            val t = s.toFloat() / segments
            val t2 = t * t
            val t3 = t2 * t

            val x = tension * (
                (2 * p1.x) +
                (-p0.x + p2.x) * t +
                (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3
            )
            val y = tension * (
                (2 * p1.y) +
                (-p0.y + p2.y) * t +
                (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3
            )

            path.lineTo(x, y)
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  Shared helpers
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Legacy countdown circle — kept for backward compatibility but the new screens
 * use [MinimalistArcCountdown] instead.
 */
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
