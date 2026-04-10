package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
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

    Scaffold(
        containerColor = BackgroundDark,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Text("Lung Volume", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = state.filterLungVolume == "",
                                            onClick = { viewModel.setLungVolumeFilter("") },
                                            label = { Text("All", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.height(30.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SurfaceVariant,
                                                selectedLabelColor = TextPrimary
                                            )
                                        )
                                        listOf("FULL", "PARTIAL", "EMPTY").forEach { vol ->
                                            FilterChip(
                                                selected = state.filterLungVolume == vol,
                                                onClick = { viewModel.setLungVolumeFilter(vol) },
                                                label = { Text(if (vol == "PARTIAL") "Half" else vol.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.height(30.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = SurfaceVariant,
                                                    selectedLabelColor = TextPrimary
                                                )
                                            )
                                        }
                                    }

                                    Text("Prep Type", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = state.filterPrepType == "",
                                            onClick = { viewModel.setPrepTypeFilter("") },
                                            label = { Text("All", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.height(30.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SurfaceVariant,
                                                selectedLabelColor = TextPrimary
                                            )
                                        )
                                        PrepType.entries.forEach { pt ->
                                            FilterChip(
                                                selected = state.filterPrepType == pt.name,
                                                onClick = { viewModel.setPrepTypeFilter(pt.name) },
                                                label = { Text(pt.displayName(), style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.height(30.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = SurfaceVariant,
                                                    selectedLabelColor = TextPrimary
                                                )
                                            )
                                        }
                                    }

                                    Text("Time of Day", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = state.filterTimeOfDay == "",
                                            onClick = { viewModel.setTimeOfDayFilter("") },
                                            label = { Text("All", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.height(30.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SurfaceVariant,
                                                selectedLabelColor = TextPrimary
                                            )
                                        )
                                        TimeOfDay.entries.forEach { tod ->
                                            FilterChip(
                                                selected = state.filterTimeOfDay == tod.name,
                                                onClick = { viewModel.setTimeOfDayFilter(tod.name) },
                                                label = { Text(tod.displayName(), style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.height(30.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = SurfaceVariant,
                                                    selectedLabelColor = TextPrimary
                                                )
                                            )
                                        }
                                    }

                                    Text("Posture", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = state.filterPosture == "",
                                            onClick = { viewModel.setPostureFilter("") },
                                            label = { Text("All", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.height(30.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SurfaceVariant,
                                                selectedLabelColor = TextPrimary
                                            )
                                        )
                                        Posture.entries.forEach { pos ->
                                            FilterChip(
                                                selected = state.filterPosture == pos.name,
                                                onClick = { viewModel.setPostureFilter(pos.name) },
                                                label = { Text(pos.displayName(), style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.height(30.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = SurfaceVariant,
                                                    selectedLabelColor = TextPrimary
                                                )
                                            )
                                        }
                                    }

                                    Text("Audio", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = state.filterAudio == "",
                                            onClick = { viewModel.setAudioFilter("") },
                                            label = { Text("All", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.height(30.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = SurfaceVariant,
                                                selectedLabelColor = TextPrimary
                                            )
                                        )
                                        AudioSetting.entries.forEach { audio ->
                                            FilterChip(
                                                selected = state.filterAudio == audio.name,
                                                onClick = { viewModel.setAudioFilter(audio.name) },
                                                label = { Text(audio.displayName(), style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.height(30.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = SurfaceVariant,
                                                    selectedLabelColor = TextPrimary
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = SurfaceVariant.copy(alpha = 0.5f))

                            // ── Collapsible Event Types section ────────────
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
                                        // Show current event type summary when collapsed
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
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    // Select All / Deselect All
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        val allSelected = ApneaEventType.ALL
                                            .all { state.selectedEventTypes.contains(it.tableTypeValue) }
                                        TextButton(
                                            onClick = {
                                                if (allSelected) viewModel.clearAllEventTypes()
                                                else viewModel.selectAllEventTypes()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                if (allSelected) "Deselect All" else "Select All",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    ApneaEventType.ALL.chunked(2).forEach { rowItems ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            rowItems.forEach { eventType ->
                                                FilterChip(
                                                    selected = state.selectedEventTypes.contains(eventType.tableTypeValue),
                                                    onClick = { viewModel.toggleEventType(eventType.tableTypeValue) },
                                                    label = { Text(eventType.label, style = MaterialTheme.typography.bodySmall) },
                                                    modifier = Modifier.weight(1f).height(30.dp),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = SurfaceVariant,
                                                        selectedLabelColor = TextPrimary
                                                    )
                                                )
                                            }
                                            if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Progress chart (shown only when exactly one event type selected) ──
            state.chartPoints?.let { points ->
                if (points.isNotEmpty()) {
                    item {
                        ApneaProgressChart(
                            points = points,
                            yLabel = state.chartYLabel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ── Record count summary ───────────────────────────────────────
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
 * A simple Canvas-based line chart that plots [points] (oldest→newest left→right).
 * The Y axis is labelled with [yLabel].
 *
 * For "Hold duration" the Y values are in milliseconds; we display them as
 * human-readable time strings on the axis.
 */
@Composable
private fun ApneaProgressChart(
    points: List<ChartPoint>,
    yLabel: String,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        // Single point — just show a note
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Not enough data to plot a chart yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        return
    }

    val isDuration = yLabel == "Hold duration" || yLabel == "Total hold time"

    val lineColor   = TextPrimary
    val dotColor    = TextPrimary
    val axisColor   = TextSecondary
    val gridColor   = SurfaceVariant.copy(alpha = 0.5f)
    val labelColor  = TextSecondary

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Progress",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    yLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val yRange = (maxY - minY).coerceAtLeast(1f)

            val minX = points.first().x
            val maxX = points.last().x
            val xRange = (maxX - minX).coerceAtLeast(1L).toFloat()

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                val leftPad   = 64f
                val rightPad  = 16f
                val topPad    = 16f
                val bottomPad = 32f

                val chartW = size.width  - leftPad - rightPad
                val chartH = size.height - topPad  - bottomPad

                // ── Grid lines (4 horizontal) ──────────────────────────────
                val gridSteps = 4
                for (i in 0..gridSteps) {
                    val fraction = i.toFloat() / gridSteps
                    val y = topPad + chartH * (1f - fraction)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end   = Offset(leftPad + chartW, y),
                        strokeWidth = 1f
                    )
                    // Y axis label
                    val yVal = minY + yRange * fraction
                    val labelText = if (isDuration) formatChartMs(yVal.toLong()) else "%.1f".format(yVal)
                    drawContext.canvas.nativeCanvas.drawText(
                        labelText,
                        leftPad - 4f,
                        y + 4f,
                        android.graphics.Paint().apply {
                            color     = labelColor.toArgb()
                            textSize  = 22f
                            textAlign = android.graphics.Paint.Align.RIGHT
                            isAntiAlias = true
                        }
                    )
                }

                // ── X axis ────────────────────────────────────────────────
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad, topPad + chartH),
                    end   = Offset(leftPad + chartW, topPad + chartH),
                    strokeWidth = 1.5f
                )

                // ── Line path ─────────────────────────────────────────────
                val path = Path()
                points.forEachIndexed { idx, pt ->
                    val px = leftPad + ((pt.x - minX).toFloat() / xRange) * chartW
                    val py = topPad  + chartH * (1f - (pt.y - minY) / yRange)
                    if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path  = path,
                    color = lineColor,
                    style = Stroke(
                        width     = 2.5f,
                        cap       = StrokeCap.Round,
                        join      = StrokeJoin.Round
                    )
                )

                // ── Dots ──────────────────────────────────────────────────
                points.forEach { pt ->
                    val px = leftPad + ((pt.x - minX).toFloat() / xRange) * chartW
                    val py = topPad  + chartH * (1f - (pt.y - minY) / yRange)
                    drawCircle(color = dotColor, radius = 4f, center = Offset(px, py))
                    drawCircle(color = BackgroundDark, radius = 2f, center = Offset(px, py))
                }

                // ── X axis date labels (first and last) ───────────────────
                val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
                val firstLabel = dateFmt.format(Date(points.first().x))
                val lastLabel  = dateFmt.format(Date(points.last().x))
                val xLabelY    = topPad + chartH + 22f
                val paint = android.graphics.Paint().apply {
                    color       = labelColor.toArgb()
                    textSize    = 22f
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(firstLabel, leftPad, xLabelY, paint.apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                })
                drawContext.canvas.nativeCanvas.drawText(lastLabel, leftPad + chartW, xLabelY, paint.apply {
                    textAlign = android.graphics.Paint.Align.RIGHT
                })
            }
        }
    }
}

/** Format milliseconds as a compact time string for chart axis labels. */
private fun formatChartMs(ms: Long): String {
    val totalSecs = ms / 1000L
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return if (mins > 0) "${mins}m${secs}s" else "${secs}s"
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
                if (record.tableType != null) {
                    // Table record: show table type as primary, duration as secondary
                    Text(
                        eventLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = EcgCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (record.tableType == "MIN_BREATH")
                            "Total hold time: ${formatAllRecordsMs(record.durationMs)}"
                        else
                            "Longest hold: ${formatAllRecordsMs(record.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else {
                    // Free hold: show duration as primary
                    Text(
                        formatAllRecordsMs(record.durationMs),
                        style = MaterialTheme.typography.bodyLarge,
                        color = EcgCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
                    if (record.tableType == null) {
                        Text(
                            eventLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
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
