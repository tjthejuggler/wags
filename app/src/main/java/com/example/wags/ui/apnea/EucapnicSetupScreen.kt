package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

/**
 * Setup screen for Eucapnic Diaphragmatic Breathing preparation.
 *
 * This screen appears before the actual breathing pacer and allows users to:
 * - View and configure Eucapnic settings via a settings button
 * - View and select from past configurations
 * - Start the breathing pacer
 *
 * @param navController Navigation controller
 * @param lungVolume Lung volume setting for the hold
 * @param timeOfDay Time of day setting for the hold
 * @param posture Posture setting for the hold
 * @param audio Audio setting for the hold
 * @param viewModel Injected EucapnicConfigViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EucapnicSetupScreen(
    navController: NavController,
    lungVolume: String,
    timeOfDay: String,
    posture: String,
    audio: String,
    sessionType: String = "FREE_HOLD",
    viewModel: EucapnicConfigViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val savedConfigurations by viewModel.pastConfigurations.collectAsStateWithLifecycle()

    // Dialog states
    var showEucapnicSettingsDialog by remember { mutableStateOf(false) }
    var showPastConfigurationsDialog by remember { mutableStateOf(false) }
    var showSaveConfigurationDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Eucapnic Setup") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Title
            Text(
                "Eucapnic Diaphragmatic Breathing",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Eucapnic settings button
            EucapnicSettingsButton(
                config = config,
                onClick = { showEucapnicSettingsDialog = true }
            )

            // Past Configurations button
            OutlinedButton(
                onClick = { showPastConfigurationsDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Past Configurations")
            }

            Spacer(modifier = Modifier.weight(1f))

            // Start button
            Button(
                onClick = {
                    // Navigate to the actual pacer screen with config and sessionType
                    navController.navigate(
                        WagsRoutes.eucapnicPacer(
                            lungVolume = lungVolume,
                            timeOfDay = timeOfDay,
                            posture = posture,
                            audio = audio,
                            sessionType = sessionType,
                            prepDurationSec = config.prepDurationSec,
                            breathsPerMin = config.breathsPerMin,
                            inhaleSec = config.inhaleSec,
                            topPauseSec = config.topPauseSec,
                            exhaleSec = config.exhaleSec,
                            bottomPauseSec = config.bottomPauseSec,
                            breathDepthPercent = config.breathDepthPercent
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("Start Breathing", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    // Eucapnic settings dialog
    if (showEucapnicSettingsDialog) {
        EucapnicSettingsDialog(
            config = config,
            onPrepDurationChange = { duration ->
                viewModel.updatePrepDuration(duration)
            },
            onBpmChange = { bpm ->
                viewModel.updateBpm(bpm)
            },
            onInhaleChange = { inhale ->
                viewModel.updateInhale(inhale)
            },
            onTopPauseChange = { topPause ->
                viewModel.updateTopPause(topPause)
            },
            onExhaleChange = { exhale ->
                viewModel.updateExhale(exhale)
            },
            onBottomPauseChange = { bottomPause ->
                viewModel.updateBottomPause(bottomPause)
            },
            onBreathDepthChange = { depth ->
                viewModel.updateBreathDepth(depth)
            },
            onDismiss = { showEucapnicSettingsDialog = false }
        )
    }

    // Past Configurations dialog
    if (showPastConfigurationsDialog) {
        PastConfigurationsDialog(
            configurations = savedConfigurations,
            onConfigurationSelected = { entity ->
                viewModel.loadConfiguration(entity)
                showPastConfigurationsDialog = false
            },
            onSaveCurrentClick = {
                showPastConfigurationsDialog = false
                showSaveConfigurationDialog = true
            },
            onDismiss = { showPastConfigurationsDialog = false }
        )
    }

    // Save Configuration dialog
    if (showSaveConfigurationDialog) {
        SaveConfigurationDialog(
            onSave = { name ->
                viewModel.saveConfiguration(name)
                showSaveConfigurationDialog = false
            },
            onDismiss = { showSaveConfigurationDialog = false }
        )
    }
}
