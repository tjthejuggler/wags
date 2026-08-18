package com.example.wags.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.domain.usecase.apnea.HyperLockManager
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.common.LockPortrait
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LockPortrait()

    // Day-granularity "now" — recomputed per composition is fine for whole-day
    // badges (same approach as the apnea section corner badges).
    val badgeNowMs = remember { System.currentTimeMillis() }
    fun daysSinceBadge(lastUsedMs: Long?): String =
        HyperLockManager.daysSinceUsed(lastUsedMs, badgeNowMs)?.toString() ?: "∞"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("WAGS", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    val hasSensorData = LiveSensorActionsNav(navController)
                    if (!hasSensorData) {
                        IconButton(onClick = { navController.navigate(WagsRoutes.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Device Settings"
                            )
                        }
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

            // ── Separator between the today-cards and the session cards ─────────
            // Slightly thicker than the 1.dp card borders for visual separation.
            item {
                HorizontalDivider(
                    thickness = 2.dp,
                    color = CardBorder,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            item {
                NavigationCard(
                    "Morning Readiness",
                    "Full ANS readiness: supine → stand protocol",
                    daysBadge = daysSinceBadge(state.sessionLastUse.morningReadinessMs)
                ) {
                    navController.navigate(WagsRoutes.MORNING_READINESS)
                }
            }
            item {
                NavigationCard(
                    "HRV Readiness",
                    "Quick 2-min resting HRV measurement",
                    daysBadge = daysSinceBadge(state.sessionLastUse.hrvReadinessMs)
                ) {
                    navController.navigate(WagsRoutes.READINESS)
                }
            }
            item {
                NavigationCard(
                    "Resonance Breathing",
                    "Coherence biofeedback",
                    daysBadge = daysSinceBadge(state.sessionLastUse.resonanceMs)
                ) {
                    navController.navigate(WagsRoutes.BREATHING)
                }
            }
            item {
                NavigationCard(
                    "Apnea Training",
                    "Free hold & table sessions",
                    daysBadge = daysSinceBadge(state.sessionLastUse.apneaMs)
                ) {
                    navController.navigate(WagsRoutes.APNEA_FREE)
                }
            }
            item {
                NavigationCard(
                    "Meditation / NSDR",
                    "Audio-guided sessions with HR tracking",
                    daysBadge = daysSinceBadge(state.sessionLastUse.meditationMs)
                ) {
                    navController.navigate(WagsRoutes.MEDITATION)
                }
            }
            item {
                NavigationCard(
                    "Rapid HR Change",
                    "Time how fast you can shift your heart rate",
                    daysBadge = daysSinceBadge(state.sessionLastUse.rapidHrMs)
                ) {
                    navController.navigate(WagsRoutes.RAPID_HR)
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
        border = BorderStroke(1.dp, CardBorder),
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
            border = BorderStroke(1.dp, CardBorder),
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
        border = BorderStroke(1.dp, CardBorder),
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

/**
 * Tiny bordered number badge — same style as the apnea section corner badges.
 * Shows whole days since the session type was last done (∞ when never).
 */
@Composable
private fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 9.sp,
        lineHeight = 10.sp,
        color = TextPrimary,
        modifier = modifier
            .border(1.dp, TextSecondary, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    daysBadge: String? = null,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    // Keep the trailing arrow clear of the corner badge that
                    // floats at the card's top-right edge.
                    .then(if (daysBadge != null) Modifier.padding(end = 20.dp) else Modifier),
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

        // Days-since badge floats in the card's top-right corner.
        daysBadge?.let {
            CornerBadge(
                text = it,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 4.dp)
            )
        }
    }
}
