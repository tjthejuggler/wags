package com.example.wags.ui.meditation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.common.LockPortrait
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationScreen(
    navController: NavController,
    viewModel: MeditationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val isActive = state.sessionState == MeditationSessionState.ACTIVE ||
            state.sessionState == MeditationSessionState.PROCESSING

    // Keep screen on during COMPLETE too so the user can review results
    val keepScreenOn = isActive || state.sessionState == MeditationSessionState.COMPLETE

    var showAudioPicker by remember { mutableStateOf(false) }

    LockPortrait()
    SessionBackHandler(enabled = isActive) { navController.popBackStack() }
    KeepScreenOn(enabled = keepScreenOn)

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Meditation / NSDR", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
                        )
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2, onClick = { navController.navigate(WagsRoutes.SETTINGS) })
                    IconButton(onClick = {
                        navController.navigate(WagsRoutes.MEDITATION_HISTORY)
                    }) {
                        Text("📊", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.grayscale())
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
        ) {
            // ── Advice Banner ───────────────────────────────────────────────
            AdviceBanner(section = AdviceSection.MEDITATION)

            when (state.sessionState) {
                MeditationSessionState.IDLE -> IdleContent(
                    state = state,
                    onAudioPickerClick = { showAudioPicker = true },
                    onSonificationToggle = { viewModel.setSonificationEnabled(!state.sonificationEnabled) },
                    onPostureSelected = { viewModel.setPosture(it) },
                    onTimerEnabledChange = { viewModel.setTimerEnabled(it) },
                    onTimerHoursChange = { viewModel.setTimerHours(it) },
                    onTimerMinutesChange = { viewModel.setTimerMinutes(it) },
                    onTimerSecondsChange = { viewModel.setTimerSeconds(it) },
                    onTimerSoundEnabledChange = { viewModel.setTimerSoundEnabled(it) },
                    onTimerVibrationEnabledChange = { viewModel.setTimerVibrationEnabled(it) },
                    onStart = { viewModel.startSession() },
                    modifier = Modifier
                )
                MeditationSessionState.ACTIVE -> ActiveContent(
                    state = state,
                    onStop = { viewModel.stopSession() },
                    modifier = Modifier
                )
                MeditationSessionState.PROCESSING -> ProcessingContent(
                    modifier = Modifier
                )
                MeditationSessionState.COMPLETE -> CompleteContent(
                    state = state,
                    onViewHistory = {
                        navController.navigate(WagsRoutes.MEDITATION_HISTORY)
                    },
                    onDone = { viewModel.reset() },
                    modifier = Modifier
                )
            }
        }
    }

    // Audio picker dialog
    if (showAudioPicker) {
        MeditationAudioPickerDialog(
            audios = state.audios,
            selectedAudio = state.selectedAudio,
            availableChannels = state.availableChannels,
            selectedChannelFilter = state.selectedChannelFilter,
            isLoadingAudios = state.isLoadingAudios,
            audioDirUri = state.audioDirUri,
            onSelectAudio = { viewModel.selectAudio(it) },
            onChannelFilterSelected = { viewModel.setChannelFilter(it) },
            onRefreshAudios = { viewModel.refreshAudios() },
            onEditAudioUrl = { viewModel.openUrlEditor(it) },
            onDismiss = { showAudioPicker = false }
        )
    }

    // URL edit dialog
    state.editingAudio?.let { audio ->
        AudioUrlEditDialog(
            audio = audio,
            isFetching = state.isFetchingMetadata,
            fetchedMetadata = state.fetchedMetadata,
            onFetchPreview = { url -> viewModel.fetchMetadataPreview(url) },
            onDismiss = { viewModel.dismissUrlEditor() },
            onSave = { url -> viewModel.saveAudioUrl(audio.audioId, url) }
        )
    }
}

