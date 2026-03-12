package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllApneaRecordsScreen(
    navController: NavController,
    viewModel: AllApneaRecordsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Infinite scroll: load next page when we're near the bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 5 && !state.isLoadingMore && state.hasMore
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadNextPage()
    }

    var filtersExpanded by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("All Records") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Collapsible filter section ─────────────────────────────────
            item {
                Surface(
                    color = SurfaceDark,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // Filter header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { filtersExpanded = !filtersExpanded }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Filters",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = if (filtersExpanded) Icons.Filled.KeyboardArrowUp
                                              else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (filtersExpanded) "Collapse" else "Expand",
                                tint = TextSecondary
                            )
                        }

                        if (filtersExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))

                            // ── Settings filters ───────────────────────────
                            Text(
                                "Lung Volume",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = state.filterLungVolume == "",
                                        onClick = { viewModel.setLungVolumeFilter("") },
                                        label = { Text("All") }
                                    )
                                }
                                items(listOf("FULL", "PARTIAL", "EMPTY")) { vol ->
                                    FilterChip(
                                        selected = state.filterLungVolume == vol,
                                        onClick = { viewModel.setLungVolumeFilter(vol) },
                                        label = { Text(vol.lowercase().replaceFirstChar { it.uppercase() }) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Prep Type",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = state.filterPrepType == "",
                                        onClick = { viewModel.setPrepTypeFilter("") },
                                        label = { Text("All") }
                                    )
                                }
                                items(PrepType.entries) { pt ->
                                    FilterChip(
                                        selected = state.filterPrepType == pt.name,
                                        onClick = { viewModel.setPrepTypeFilter(pt.name) },
                                        label = { Text(pt.displayName()) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Time of Day",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = state.filterTimeOfDay == "",
                                        onClick = { viewModel.setTimeOfDayFilter("") },
                                        label = { Text("All") }
                                    )
                                }
                                items(TimeOfDay.entries) { tod ->
                                    FilterChip(
                                        selected = state.filterTimeOfDay == tod.name,
                                        onClick = { viewModel.setTimeOfDayFilter(tod.name) },
                                        label = { Text(tod.displayName()) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = SurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))

                            // ── Event type filter ──────────────────────────
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Event Types",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                // Select All button
                                val allSelected = state.selectedEventTypes.size == ApneaEventType.ALL.size
                                TextButton(
                                    onClick = {
                                        if (allSelected) viewModel.clearAllEventTypes()
                                        else viewModel.selectAllEventTypes()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (allSelected) "Deselect All" else "Select All",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EcgCyan
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            // Wrap event type chips in a flow-like layout using a Column of Rows
                            val eventTypes = ApneaEventType.ALL
                            // Two chips per row for readability
                            val chunked = eventTypes.chunked(2)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                chunked.forEach { rowItems ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowItems.forEach { eventType ->
                                            val selected = state.selectedEventTypes.contains(eventType.tableTypeValue)
                                            FilterChip(
                                                selected = selected,
                                                onClick = { viewModel.toggleEventType(eventType.tableTypeValue) },
                                                label = { Text(eventType.label, style = MaterialTheme.typography.labelSmall) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        // If odd number in last row, fill remaining space
                                        if (rowItems.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        HorizontalDivider(color = SurfaceVariant)
                    }
                }
            }

            // ── Record count summary ───────────────────────────────────────
            item {
                if (!state.isInitialLoad) {
                    Text(
                        text = if (state.records.isEmpty()) "No records match the current filters."
                               else "${state.records.size}${if (state.hasMore) "+" else ""} records",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // ── Record rows ────────────────────────────────────────────────
            if (state.isInitialLoad) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = EcgCyan)
                    }
                }
            } else {
                itemsIndexed(state.records) { _, record ->
                    AllRecordsRow(
                        record = record,
                        onClick = {
                            navController.navigate(WagsRoutes.apneaRecordDetail(record.recordId))
                        }
                    )
                }
            }

            // ── Loading more indicator ─────────────────────────────────────
            if (state.isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = EcgCyan,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // ── End of list indicator ──────────────────────────────────────
            if (!state.hasMore && state.records.isNotEmpty()) {
                item {
                    Text(
                        "— End of records —",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Row composable for a single record in the All Records list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AllRecordsRow(
    record: ApneaRecordEntity,
    onClick: () -> Unit
) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }
    val eventLabel = remember(record.tableType) {
        when (record.tableType) {
            null                       -> "Free Hold"
            "O2"                       -> "O₂ Table"
            "CO2"                      -> "CO₂ Table"
            "PROGRESSIVE_O2"           -> "Progressive O₂"
            "MIN_BREATH"               -> "Min Breath"
            "WONKA_FIRST_CONTRACTION"  -> "Wonka: Contraction"
            "WONKA_ENDURANCE"          -> "Wonka: Endurance"
            else                       -> record.tableType
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatAllRecordsMs(record.durationMs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = EcgCyan,
                    fontWeight = FontWeight.SemiBold
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
                        eventLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Text(
                        "${record.lungVolume.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${record.prepType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
        }
    }
    HorizontalDivider(
        color = SurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatAllRecordsMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
