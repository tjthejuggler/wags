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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaHistoryScreen(
    navController: NavController,
    viewModel: ApneaHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("History") },
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
            ) {
                CircularProgressIndicator(color = EcgCyan)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── Settings filter badge ──────────────────────────────────────
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Showing results for:",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(if (state.lungVolume == "PARTIAL") "Half" else state.lungVolume.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(state.prepType.displayName()) }
                        )
                    }
                }
            }

            // ── Free Hold section ──────────────────────────────────────────
            item {
                Text(
                    "Free Hold",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (state.bestFreeHoldMs > 0L) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🏆 Personal Best", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                formatDuration(state.bestFreeHoldMs),
                                style = MaterialTheme.typography.headlineSmall,
                                color = EcgCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (state.freeHoldRecords.isEmpty()) {
                item {
                    Text(
                        "No free-hold records for these settings yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(state.freeHoldRecords) { record ->
                    FreeHoldHistoryRow(
                        record = record,
                        onClick = {
                            navController.navigate(WagsRoutes.apneaRecordDetail(record.recordId))
                        }
                    )
                }
            }

            // ── Table Sessions section ─────────────────────────────────────
            item {
                HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Table Sessions",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (state.tableSessions.isEmpty()) {
                item {
                    Text(
                        "No table sessions recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(state.tableSessions) { session ->
                    TableSessionHistoryRow(session = session)
                }
            }
        }
    }
}

@Composable
private fun FreeHoldHistoryRow(record: ApneaRecordEntity, onClick: () -> Unit) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
            .format(Date(record.timestamp))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    formatDuration(record.durationMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = EcgCyan
                )
                Text(
                    dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (record.lungVolume == "PARTIAL") "Half" else record.lungVolume.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    if (record.maxHrBpm > 0f) {
                        Text(
                            "HR ${record.minHrBpm.toInt()}–${record.maxHrBpm.toInt()} bpm",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun TableSessionHistoryRow(session: ApneaSessionEntity) {
    val dateStr = remember(session.timestamp) {
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
            .format(Date(session.timestamp))
    }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    session.tableType,
                    style = MaterialTheme.typography.titleMedium,
                    color = EcgCyan
                )
                Text(
                    "${session.roundsCompleted}/${session.totalRounds} rounds  •  ${session.tableVariant}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "PB: ${session.pbAtSessionMs / 1000L}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                if (session.totalSessionDurationMs > 0L) {
                    Text(
                        formatDuration(session.totalSessionDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