// ── Idle content ───────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(
    state: MeditationUiState,
    onAudioPickerClick: () -> Unit,
    onSonificationToggle: () -> Unit,
    onPostureSelected: (MeditationPosture) -> Unit,
    onTimerEnabledChange: (Boolean) -> Unit,
    onTimerHoursChange: (Int) -> Unit,
    onTimerMinutesChange: (Int) -> Unit,
    onTimerSecondsChange: (Int) -> Unit,
    onTimerSoundEnabledChange: (Boolean) -> Unit,
    onTimerVibrationEnabledChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── Audio picker button + selected banner ──────────────────────────
        item {
            state.selectedAudio?.let { audio ->
                SelectedMeditationAudioBanner(
                    name = if (audio.isNone) "None (silent meditation)" else audio.displayName
                )
            }
        }
        item {
            MeditationAudioPickerButton(onClick = onAudioPickerClick)
        }

        // ── HR monitor status ───────────────────────────────────────────────
        item {
            HorizontalDivider(color = SurfaceVariant)
            Spacer(Modifier.height(4.dp))
            MonitorStatusBanner(
                hasHrMonitor = state.hasHrMonitor,
                deviceId = state.connectedDeviceId
            )
        }

        // ── Sonification toggle ─────────────────────────────────────────────
        if (state.hasHrMonitor) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("HR Sonification", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Auditory heartbeat feedback",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = state.sonificationEnabled,
                            onCheckedChange = { onSonificationToggle() }
                        )
                    }
                }
            }
        }

        // ── Posture selector ────────────────────────────────────────────────
        item {
            PostureSelector(
                selected = state.selectedPosture,
                onSelect = onPostureSelected
            )
        }

        // ── Timer option ────────────────────────────────────────────────────
        item {
            TimerOptionRow(
                enabled = state.timerEnabled,
                hours = state.timerHours,
                minutes = state.timerMinutes,
                seconds = state.timerSeconds,
                soundEnabled = state.timerSoundEnabled,
                vibrationEnabled = state.timerVibrationEnabled,
                onEnabledChange = onTimerEnabledChange,
                onHoursChange = onTimerHoursChange,
                onMinutesChange = onTimerMinutesChange,
                onSecondsChange = onTimerSecondsChange,
                onSoundEnabledChange = onTimerSoundEnabledChange,
                onVibrationEnabledChange = onTimerVibrationEnabledChange
            )
        }

        // ── Start button ────────────────────────────────────────────────────
        item {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedAudio != null
            ) {
                Text(
                    if (state.hasHrMonitor) "Start Session" else "Start Session (no HR monitor)"
                )
            }
        }
    }
}

// ── Timer option row ──────────────────────────────────────────────────────────

@Composable
private fun TimerOptionRow(
    enabled: Boolean,
    hours: Int,
    minutes: Int,
    seconds: Int,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHoursChange: (Int) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Checkbox row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextSecondary,
                        uncheckedColor = TextSecondary
                    )
                )
                Column {
                    Text(
                        "Countdown Timer",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        "Plays a chime when time is up (session continues)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // hh:mm:ss fields — only visible when enabled
            if (enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimerField(
                        value = hours,
                        label = "hh",
                        max = 23,
                        onChange = onHoursChange,
                        modifier = Modifier.weight(1f)
                    )
                    Text(":", style = MaterialTheme.typography.headlineSmall, color = TextSecondary)
                    TimerField(
                        value = minutes,
                        label = "mm",
                        max = 59,
                        onChange = onMinutesChange,
                        modifier = Modifier.weight(1f)
                    )
                    Text(":", style = MaterialTheme.typography.headlineSmall, color = TextSecondary)
                    TimerField(
                        value = seconds,
                        label = "ss",
                        max = 59,
                        onChange = onSecondsChange,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // Sound and vibration toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sound toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = soundEnabled,
                            onCheckedChange = onSoundEnabledChange,
                            modifier = Modifier.size(24.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = ButtonPrimary,
                                uncheckedColor = TextSecondary,
                                checkmarkColor = TextPrimary
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "🔊 Sound",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (soundEnabled) TextPrimary else TextSecondary
                        )
                    }
                    
                    // Vibration toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = vibrationEnabled,
                            onCheckedChange = onVibrationEnabledChange,
                            modifier = Modifier.size(24.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = ButtonPrimary,
                                uncheckedColor = TextSecondary,
                                checkmarkColor = TextPrimary
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "📳 Vibration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (vibrationEnabled) TextPrimary else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerField(
    value: Int,
    label: String,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = String.format("%02d", value),
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.takeLast(2)
            val parsed = digits.toIntOrNull() ?: 0
            onChange(parsed.coerceIn(0, max))
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            textAlign = TextAlign.Center,
            color = TextPrimary
        ),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TextSecondary,
            unfocusedBorderColor = SurfaceVariant,
            focusedLabelColor = TextSecondary,
            cursorColor = TextPrimary
        )
    )
}

