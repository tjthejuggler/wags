package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Drill-down from a Stats tab extremes label (e.g. "Lowest SpO₂", "Highest HR"):
 * every hold that has a value for that metric, as cards sorted best-first.
 * Tapping a card opens the full record detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldsRankedListScreen(
    onBack: () -> Unit,
    onRecordClick: (Long) -> Unit,
    viewModel: HoldsRankedListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            if (state.items.isEmpty()) "No data"
                            else "${state.items.size} holds · sorted ${if (state.ascending) "lowest" else "highest"} first",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TextSecondary)
            }
            state.items.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No holds with this metric yet", color = TextDisabled)
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.recordId }) { item ->
                    RankedHoldCard(
                        item = item,
                        unit = state.unit,
                        onClick = { onRecordClick(item.recordId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RankedHoldCard(
    item: RankedHoldItem,
    unit: String,
    onClick: () -> Unit
) {
    val dateFmt = remember {
        DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault())
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "#${item.rank}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
            }

            Spacer(Modifier.width(14.dp))

            // Metric value + context
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${item.metricValue.toInt()}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    "${dateFmt.format(Instant.ofEpochMilli(item.timestamp))}  ·  ${item.drillLabel}  ·  ${formatRankDuration(item.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                // Secondary metrics
                val secondary = buildList {
                    item.minHrBpm?.let { add("Min HR ${it.toInt()}") }
                    item.maxHrBpm?.let { add("Max HR ${it.toInt()}") }
                    item.lowestSpO2?.let { add("SpO₂ $it%") }
                }
                if (secondary.isNotEmpty()) {
                    Text(
                        secondary.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        fontSize = 10.sp
                    )
                }
            }

            Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        }
    }
}

/** Formats a duration in ms as "Xm Ys" / "Ys". */
private fun formatRankDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
