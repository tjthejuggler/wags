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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
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
                    state.liveHr?.let { bpm ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Heart rate",
                                tint = ReadinessRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "$bpm",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ReadinessRed
                            )
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
            item {
                state.lastReadinessScore?.let { score ->
                    ReadinessScoreCard(score = score, lnRmssd = state.lastLnRmssd)
                }
            }
            item {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                NavigationCard("Morning Readiness", "HRV measurement & scoring") {
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
                NavigationCard("Meditation / NSDR", "Session analytics") {
                    navController.navigate(WagsRoutes.session("MEDITATION"))
                }
            }
        }
    }
}

@Composable
private fun ReadinessScoreCard(score: Int, lnRmssd: Float?) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Today's Readiness", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = score.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = when {
                        score >= 80 -> ReadinessGreen
                        score >= 60 -> ReadinessOrange
                        else -> ReadinessRed
                    }
                )
            }
            lnRmssd?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("ln(RMSSD)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        String.format("%.2f", it),
                        style = MaterialTheme.typography.headlineMedium,
                        color = EcgCyan
                    )
                }
            }
        }
    }
}

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
            Text("→", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
        }
    }
}
