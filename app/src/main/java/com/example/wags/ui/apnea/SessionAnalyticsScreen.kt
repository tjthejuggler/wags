package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val CruisingGreen  = Color(0xFFD0D0D0)   // light grey (replaces green)
private val StruggleOrange = Color(0xFF707070)    // mid grey (replaces orange)

private const val DELTA_CHART_HELP_TITLE = "Contraction Delta Chart"
private const val DELTA_CHART_HELP_CONTENT = """
Visualizes the two phases of each breath-hold round.

Cruising Phase (green): Time from hold start to first diaphragmatic contraction.
• Represents aerobic, comfortable breath-holding
• Longer = better CO₂ tolerance

Struggle Phase (orange): Time from first contraction to hold end.
• Represents the anaerobic, high-CO₂ zone
• Training here builds mental toughness and CO₂ tolerance

Formula: Total Hold = T_cruise + T_struggle
Efficiency = T_cruise / T_total × 100%
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionAnalyticsScreen(
    navController: NavController,
    sessionId: Long,
    viewModel: SessionAnalyticsViewModel = hiltViewModel()
) {
    val contractionDeltas by viewModel.contractionDeltas.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        viewModel.loadSessionData(sessionId)
    }

    val sessionDate = remember(sessionId) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            .format(Date(sessionId))
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Session Analytics") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    val hasSensorData = LiveSensorActionsNav(navController)
                    if (!hasSensorData) {
                        IconButton(onClick = { navController.navigate(WagsRoutes.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ContractionDeltaSection(
                        sessionDate = sessionDate,
                        dataPoints = contractionDeltas
                    )
                }
            }
        }
    }
}

@Composable
private fun ContractionDeltaSection(
    sessionDate: String,
    dataPoints: List<SessionAnalyticsViewModel.ContractionDeltaPoint>
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Contraction Delta — $sessionDate",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                InfoHelpBubble(
                    title = DELTA_CHART_HELP_TITLE,
                    content = DELTA_CHART_HELP_CONTENT
                )
            }

            ContractionDeltaChart(dataPoints = dataPoints)

            ChartLegend()
        }
    }
}

@Composable
fun ContractionDeltaChart(
    dataPoints: List<SessionAnalyticsViewModel.ContractionDeltaPoint>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No contraction data for this session",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(16.dp)
    ) {
        val maxMs = dataPoints.maxOf { it.cruisingMs + it.struggleMs }.coerceAtLeast(1L)
        val barWidth = size.width / (dataPoints.size * 1.5f)
        val spacing = barWidth * 0.5f

        dataPoints.forEachIndexed { index, point ->
            val x = spacing + index * (barWidth + spacing)
            val cruisingHeight = (point.cruisingMs.toFloat() / maxMs) * size.height * 0.85f
            val struggleHeight = (point.struggleMs.toFloat() / maxMs) * size.height * 0.85f

            // Cruising (green) — bottom segment
            drawRect(
                color = CruisingGreen,
                topLeft = Offset(x, size.height - cruisingHeight - struggleHeight),
                size = Size(barWidth, cruisingHeight)
            )
            // Struggle (orange) — top segment
            drawRect(
                color = StruggleOrange,
                topLeft = Offset(x, size.height - struggleHeight),
                size = Size(barWidth, struggleHeight)
            )
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = CruisingGreen, label = "Cruising Phase")
        LegendItem(color = StruggleOrange, label = "Struggle Phase")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawRect(color = color, size = size)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}
