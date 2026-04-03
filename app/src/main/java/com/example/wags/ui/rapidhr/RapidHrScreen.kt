package com.example.wags.ui.rapidhr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.dao.RapidHrPreset
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.util.Locale

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "%d:%02d.%d" .format(min, sec, tenths)
    else "%d.%d s".format(sec, tenths)
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RapidHrScreen(
    navController: NavController,
    viewModel: RapidHrViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isActive = state.phase == RapidHrPhase.WAITING_FIRST ||
            state.phase == RapidHrPhase.TRANSITIONING

    SessionBackHandler(enabled = isActive) { navController.popBackStack() }
    KeepScreenOn(enabled = isActive || state.phase == RapidHrPhase.COMPLETE)

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Rapid HR Change", style = MaterialTheme.typography.titleMedium) },
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
                    IconButton(onClick = { navController.navigate(WagsRoutes.RAPID_HR_HISTORY) }) {
                        Text("📊", style = MaterialTheme.typography.titleMedium)
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
            AdviceBanner(section = AdviceSection.RAPID_HR_CHANGE)

            when (state.phase) {
                RapidHrPhase.IDLE -> IdleContent(
                    state = state,
                    onSetDirection = viewModel::setDirection,
                    onSetHigh = viewModel::setHighThreshold,
                    onSetLow = viewModel::setLowThreshold,
                    onApplyPreset = viewModel::applyPreset,
                    onStart = viewModel::startSession,
                    modifier = Modifier
                )
                RapidHrPhase.WAITING_FIRST,
                RapidHrPhase.TRANSITIONING -> ActiveContent(
                    state = state,
                    onCancel = viewModel::cancelSession,
                    modifier = Modifier
                )
                RapidHrPhase.COMPLETE -> CompleteContent(
                    state = state,
                    onNewAttempt = viewModel::resetToIdle,
                    onViewDetail = {
                        state.savedSessionId?.let { id ->
                            navController.navigate(WagsRoutes.rapidHrDetail(id))
                        }
                    },
                    modifier = Modifier
                )
            }
        }
    }
}

// ── Idle ───────────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(
    state: RapidHrUiState,
    onSetDirection: (RapidHrDirection) -> Unit,
    onSetHigh: (String) -> Unit,
    onSetLow: (String) -> Unit,
    onApplyPreset: (RapidHrPreset) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Direction toggle
        item {
            Text(
                "Direction",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RapidHrDirection.entries.forEach { dir ->
                    val selected = state.direction == dir
                    Button(
                        onClick = { onSetDirection(dir) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF555555) else Color(0xFF222222),
                            contentColor = if (selected) TextPrimary else TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(dir.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }

        // Threshold inputs
        item {
            Text(
                "Thresholds (bpm)",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.highThresholdText,
                    onValueChange = onSetHigh,
                    label = { Text("High HR") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedLabelColor = TextSecondary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                OutlinedTextField(
                    value = state.lowThresholdText,
                    onValueChange = onSetLow,
                    label = { Text("Low HR") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedLabelColor = TextSecondary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
            if (!state.canStart && state.hasHrMonitor) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "High HR must be greater than Low HR (both in range 30–220)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Start button
        item {
            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF444444),
                    disabledContainerColor = Color(0xFF222222)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (!state.hasHrMonitor) "Connect HR Monitor to Start" else "Start",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Presets section
        if (state.presets.isNotEmpty()) {
            item {
                Text(
                    "Previous Settings — ${state.direction.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
            }
            items(state.presets) { preset ->
                PresetCard(
                    preset = preset,
                    isCurrentSelection = preset.highThreshold == state.highThreshold &&
                            preset.lowThreshold == state.lowThreshold,
                    onClick = { onApplyPreset(preset) }
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: RapidHrPreset,
    isCurrentSelection: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isCurrentSelection) Color(0xFF888888) else Color(0xFF333333)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentSelection) Color(0xFF2A2A2A) else SurfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "${preset.highThreshold} → ${preset.lowThreshold} bpm",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    "${preset.attemptCount} attempt${if (preset.attemptCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Best",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    formatMs(preset.bestTimeMs),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }
    }
}

// ── Active ─────────────────────────────────────────────────────────────────────

@Composable
private fun ActiveContent(
    state: RapidHrUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hr = state.liveHr
    val progress = hr?.let { state.progressToTarget(it) } ?: 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Phase label
        Text(
            text = when (state.phase) {
                RapidHrPhase.WAITING_FIRST -> "Phase 1 — Get Ready"
                RapidHrPhase.TRANSITIONING -> "Phase 2 — Transition!"
                else -> ""
            },
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary
        )

        // Instruction
        Text(
            text = state.phaseInstruction,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        // Live HR display
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(80.dp))
                .background(Color(0xFF1A1A1A))
                .border(2.dp, Color(0xFF555555), RoundedCornerShape(80.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = hr?.toString() ?: "--",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "bpm",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Progress bar toward target
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Progress to target",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = Color(0xFFD0D0D0),
                trackColor = Color(0xFF333333)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Target: ${if (state.phase == RapidHrPhase.WAITING_FIRST) state.firstThreshold else state.secondThreshold} bpm",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        // Timers
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TimerCell(
                    label = "Total",
                    value = formatMs(state.elapsedMs)
                )
                if (state.phase == RapidHrPhase.TRANSITIONING) {
                    TimerCell(
                        label = "Transition",
                        value = formatMs(state.transitionMs),
                        highlight = true
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Cancel button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            border = ButtonDefaults.outlinedButtonBorder
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun TimerCell(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) TextPrimary else Color(0xFFCCCCCC)
        )
    }
}

// ── Complete ───────────────────────────────────────────────────────────────────

@Composable
private fun CompleteContent(
    state: RapidHrUiState,
    onNewAttempt: () -> Unit,
    onViewDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // PB banner
        if (state.isPersonalBest) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "🏆 New Personal Best!",
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        }

        // Headline: transition time
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Transition Time",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Text(
                        formatMs(state.transitionMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "${state.direction.label}  •  ${state.highThreshold}→${state.lowThreshold} bpm",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // Stats grid
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Session Stats",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatCell("Phase 1", formatMs(state.phase1Ms))
                        StatCell("Total", formatMs(state.totalDurationMs))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatCell("Peak HR", "${state.peakHrBpm} bpm")
                        StatCell("Trough HR", "${state.troughHrBpm} bpm")
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatCell("HR at 1st crossing", "${state.hrAtFirstCrossing} bpm")
                        StatCell("HR at 2nd crossing", "${state.hrAtSecondCrossing} bpm")
                    }
                    state.avgHrBpm?.let { avg ->
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatCell("Avg HR", "%.1f bpm".format(avg))
                        }
                    }
                }
            }
        }

        // Action buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onViewDetail,
                    modifier = Modifier.weight(1f),
                    enabled = state.savedSessionId != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("View Detail")
                }
                Button(
                    onClick = onNewAttempt,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444))
                ) {
                    Text("New Attempt")
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}
