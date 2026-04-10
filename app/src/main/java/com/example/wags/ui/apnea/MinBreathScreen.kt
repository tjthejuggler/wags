package com.example.wags.ui.apnea

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
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Min Breath Setup Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinBreathScreen(
    navController: NavController,
    viewModel: MinBreathViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadPastSessions()
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Min Breath") },
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
            MinBreathExplanationCard()

            // 2. Session duration input
            SessionDurationSection(
                sessionDurationSec = state.sessionDurationSec,
                onSetSessionDuration = { viewModel.setSessionDurationSec(it) }
            )

            // 3. Start button
            Button(
                onClick = { navController.navigate("min_breath_active") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text(
                    "Start Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 4. Session history
            SessionHistorySection(
                history = state.pastDurations,
                currentDurationSec = state.sessionDurationSec,
                onSelectDuration = { viewModel.setSessionDurationSec(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Explanation Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MinBreathExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = "Choose a session duration, then try to minimize your total breathing time. " +
                    "During the session, you control when to breathe and when to hold \u2014 " +
                    "the goal is to spend as little time breathing as possible.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session Duration Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SessionDurationSection(
    sessionDurationSec: Int,
    onSetSessionDuration: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Session Duration",
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
                    val newVal = (sessionDurationSec - 30).coerceIn(60, 600)
                    onSetSessionDuration(newVal)
                },
                enabled = sessionDurationSec > 60,
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
                Text("\u2212", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = formatSeconds(sessionDurationSec),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 80.dp)
            )
            Spacer(modifier = Modifier.width(24.dp))
            FilledTonalButton(
                onClick = {
                    val newVal = (sessionDurationSec + 30).coerceIn(60, 600)
                    onSetSessionDuration(newVal)
                },
                enabled = sessionDurationSec < 600,
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
// Session History Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SessionHistorySection(
    history: List<DurationHistory>,
    currentDurationSec: Int,
    onSelectDuration: (Int) -> Unit
) {
    Text(
        text = "Session History",
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
                val isSelected = item.durationSec == currentDurationSec
                val bgColor = if (isSelected) SurfaceVariant.copy(alpha = 0.5f) else SurfaceDark
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .clickable { onSelectDuration(item.durationSec) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatSeconds(item.durationSec),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = TextPrimary
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Best: ${String.format("%.1f", item.bestHoldPct)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${item.sessionCount} session${if (item.sessionCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextDisabled
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Formats total seconds as "M:SS" (e.g. 300 → "5:00", 90 → "1:30"). */
private fun formatSeconds(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
