package com.example.wags.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("WAGS", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    // Live sensor readings row — shown whenever any value is available
                    if (state.liveHr != null || state.liveSpO2 != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            // Heart rate
                            state.liveHr?.let { bpm ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Heart rate",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "$bpm",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }
                            }
                            // SpO₂
                            state.liveSpO2?.let { spo2 ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "SpO₂",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "$spo2%",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { navController.navigate(WagsRoutes.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Device Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Home advice banner ────────────────────────────────────────────
            item {
                AdviceBanner(section = AdviceSection.HOME)
            }

            // ── Today's HRV Readiness card (only shown if done today) ─────────
            item {
                TodayHrvReadinessCard(
                    reading = state.todayHrvReading,
                    onClick = {
                        state.todayHrvReading?.let { reading ->
                            navController.navigate(WagsRoutes.hrvReadinessDetail(reading.readingId))
                        }
                    }
                )
            }

            // ── Today's Morning Readiness card (always shown) ─────────────────
            item {
                TodayMorningReadinessCard(
                    reading = state.todayMorningReading,
                    onClick = {
                        state.todayMorningReading?.let { reading ->
                            navController.navigate(WagsRoutes.morningReadinessDetail(reading.id))
                        }
                    }
                )
            }

            item {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                NavigationCard("Morning Readiness", "Full ANS readiness: supine → stand protocol") {
                    navController.navigate(WagsRoutes.MORNING_READINESS)
                }
            }
            item {
                NavigationCard("HRV Readiness", "Quick 2-min resting HRV measurement") {
                    navController.navigate(WagsRoutes.READINESS)
                }
            }
            item {
                NavigationCard("Resonance Breathing", "Coherence biofeedback") {
                    navController.navigate(WagsRoutes.BREATHING)
                }
            }
            item {
                NavigationCard("Apnea Training", "Free hold & table sessions") {
                    navController.navigate(WagsRoutes.APNEA_FREE)
                }
            }
            item {
                NavigationCard("Meditation / NSDR", "Audio-guided sessions with HR tracking") {
                    navController.navigate(WagsRoutes.MEDITATION)
                }
            }
        }
    }
}

// ── Today's HRV Readiness card ────────────────────────────────────────────────

@Composable
private fun TodayHrvReadinessCard(
    reading: DailyReadingEntity?,
    onClick: () -> Unit
) {
    if (reading == null) {
        // No HRV readiness done today — don't show anything
        return
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Today's HRV Readiness",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = reading.readinessScore.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ln(RMSSD)", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text(
                    String.format("%.2f", reading.lnRmssd),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text("Tap for details →", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
    }
}

// ── Today's Morning Readiness card ───────────────────────────────────────────

@Composable
private fun TodayMorningReadinessCard(
    reading: MorningReadinessEntity?,
    onClick: () -> Unit
) {
    if (reading == null) {
        // No morning readiness done today — show a placeholder card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Today's Morning Readiness",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No morning readiness done today",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextDisabled
                    )
                }
                Text("—", style = MaterialTheme.typography.headlineLarge, color = TextDisabled)
            }
        }
        return
    }

    val scoreColor = TextPrimary

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Today's Morning Readiness",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = reading.readinessScore.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    reading.readinessColor,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "RHR ${reading.supineRhr} bpm",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text("Tap for details →", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
    }
}

// ── Navigation card ───────────────────────────────────────────────────────────

@Composable
private fun NavigationCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Text("→", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
        }
    }
}
