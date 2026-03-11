package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val CruisingGreen = Color(0xFF4CAF50)
private val StruggleOrange = Color(0xFFFF6B35)

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

private const val SCATTER_HELP_TITLE = "Hypoxic Resistance Scatter Plot"
private const val SCATTER_HELP_CONTENT = """
Maps your historical PB attempts against late-stage contraction frequency.

X-axis: Your Personal Best (PB) at the time of each session
Y-axis: Number of contractions in the final 30 seconds of holds

Interpretation:
• High PB + Low late contractions = High hypoxic resistance
• Low PB + High late contractions = Normal beginner pattern
• Trend moving right + down = Improving hypoxic tolerance

This chart reveals whether your PB improvements are driven by CO₂ tolerance or true O₂ efficiency.
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
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionAnalyticsHistoryScreen(
    navController: NavController,
    viewModel: SessionAnalyticsViewModel = hiltViewModel()
) {
    val hypoxicScatter by viewModel.hypoxicScatter.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadHistoricalData()
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Session Analytics") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
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
                    HypoxicScatterSection(dataPoints = hypoxicScatter)
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
private fun HypoxicScatterSection(
    dataPoints: List<SessionAnalyticsViewModel.HypoxicScatterPoint>
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Hypoxic Resistance History",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                InfoHelpBubble(
                    title = SCATTER_HELP_TITLE,
                    content = SCATTER_HELP_CONTENT
                )
            }

            HypoxicScatterPlot(dataPoints = dataPoints)

            Text(
                "Each dot = one session. X = PB at time, Y = late contractions",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
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
fun HypoxicScatterPlot(
    dataPoints: List<SessionAnalyticsViewModel.HypoxicScatterPoint>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No historical session data available",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val minPb = dataPoints.minOf { it.pbMs }
    val maxPb = dataPoints.maxOf { it.pbMs }.coerceAtLeast(minPb + 1L)
    val maxContractions = dataPoints.maxOf { it.lateContractionCount }.coerceAtLeast(1)
    val minTimestamp = dataPoints.minOf { it.sessionTimestamp }
    val maxTimestamp = dataPoints.maxOf { it.sessionTimestamp }.coerceAtLeast(minTimestamp + 1L)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(16.dp)
    ) {
        dataPoints.forEach { point ->
            val x = ((point.pbMs - minPb).toFloat() / (maxPb - minPb)) * size.width
            val y = size.height - ((point.lateContractionCount.toFloat() / maxContractions) * size.height)

            // Recency: newer sessions are brighter (higher alpha)
            val recency = (point.sessionTimestamp - minTimestamp).toFloat() /
                    (maxTimestamp - minTimestamp)
            val alpha = 0.3f + recency * 0.7f

            drawCircle(
                color = EcgCyan.copy(alpha = alpha),
                radius = 8f,
                center = Offset(x, y)
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
