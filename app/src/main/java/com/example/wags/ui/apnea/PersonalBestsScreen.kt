package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.PersonalBestEntry
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalBestsScreen(
    navController: NavController,
    viewModel: PersonalBestsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Personal Bests") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = EcgCyan) }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Group entries by trophy count for section headers
                val grouped = state.entries.groupBy { it.trophyCount }

                // 4🏆 Global
                grouped[4]?.let { entries ->
                    item {
                        SectionHeader(
                            trophies = "🏆🏆🏆🏆",
                            title = "Global Personal Best",
                            subtitle = "Best across all settings"
                        )
                    }
                    items(entries) { entry ->
                        PersonalBestRow(
                            entry = entry,
                            textStyle = TrophyTextStyle.GLOBAL,
                            onRecordClick = { recordId ->
                                navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // 3🏆 Single setting
                grouped[3]?.let { entries ->
                    item {
                        SectionHeader(
                            trophies = "🏆🏆🏆",
                            title = "Single Setting Bests",
                            subtitle = "Best for one setting (any other settings)"
                        )
                    }
                    items(entries) { entry ->
                        PersonalBestRow(
                            entry = entry,
                            textStyle = TrophyTextStyle.ONE_SETTING,
                            onRecordClick = { recordId ->
                                navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // 2🏆 Two settings
                grouped[2]?.let { entries ->
                    item {
                        SectionHeader(
                            trophies = "🏆🏆",
                            title = "Two Setting Bests",
                            subtitle = "Best for a pair of settings"
                        )
                    }
                    items(entries) { entry ->
                        PersonalBestRow(
                            entry = entry,
                            textStyle = TrophyTextStyle.TWO_SETTINGS,
                            onRecordClick = { recordId ->
                                navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // 1🏆 Exact combo
                grouped[1]?.let { entries ->
                    item {
                        SectionHeader(
                            trophies = "🏆",
                            title = "Exact Setting Bests",
                            subtitle = "Best for each specific combination"
                        )
                    }
                    items(entries) { entry ->
                        PersonalBestRow(
                            entry = entry,
                            textStyle = TrophyTextStyle.EXACT,
                            onRecordClick = { recordId ->
                                navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(trophies: String, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Text(
            "$trophies  $title",
            style = MaterialTheme.typography.titleMedium,
            color = EcgCyan,
            fontWeight = FontWeight.Bold
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        HorizontalDivider(
            color = EcgCyan.copy(alpha = 0.3f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Text style tiers — increasingly smaller text for narrower categories
// ─────────────────────────────────────────────────────────────────────────────

private enum class TrophyTextStyle {
    GLOBAL,        // largest
    ONE_SETTING,
    TWO_SETTINGS,
    EXACT          // smallest
}

// ─────────────────────────────────────────────────────────────────────────────
// Single PB row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PersonalBestRow(
    entry: PersonalBestEntry,
    textStyle: TrophyTextStyle,
    onRecordClick: (Long) -> Unit
) {
    val labelFontSize = when (textStyle) {
        TrophyTextStyle.GLOBAL       -> 18.sp
        TrophyTextStyle.ONE_SETTING  -> 16.sp
        TrophyTextStyle.TWO_SETTINGS -> 14.sp
        TrophyTextStyle.EXACT        -> 13.sp
    }
    val durationFontSize = when (textStyle) {
        TrophyTextStyle.GLOBAL       -> 20.sp
        TrophyTextStyle.ONE_SETTING  -> 17.sp
        TrophyTextStyle.TWO_SETTINGS -> 15.sp
        TrophyTextStyle.EXACT        -> 14.sp
    }
    val labelColor = when (textStyle) {
        TrophyTextStyle.GLOBAL       -> Color.White
        TrophyTextStyle.ONE_SETTING  -> Color.White.copy(alpha = 0.95f)
        TrophyTextStyle.TWO_SETTINGS -> Color.White.copy(alpha = 0.85f)
        TrophyTextStyle.EXACT        -> Color.White.copy(alpha = 0.75f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label
        Text(
            entry.label,
            fontSize = labelFontSize,
            color = labelColor,
            fontWeight = if (textStyle == TrophyTextStyle.GLOBAL) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        // Duration + date
        if (entry.durationMs != null && entry.recordId != null) {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    formatPbDuration(entry.durationMs),
                    fontSize = durationFontSize,
                    color = EcgCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    formatPbDate(entry.timestamp ?: 0L),
                    fontSize = 11.sp,
                    color = EcgCyan.copy(alpha = 0.7f),
                    modifier = Modifier.clickable { onRecordClick(entry.recordId) }
                )
            }
        } else {
            Text(
                "—",
                fontSize = durationFontSize,
                color = TextSecondary,
                textAlign = TextAlign.End
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatPbDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0)
        "${minutes}m ${seconds}.${centis.toString().padStart(2, '0')}s"
    else
        "${seconds}.${centis.toString().padStart(2, '0')}s"
}

private val pbDateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())

private fun formatPbDate(epochMs: Long): String =
    if (epochMs > 0L) pbDateFormat.format(Date(epochMs)) else ""
