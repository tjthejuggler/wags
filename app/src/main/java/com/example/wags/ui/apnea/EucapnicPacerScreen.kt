package com.example.wags.ui.apnea

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.model.EucapnicPhase
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.common.LockPortrait
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

/**
 * Active pacing screen for Eucapnic Diaphragmatic breathing preparation.
 *
 * Displays a full-screen guided breathing pacer with:
 * - Animated breathing gauge (expand/contract) that always fills fully,
 *   showing the target lung fullness percent ("to X%") inside the circle
 * - Current phase indicator
 * - Time remaining in total prep
 * - Breath count
 * - Current BPM
 *
 * The screen handles lifecycle events (pause/resume on background/foreground)
 * and provides audio/haptic feedback via the ViewModel.
 *
 * @param navController Navigation controller for navigating to the hold screen
 * @param lungVolume Lung volume setting for the hold
 * @param timeOfDay Time of day setting for the hold
 * @param posture Posture setting for the hold
 * @param audio Audio setting for the hold
 * @param viewModel Injected [EucapnicPacerViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EucapnicPacerScreen(
    navController: NavController,
    lungVolume: String,
    timeOfDay: String,
    posture: String,
    audio: String,
    sessionType: String = "FREE_HOLD",
    initialConfig: EucapnicConfig? = null,
    viewModel: EucapnicPacerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("apnea_prefs", Context.MODE_PRIVATE)
    }

    // Collect state
    val pacerState by viewModel.pacerState.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val pacerRadius by viewModel.pacerRadius.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val isComplete by viewModel.isComplete.collectAsStateWithLifecycle()
    val remainingTimeMs by viewModel.remainingTimeMs.collectAsStateWithLifecycle()
    val breathsCompleted by viewModel.breathsCompleted.collectAsStateWithLifecycle()
    val currentBpm by viewModel.currentBpm.collectAsStateWithLifecycle()
    val vibrationOn by viewModel.vibrationEnabled.collectAsStateWithLifecycle()

    // Get config from ViewModel (it should be set by ApneaViewModel before navigation)
    val config by viewModel.config.collectAsStateWithLifecycle()

    // Color mode toggle — persisted to SharedPreferences (same key as resonance breathing)
    var useColors by remember {
        mutableStateOf(prefs.getBoolean("breathing_colors", false))
    }

    // Start the pacer once on first composition.
    // Using LaunchedEffect(Unit) avoids the double-start bug where keying on
    // `config` causes startPrep to fire again when the ViewModel sets _config
    // (null → initialConfig), which would restart the engine from scratch.
    LaunchedEffect(Unit) {
        initialConfig?.let { viewModel.startPrep(it) }
    }

    // Handle completion - navigate to the appropriate active screen based on sessionType
    LaunchedEffect(isComplete) {
        if (isComplete) {
            when (sessionType) {
                "FREE_HOLD" -> {
                    // Pop back to the existing FreeHoldActiveScreen and mark
                    // eucapnic prep as completed so the user can start the hold.
                    // Using popBackStack avoids stacking duplicate FreeHoldActive
                    // screens and keeps the back navigation clean.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("eucapnic_prep_completed", true)

                    navController.popBackStack()
                }
                "PROGRESSIVE_O2" -> {
                    navController.navigate(WagsRoutes.PROGRESSIVE_O2_ACTIVE)
                }
                "MIN_BREATH" -> {
                    navController.navigate(WagsRoutes.MIN_BREATH_ACTIVE)
                }
                "WONKA_FIRST_CONTRACTION", "WONKA_ENDURANCE" -> {
                    navController.navigate(WagsRoutes.CONTRACTION_TABLE_ACTIVE)
                }
                "APNEA_TABLE" -> {
                    navController.navigate(WagsRoutes.APNEA_TABLE)
                }
                else -> {
                    // Default to free hold for unknown session types — pop back
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("eucapnic_prep_completed", true)

                    navController.popBackStack()
                }
            }
        }
    }

    // Lifecycle: pause/resume when app goes to background/foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE  -> viewModel.pausePrep()
                Lifecycle.Event.ON_RESUME -> viewModel.resumePrep()
                else                      -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Session guards
    LockPortrait()
    KeepScreenOn(enabled = isRunning && !isComplete)
    SessionBackHandler(
        enabled = isRunning && !isComplete,
        onConfirm = {
            viewModel.stopPrep()
            navController.popBackStack()
        }
    )

    // ── Initialise vibration from persisted pref ───────────────────────────
    LaunchedEffect(Unit) {
        viewModel.setVibrationEnabled(prefs.getBoolean("breathing_vibration", false))
    }

    // ── Colour-mode background (peripheral-vision cue) ──────────────────────
    val phaseBgColor = if (useColors) {
        when (phase) {
            EucapnicPhase.INHALE       -> PacerInhale.copy(alpha = 0.18f)
            EucapnicPhase.TOP_PAUSE    -> PacerInhale.copy(alpha = 0.12f)
            EucapnicPhase.EXHALE       -> PacerExhale.copy(alpha = 0.18f)
            EucapnicPhase.BOTTOM_PAUSE -> PacerExhale.copy(alpha = 0.10f)
        }
    } else {
        BackgroundDark
    }
    val animatedBg by animateColorAsState(
        targetValue = phaseBgColor,
        animationSpec = tween(durationMillis = 300),
        label = "eucapnic_bg"
    )

    // ── UI ──────────────────────────────────────────────────────────────────

    Scaffold(
        containerColor = animatedBg,
        topBar = {
            TopAppBar(
                title = { Text("Eucapnic Prep", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopPrep()
                        navController.popBackStack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel & back",
                            tint = TextSecondary
                        )
                    }
                },
                actions = {
                    // Vibration toggle — toggleable mid-session
                    IconButton(onClick = {
                        viewModel.setVibrationEnabled(!vibrationOn)
                        prefs.edit().putBoolean("breathing_vibration", !vibrationOn).apply()
                    }) {
                        Text(
                            text = "〰",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (vibrationOn) TextPrimary else TextDisabled
                        )
                    }
                    // Colour mode toggle for inhale/exhale peripheral vision
                    IconButton(onClick = {
                        useColors = !useColors
                        prefs.edit().putBoolean("breathing_colors", useColors).apply()
                    }) {
                        Text(
                            text = "🎨",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = if (!useColors) Modifier.grayscale() else Modifier,
                            color = if (useColors) PacerInhale else TextDisabled
                        )
                    }
                    // Live HR / SpO₂ feed
                    LiveSensorActionsCallback(
                        onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(animatedBg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // ── Top info bar ────────────────────────────────────────────
                PacerInfoBar(
                    remainingTimeMs = remainingTimeMs,
                    breathsCompleted = breathsCompleted,
                    currentBpm = currentBpm
                )

                Spacer(modifier = Modifier.height(32.dp))

                // ── Breathing gauge ─────────────────────────────────────────
                EucapnicPacerGauge(
                    phase = phase,
                    radius = pacerRadius,
                    breathDepthPercent = config?.breathDepthPercent ?: 25,
                    size = 280.dp,
                    showLabel = true,
                    useColors = useColors
                )

                Spacer(modifier = Modifier.height(32.dp))

                // ── Phase indicator ─────────────────────────────────────────
                PhaseIndicator(phase = phase)

                Spacer(modifier = Modifier.height(16.dp))

                // ── Progress bar ────────────────────────────────────────────
                val totalProg = pacerState?.totalProgress ?: 0f
                LinearProgressIndicator(
                    progress = { totalProg },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = TextPrimary,
                    trackColor = SurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ── Time remaining text ─────────────────────────────────────
                Text(
                    text = formatTime(remainingTimeMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                // ── Paused overlay hint ─────────────────────────────────────
                if (isPaused && !isComplete) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "PAUSED",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        ),
                        color = TextSecondary
                    )
                }

                // ── Completion state ────────────────────────────────────────
                if (isComplete) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "PREPARATION COMPLETE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        ),
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

/**
 * Top bar showing remaining time, breath count, and BPM.
 */
