package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.AudioSetting
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Progressive O₂ Setup Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveO2Screen(
    navController: NavController,
    viewModel: ProgressiveO2ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadBreathPeriodHistory()
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Progressive O\u2082") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text(
                            "←",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Settings edit dialog state
        var showSettingsDialog by remember { mutableStateOf(false) }
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }

        if (showSettingsDialog) {
            FreeHoldSettingsDialog(
                lungVolume = state.lungVolume,
                prepType = state.prepType,
                timeOfDay = state.timeOfDay,
                posture = state.posture,
                audio = state.audio,
                onLungVolumeChange = { viewModel.setLungVolume(it) },
                onPrepTypeChange = { viewModel.setPrepType(it) },
                onTimeOfDayChange = { viewModel.setTimeOfDay(it) },
                onPostureChange = { viewModel.setPosture(it) },
                onAudioChange = { viewModel.setAudio(it) },
                onDismiss = { showSettingsDialog = false }
            )
        }

        if (showSongPicker) {
            SongPickerDialog(
                songs = state.previousSongs,
                isLoading = state.loadingSongs,
                selectedSong = state.selectedSong,
                loadingSelectedSong = state.loadingSelectedSong,
                onSongSelected = { track -> viewModel.selectSong(track) },
                onDismiss = { showSongPicker = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. Settings summary banner — clickable to open settings popup
            ApneaSettingsSummaryBanner(
                lungVolume = state.lungVolume,
                prepType   = state.prepType,
                timeOfDay  = state.timeOfDay,
                posture    = state.posture,
                audio      = state.audio,
                onClick    = { showSettingsDialog = true }
            )

            // 0b. Song picker / connect prompt — shown when MUSIC mode
            if (state.isMusicMode) {
                if (state.spotifyConnected) {
                    if (state.selectedSong != null) {
                        SelectedSongBanner(track = state.selectedSong!!) {
                            viewModel.clearSelectedSong()
                        }
                    }
                    SongPickerButton(onClick = {
                        viewModel.loadPreviousSongs()
                        showSongPicker = true
                    })
                } else {
                    SpotifyConnectPrompt(
                        onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }
                    )
                }
            }

            // 1. Explanation card
            ExplanationCard()

            // 2. Breath period input
            BreathPeriodSection(
                breathPeriodSec = state.breathPeriodSec,
                onSetBreathPeriod = { viewModel.setBreathPeriod(it) }
            )

            // 2b. Voice / vibration toggles
            VoiceVibrationToggles(
                voiceEnabled = state.voiceEnabled,
                vibrationEnabled = state.vibrationEnabled,
                onVoiceToggle = { viewModel.setVoiceEnabled(it) },
                onVibrationToggle = { viewModel.setVibrationEnabled(it) }
            )

            // 3. Start button
            Button(
                onClick = { navController.navigate(WagsRoutes.PROGRESSIVE_O2_ACTIVE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text(
                    "Start",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 3b. Personal Bests button
            OutlinedButton(
                onClick = {
                    navController.navigate(
                        WagsRoutes.personalBests(
                            drillType = "PROGRESSIVE_O2",
                            drillParamValue = state.breathPeriodSec
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TextSecondary)
            ) {
                Text(
                    "🏆  Personal Bests",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
            }

            // 4. Breath period history
            BreathPeriodHistorySection(
                history = state.pastBreathPeriods,
                currentBreathPeriodSec = state.breathPeriodSec,
                onSelectBreathPeriod = { viewModel.setBreathPeriod(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Explanation Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = "Progressive O\u2082 is an endless breath-hold drill that builds oxygen " +
                    "tolerance. Each round increases the hold by 15 seconds: " +
                    "15s \u2192 30s \u2192 45s \u2192 60s \u2192 \u2026 " +
                    "The drill continues until you stop it. Set your breathing period " +
                    "below and tap Start when ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Breath Period Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BreathPeriodSection(
    breathPeriodSec: Int,
    onSetBreathPeriod: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Breathing Period",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalButton(
                onClick = {
                    val newVal = (breathPeriodSec - 5).coerceIn(15, 180)
                    onSetBreathPeriod(newVal)
                },
                enabled = breathPeriodSec > 15,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled
                ),
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = "${breathPeriodSec}s",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 80.dp)
            )
            Spacer(modifier = Modifier.width(24.dp))
            FilledTonalButton(
                onClick = {
                    val newVal = (breathPeriodSec + 5).coerceIn(15, 180)
                    onSetBreathPeriod(newVal)
                },
                enabled = breathPeriodSec < 180,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled
                ),
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Breath Period History Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BreathPeriodHistorySection(
    history: List<BreathPeriodHistory>,
    currentBreathPeriodSec: Int,
    onSelectBreathPeriod: (Int) -> Unit
) {
    Text(
        text = "Past Breath Periods",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = TextPrimary
    )

    if (history.isEmpty()) {
        Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            history.forEach { item ->
                val isSelected = item.breathPeriodSec == currentBreathPeriodSec
                val bgColor = if (isSelected) SurfaceVariant.copy(alpha = 0.5f) else SurfaceDark
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .clickable { onSelectBreathPeriod(item.breathPeriodSec) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.breathPeriodSec}s breath",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = TextPrimary
                    )
                    Text(
                        text = "Max hold: ${formatSeconds(item.maxHoldReachedSec)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Formats total seconds as "M:SS" (e.g. 120 → "2:00", 75 → "1:15"). */
private fun formatSeconds(totalSec: Int): String {
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
