package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.common.LiveSensorActionsNav
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

    // Reload list when we return to this screen (e.g. after deleting a record in the detail screen)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(navBackStackEntry) {
        val deletedId = navBackStackEntry
            ?.savedStateHandle
            ?.remove<Long>("deletedRecordId")
        if (deletedId != null) {
            viewModel.removeRecord(deletedId)
        }
    }

    // Section collapse states — both collapsed by default
    var filtersExpanded by remember { mutableStateOf(false) }
    var eventTypesExpanded by remember { mutableStateOf(false) }

    // Sort popup state
    var showSortMenu by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // ── Header surface (Sort + Filters + Event Types) ──────────────
        item {
            Surface(
                color = SurfaceDark,
                tonalElevation = 2.dp
            ) {
                Column {
                    // ── Sort row (same style as Filters / Event Types) ──
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showSortMenu = true }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Sort",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        state.sortOrder.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Sort options",
                                        tint = TextSecondary
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                containerColor = SurfaceDark
                            ) {
                                RecordSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                order.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (state.sortOrder == order) TextPrimary else TextSecondary,
                                                fontWeight = if (state.sortOrder == order) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = SurfaceVariant.copy(alpha = 0.5f))

                    // ── Collapsible Filters section ────────────────────
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        // Filters header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { filtersExpanded = !filtersExpanded }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Filters",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (!filtersExpanded) {
                                    // Show current filter summary when collapsed
                                    Text(
                                        buildFilterSummary(state),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Icon(
                                    imageVector = if (filtersExpanded) Icons.Filled.KeyboardArrowUp
                                                  else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (filtersExpanded) "Collapse" else "Expand",
                                    tint = TextSecondary
                                )
                            }
                        }

                        if (filtersExpanded) {
                            Column(
                                modifier = Modifier.padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Lung Volume
                                FilterRow(label = "Lung Volume") {
                                    FilterChip(
                                        selected = state.filterLungVolume == "",
                                        onClick = { viewModel.setLungVolumeFilter("") },
                                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    listOf("FULL", "PARTIAL", "EMPTY").forEach { lv ->
                                        FilterChip(
                                            selected = state.filterLungVolume == lv,
                                            onClick = { viewModel.setLungVolumeFilter(lv) },
                                            label = { Text(if (lv == "PARTIAL") "Half" else lv.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                // Prep Type
                                FilterRow(label = "Prep") {
                                    FilterChip(
                                        selected = state.filterPrepType == "",
                                        onClick = { viewModel.setPrepTypeFilter("") },
                                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    PrepType.entries.forEach { pt ->
                                        FilterChip(
                                            selected = state.filterPrepType == pt.name,
                                            onClick = { viewModel.setPrepTypeFilter(pt.name) },
                                            label = { Text(pt.displayName(), style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                // Time of Day
                                FilterRow(label = "Time of Day") {
                                    FilterChip(
                                        selected = state.filterTimeOfDay == "",
                                        onClick = { viewModel.setTimeOfDayFilter("") },
                                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    TimeOfDay.entries.forEach { tod ->
                                        FilterChip(
                                            selected = state.filterTimeOfDay == tod.name,
                                            onClick = { viewModel.setTimeOfDayFilter(tod.name) },
                                            label = { Text(tod.displayName(), style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                // Posture
                                FilterRow(label = "Posture") {
                                    FilterChip(
                                        selected = state.filterPosture == "",
                                        onClick = { viewModel.setPostureFilter("") },
                                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    Posture.entries.forEach { pos ->
                                        FilterChip(
                                            selected = state.filterPosture == pos.name,
                                            onClick = { viewModel.setPostureFilter(pos.name) },
                                            label = { Text(pos.displayName(), style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                // Audio
                                FilterRow(label = "Audio") {
                                    FilterChip(
                                        selected = state.filterAudio == "",
                                        onClick = { viewModel.setAudioFilter("") },
                                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    AudioSetting.entries.forEach { aud ->
                                        FilterChip(
                                            selected = state.filterAudio == aud.name,
                                            onClick = { viewModel.setAudioFilter(aud.name) },
                                            label = { Text(aud.displayName(), style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = SurfaceVariant.copy(alpha = 0.5f))

                    // ── Collapsible Event Types section ────────────────
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { eventTypesExpanded = !eventTypesExpanded }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Event Types",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (!eventTypesExpanded) {
                                    Text(
                                        buildEventTypeSummary(state),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Icon(
                                    imageVector = if (eventTypesExpanded) Icons.Filled.KeyboardArrowUp
                                                  else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (eventTypesExpanded) "Collapse" else "Expand",
                                    tint = TextSecondary
                                )
                            }
                        }

                        if (eventTypesExpanded) {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ApneaEventType.ALL.forEach { type ->
                                    val isSelected = state.selectedEventTypes.contains(type.tableTypeValue)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.toggleEventType(type.tableTypeValue) },
                                        label = { Text(type.label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Progress chart section ─────────────────────────────────────
        if (state.records.size >= 2) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "Progress",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    AllRecordsProgressChart(
                        records = state.records,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )
                }
            }
        }

        // ── Record count header ────────────────────────────────────────
        item {
            if (!state.isInitialLoad && !state.isLoading) {
                Text(
                    text = if (state.records.isEmpty()) "No records match the current filters."
                           else "${state.records.size} records",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // ── Record rows ────────────────────────────────────────────────
        if (state.isInitialLoad || state.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TextSecondary)
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

        // ── End of list indicator ──────────────────────────────────────
        if (state.records.isNotEmpty() && !state.isLoading) {
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

// ─────────────────────────────────────────────────────────────────────────────
// Collapsed-state summary helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun buildFilterSummary(state: AllApneaRecordsUiState): String {
    val parts = mutableListOf<String>()
    if (state.filterLungVolume.isNotEmpty()) parts.add(
        when (state.filterLungVolume) {
            "PARTIAL" -> "Half"
            else -> state.filterLungVolume.lowercase().replaceFirstChar { it.uppercase() }
        }
    )
    if (state.filterPrepType.isNotEmpty()) parts.add(
        runCatching { PrepType.valueOf(state.filterPrepType).displayName() }
            .getOrDefault(state.filterPrepType)
    )
    if (state.filterTimeOfDay.isNotEmpty()) parts.add(
        runCatching { TimeOfDay.valueOf(state.filterTimeOfDay).displayName() }
            .getOrDefault(state.filterTimeOfDay)
    )
    if (state.filterPosture.isNotEmpty()) parts.add(
        runCatching { Posture.valueOf(state.filterPosture).displayName() }
            .getOrDefault(state.filterPosture)
    )
    if (state.filterAudio.isNotEmpty()) parts.add(
        runCatching { AudioSetting.valueOf(state.filterAudio).displayName() }
            .getOrDefault(state.filterAudio)
    )
    return if (parts.isEmpty()) "All" else parts.joinToString(" · ")
}

private fun buildEventTypeSummary(state: AllApneaRecordsUiState): String {
    val allCount = ApneaEventType.ALL.size
    val selectedCount = ApneaEventType.ALL.count { state.selectedEventTypes.contains(it.tableTypeValue) }
    return when {
        selectedCount == 0 -> "None"
        selectedCount == allCount -> "All"
        selectedCount == 1 -> ApneaEventType.ALL
            .firstOrNull { state.selectedEventTypes.contains(it.tableTypeValue) }?.label ?: "1 type"
        else -> "$selectedCount types"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Progress chart composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A simple line chart showing hold duration progress over the current filtered list.
 */
@Composable
private fun AllRecordsProgressChart(
    records: List<ApneaRecordEntity>,
    modifier: Modifier = Modifier
) {
    // We want to show progress in chronological order (oldest to newest)
    // The list is usually newest first, so we reverse it for the chart.
    val chartRecords = remember(records) { records.reversed() }
    val durations = chartRecords.map { it.durationMs.toFloat() / 1000f }
    
    if (durations.isEmpty()) return

    val maxDuration = durations.maxOrNull() ?: 1f
    val minDuration = durations.minOrNull() ?: 0f
    val range = (maxDuration - minDuration).coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val spacing = width / (durations.size - 1).coerceAtLeast(1)

        val path = Path()
        durations.forEachIndexed { index, duration ->
            val x = index * spacing
            val normalized = (duration - minDuration) / range
            val y = height - (normalized * height)
            
            if (index == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = EcgCyan,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
        
        // Draw dots for each point
        durations.forEachIndexed { index, duration ->
            val x = index * spacing
            val normalized = (duration - minDuration) / range
            val y = height - (normalized * height)
            drawCircle(
                color = EcgCyan,
                radius = 3.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Components
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(label: String, chips: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { chips() }
    }
}

@Composable
private fun AllRecordsRow(
    record: ApneaRecordEntity,
    onClick: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault()) }
    val dateStr = remember(record.timestamp) { sdf.format(Date(record.timestamp)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatAllRecordsMs(record.durationMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Surface(
                        color = SurfaceVariant.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = record.tableType?.replace("_", " ") ?: "FREE HOLD",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (record.minHrBpm > 0) {
                        Text(
                            "Min HR: ${record.minHrBpm.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    if (record.lowestSpO2 != null && record.lowestSpO2 > 0) {
                        Text(
                            "SpO₂: ${record.lowestSpO2}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                Text(
                    "${if (record.lungVolume == "PARTIAL") "Half" else record.lungVolume.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${record.prepType.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}  ·  ${record.posture.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
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