@Composable
private fun PacerInfoBar(
    remainingTimeMs: Long,
    breathsCompleted: Int,
    currentBpm: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        InfoCell(
            label = "REMAINING",
            value = formatTime(remainingTimeMs)
        )
        InfoCell(
            label = "BREATHS",
            value = "$breathsCompleted"
        )
        InfoCell(
            label = "BPM",
            value = "%.1f".format(currentBpm)
        )
    }
}

/**
 * Single info cell with a label and value.
 */
@Composable
private fun InfoCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = TextPrimary
        )
    }
}

/**
 * Phase indicator chip showing the current breathing phase.
 */
@Composable
private fun PhaseIndicator(phase: EucapnicPhase) {
    val phaseLabel = when (phase) {
        EucapnicPhase.INHALE       -> "INHALE"
        EucapnicPhase.TOP_PAUSE    -> "TOP PAUSE"
        EucapnicPhase.EXHALE       -> "EXHALE"
        EucapnicPhase.BOTTOM_PAUSE -> "BOTTOM PAUSE"
    }

    val phaseColor = when (phase) {
        EucapnicPhase.INHALE       -> PacerInhale
        EucapnicPhase.TOP_PAUSE    -> PacerInhale.copy(alpha = 0.6f)
        EucapnicPhase.EXHALE       -> PacerExhale
        EucapnicPhase.BOTTOM_PAUSE -> PacerExhale.copy(alpha = 0.4f)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SurfaceVariant
    ) {
        Text(
            text = phaseLabel,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp
            ),
            color = phaseColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Format milliseconds as M:SS.
 */
private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}