// ── Monitor status banner ──────────────────────────────────────────────────────

@Composable
private fun MonitorStatusBanner(hasHrMonitor: Boolean, deviceId: String?) {
    val bgColor = if (hasHrMonitor) SurfaceDark else SurfaceVariant
    val dot = if (hasHrMonitor) "●" else "○"
    val dotColor = if (hasHrMonitor) TextPrimary else TextDisabled
    val text = if (hasHrMonitor) {
        "Monitor connected: ${deviceId ?: "Unknown"}"
    } else {
        "No HR monitor — session will be recorded without HR data"
    }
    Card(colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(dot, color = dotColor, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Active session content ─────────────────────────────────────────────────────

@Composable
private fun ActiveContent(
    state: MeditationUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Session Active", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)

        // Selected audio label
        state.selectedAudio?.let { audio ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (audio.isNone) "Silent Meditation" else audio.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (!audio.isNone && !audio.youtubeChannel.isNullOrBlank()) {
                    Text(
                        audio.youtubeChannel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Elapsed timer
        val elapsedMin = state.elapsedSeconds / 60L
        val elapsedSec = state.elapsedSeconds % 60L
        Text(
            "${elapsedMin}:${String.format("%02d", elapsedSec)}",
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimary
        )

        // Countdown timer display (only when enabled)
        val remaining = state.timerRemainingSeconds
        if (remaining != null) {
            val chimeFired = state.timerChimeFired
            val remH = remaining / 3600L
            val remM = (remaining % 3600L) / 60L
            val remS = remaining % 60L
            val countdownText = if (remH > 0)
                "${remH}:${String.format("%02d", remM)}:${String.format("%02d", remS)}"
            else
                "${remM}:${String.format("%02d", remS)}"

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (chimeFired) SurfaceVariant else SurfaceDark
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        if (chimeFired) "Timer complete" else "Timer",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        if (chimeFired) "🔔" else countdownText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (chimeFired) TextPrimary else TextSecondary
                    )
                }
            }
        }

        if (state.hasHrMonitor) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveMetricCard(
                    label = "Heart Rate",
                    value = state.currentHrBpm?.let { "${String.format("%.0f", it)} BPM" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                LiveMetricCard(
                    label = "RMSSD",
                    value = state.currentRmssd?.let { "${String.format("%.1f", it)} ms" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                Text(
                    "Timer only — no HR monitor connected",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("Stop Session")
        }
    }
}

@Composable
private fun LiveMetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        }
    }
}

// ── Processing content ─────────────────────────────────────────────────────────

@Composable
private fun ProcessingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        Text("Analyzing session…", style = MaterialTheme.typography.bodyLarge)
    }
}

// ── Complete content ───────────────────────────────────────────────────────────

@Composable
private fun CompleteContent(
    state: MeditationUiState,
    onViewHistory: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Session Complete", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)

        // Audio used
        state.selectedAudio?.let { audio ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (audio.isNone) "Silent Meditation" else audio.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (!audio.isNone && !audio.youtubeChannel.isNullOrBlank()) {
                    Text(
                        audio.youtubeChannel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Summary", style = MaterialTheme.typography.titleLarge)

                val durationMin = state.durationMs / 60_000L
                val durationSec = (state.durationMs % 60_000L) / 1_000L
                SummaryRow("Duration", "${durationMin}m ${durationSec}s")
                SummaryRow("Posture", state.selectedPosture.label)
                SummaryRow("Monitor", state.monitorId ?: "None (no HR data)")

                if (state.avgHrBpm != null) {
                    HorizontalDivider(color = TextDisabled.copy(alpha = 0.3f))
                    Text("HR Analytics", style = MaterialTheme.typography.titleMedium)
                    SummaryRow("Avg HR", "${String.format("%.1f", state.avgHrBpm)} BPM")
                    state.hrSlopeBpmPerMin?.let {
                        val sign = if (it >= 0) "+" else ""
                        SummaryRow("HR Slope", "$sign${String.format("%.2f", it)} BPM/min")
                    }
                    state.startRmssdMs?.let {
                        SummaryRow("Start RMSSD", "${String.format("%.1f", it)} ms")
                    }
                    state.endRmssdMs?.let {
                        SummaryRow("End RMSSD", "${String.format("%.1f", it)} ms")
                    }
                    state.lnRmssdSlope?.let {
                        val sign = if (it >= 0) "+" else ""
                        SummaryRow("ln(RMSSD) Slope", "$sign${String.format("%.4f", it)}")
                    }
                } else {
                    Text(
                        "Connect a monitor next time for full HR analytics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onViewHistory,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("View History")
        }

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("New Session")
        }
    }
}

// ── Posture selector ──────────────────────────────────────────────────────────

@Composable
private fun PostureSelector(
    selected: MeditationPosture,
    onSelect: (MeditationPosture) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Posture",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MeditationPosture.entries.forEach { posture ->
                val isSelected = posture == selected
                OutlinedButton(
                    onClick = { onSelect(posture) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) TextSecondary.copy(alpha = 0.15f)
                                         else Color.Transparent,
                        contentColor   = if (isSelected) TextPrimary else TextSecondary
                    ),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) TextSecondary else SurfaceVariant
                    ),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    Text(
                        posture.label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// ── Summary row ────────────────────────────────────────────────────────────────

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
    }
}

// ── URL edit dialog ────────────────────────────────────────────────────────────

@Composable
fun AudioUrlEditDialog(
    audio: MeditationAudioEntity,
    isFetching: Boolean,
    fetchedMetadata: com.example.wags.data.repository.YouTubeMetadataFetcher.YoutubeMetadata?,
    onFetchPreview: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var urlText by remember(audio.audioId) { mutableStateOf(audio.sourceUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                "Edit Source URL",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // File name subtitle
                Text(
                    audio.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // URL input
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("URL (YouTube, etc.)") },
                    placeholder = { Text("https://…", color = TextDisabled) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = SurfaceVariant,
                        focusedLabelColor = TextSecondary,
                        cursorColor = TextPrimary
                    )
                )

                // Fetch button (only shown when URL looks like YouTube)
                val looksLikeYouTube = urlText.contains("youtube.com") || urlText.contains("youtu.be")
                if (looksLikeYouTube) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onFetchPreview(urlText) },
                            enabled = !isFetching && urlText.isNotBlank(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            if (isFetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = TextSecondary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Fetching…", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("Fetch YouTube Info", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // Preview of fetched metadata
                when {
                    fetchedMetadata != null -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = SurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        "YouTube info found",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    fetchedMetadata.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "▶",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        fetchedMetadata.channel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    !isFetching && looksLikeYouTube && audio.youtubeTitle != null -> {
                        // Show existing stored metadata
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Stored info",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDisabled
                                )
                                Text(
                                    audio.youtubeTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                if (!audio.youtubeChannel.isNullOrBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            "▶",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                        Text(
                                            audio.youtubeChannel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    !isFetching && looksLikeYouTube && audio.youtubeTitle == null -> {
                        Text(
                            "Tap \"Fetch YouTube Info\" to auto-fill the title and channel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextDisabled
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(urlText) }) {
                Text("Save", color = TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
