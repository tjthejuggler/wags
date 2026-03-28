package com.example.wags.ui.meditation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

    SessionBackHandler(enabled = isActive) { navController.popBackStack() }
    KeepScreenOn(enabled = isActive)

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
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2)
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
                    onSelectAudio = { viewModel.selectAudio(it) },
                    onEditAudioUrl = { viewModel.openUrlEditor(it) },
                    onRefreshAudios = { viewModel.refreshAudios() },
                    onSonificationToggle = { viewModel.setSonificationEnabled(!state.sonificationEnabled) },
                    onChannelFilterSelected = { viewModel.setChannelFilter(it) },
                    onPostureSelected = { viewModel.setPosture(it) },
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
    onSelectAudio: (MeditationAudioEntity) -> Unit,
    onEditAudioUrl: (MeditationAudioEntity) -> Unit,
    onRefreshAudios: () -> Unit,
    onSonificationToggle: () -> Unit,
    onChannelFilterSelected: (String?) -> Unit,
    onPostureSelected: (MeditationPosture) -> Unit,
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
        // ── Header ─────────────────────────────────────────────────────────
        item {
            Text(
                "Select Audio",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        // ── Audio directory hint ────────────────────────────────────────────
        if (state.audioDirUri.isBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ℹ️", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "No audio folder set. Go to Settings → Meditation Audio Directory to choose a folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // ── Refresh button ──────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (state.isLoadingAudios) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TextSecondary,
                        strokeWidth = 2.dp
                    )
                } else {
                    TextButton(onClick = onRefreshAudios) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(16.dp),
                            tint = TextSecondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
        }

        // ── Channel filter chips ────────────────────────────────────────────
        if (state.availableChannels.isNotEmpty()) {
            item {
                ChannelFilterRow(
                    channels = state.availableChannels,
                    selectedChannel = state.selectedChannelFilter,
                    onChannelSelected = onChannelFilterSelected
                )
            }
        }

        // ── Audio list ──────────────────────────────────────────────────────
        if (state.filteredAudios.isEmpty() && !state.isLoadingAudios) {
            item {
                Text(
                    if (state.audios.isEmpty())
                        "No audio files found. Add audio files to your chosen folder and tap Refresh."
                    else
                        "No audios match the selected filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDisabled,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
            }
        }

        items(state.filteredAudios, key = { it.audioId }) { audio ->
            AudioListItem(
                audio = audio,
                isSelected = state.selectedAudio?.audioId == audio.audioId,
                onSelect = { onSelectAudio(audio) },
                onEditUrl = { onEditAudioUrl(audio) }
            )
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

// ── Channel filter chip row ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelFilterRow(
    channels: List<String>,
    selectedChannel: String?,
    onChannelSelected: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Filter by channel",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "All" chip
            FilterChip(
                selected = selectedChannel == null,
                onClick = { onChannelSelected(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TextSecondary.copy(alpha = 0.2f),
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedChannel == null,
                        selectedBorderColor = TextSecondary,
                        borderColor = SurfaceVariant
                    )
            )
            // One chip per channel
            channels.forEach { channel ->
                FilterChip(
                    selected = selectedChannel == channel,
                    onClick = { onChannelSelected(if (selectedChannel == channel) null else channel) },
                    label = {
                        Text(
                            channel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TextSecondary.copy(alpha = 0.2f),
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedChannel == channel,
                        selectedBorderColor = TextSecondary,
                        borderColor = SurfaceVariant
                    )
                )
            }
        }
    }
}

// ── Audio list item ────────────────────────────────────────────────────────────

@Composable
private fun AudioListItem(
    audio: MeditationAudioEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEditUrl: () -> Unit
) {
    val borderColor = if (isSelected) TextSecondary else Color.Transparent
    val bgColor = if (isSelected) TextSecondary.copy(alpha = 0.08f) else SurfaceDark

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Title row: checkmark + display name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isSelected) {
                        Text("✓", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = when {
                            audio.isNone -> "None  (silent meditation)"
                            else -> audio.displayName
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) TextPrimary else TextPrimary,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Channel badge (YouTube audios only)
                if (!audio.isNone && !audio.youtubeChannel.isNullOrBlank()) {
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
                            text = audio.youtubeChannel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // For non-YouTube audios that have a URL, show the URL as a hint
                if (!audio.isNone && audio.youtubeChannel.isNullOrBlank() && audio.sourceUrl.isNotBlank()) {
                    Text(
                        text = audio.sourceUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // For non-YouTube audios, show the filename as a subtitle when the display name
                // is the YouTube title (i.e. they differ)
                if (!audio.isNone && audio.youtubeTitle != null && audio.fileName.isNotBlank()) {
                    Text(
                        text = audio.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Edit URL button (not shown for None)
            if (!audio.isNone) {
                IconButton(
                    onClick = onEditUrl,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit URL",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
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

        // Timer
        val minutes = state.elapsedSeconds / 60L
        val seconds = state.elapsedSeconds % 60L
        Text(
            "${minutes}:${String.format("%02d", seconds)}",
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimary
        )

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
