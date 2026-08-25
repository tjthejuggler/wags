package com.example.wags.ui.apnea

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeDimension
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllApneaRecordsScreen(
    navController: NavController,
    viewModel: AllApneaRecordsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeDimension by viewModel.timeDimension.collectAsStateWithLifecycle()
    val byHour = timeDimension == TimeDimension.BY_HOUR
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Most recent record — the "Current" target for the filter header toggles.
    val newestRecord = remember(state.records) { state.records.maxByOrNull { it.timestamp } }

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

    // Record row targeted by a chart-node jump — pulses for a few seconds.
    var highlightRecordId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(highlightRecordId) {
        if (highlightRecordId != null) {
            kotlinx.coroutines.delay(4000)
            highlightRecordId = null
        }
    }

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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
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
                            // Anchor box around the right-side label so the
                            // dropdown opens right beneath it, not far left.
                            Box {
                                Row(
                                    modifier = Modifier
                                        .clickable { showSortMenu = true }
                                        .padding(vertical = 2.dp, horizontal = 4.dp),
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
                                        buildFilterSummary(state, byHour),
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
                                MultiSelectFilterCategory(
                                    label = "Lung Volume",
                                    options = SettingFilterOptions.LUNG_VOLUMES,
                                    optionLabel = SettingFilterOptions::lungVolumeLabel,
                                    selected = state.filterLungVolume,
                                    currentValue = newestRecord?.lungVolume,
                                    onSelectionChange = { viewModel.setLungVolumeFilter(it) }
                                )

                                // Prep Type
                                MultiSelectFilterCategory(
                                    label = "Prep",
                                    options = SettingFilterOptions.PREP_TYPES,
                                    optionLabel = SettingFilterOptions::prepTypeShortLabel,
                                    selected = state.filterPrepType,
                                    currentValue = newestRecord?.prepType,
                                    onSelectionChange = { viewModel.setPrepTypeFilter(it) }
                                )

                                // Time of Day / Hour Bucket
                                MultiSelectFilterCategory(
                                    label = if (byHour) "Hour" else "Time of Day",
                                    options = SettingFilterOptions.timeOfDayOptions(byHour),
                                    optionLabel = SettingFilterOptions::timeBucketLabel,
                                    selected = state.filterTimeOfDay,
                                    currentValue = newestRecord?.let {
                                        if (byHour) TimeBuckets.fromTimestamp(it.timestamp) else it.timeOfDay
                                    },
                                    onSelectionChange = { viewModel.setTimeOfDayFilter(it) }
                                )

                                // Posture
                                MultiSelectFilterCategory(
                                    label = "Posture",
                                    options = SettingFilterOptions.POSTURES,
                                    optionLabel = SettingFilterOptions::postureLabel,
                                    selected = state.filterPosture,
                                    currentValue = newestRecord?.posture,
                                    onSelectionChange = { viewModel.setPostureFilter(it) }
                                )

                                // Audio
                                MultiSelectFilterCategory(
                                    label = "Audio",
                                    options = SettingFilterOptions.AUDIOS,
                                    optionLabel = SettingFilterOptions::audioLabel,
                                    selected = state.filterAudio,
                                    currentValue = newestRecord?.audio,
                                    onSelectionChange = { viewModel.setAudioFilter(it) }
                                )
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
                                        label = { Text(type.label, style = MaterialTheme.typography.labelSmall) },
                                        colors = settingFilterChipColors()
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
                            .height(120.dp),
                        onPointTap = { record ->
                            highlightRecordId = record.recordId
                            val listIndex = state.records.indexOfFirst { it.recordId == record.recordId }
                            if (listIndex >= 0) {
                                // LazyColumn items before the record rows:
                                // header surface, chart, record-count label.
                                scope.launch { listState.animateScrollToItem(3 + listIndex) }
                            }
                        }
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
                    highlight = record.recordId == highlightRecordId,
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

private fun buildFilterSummary(state: AllApneaRecordsUiState, byHour: Boolean): String {
    val parts = listOfNotNull(
        settingFilterSummaryPart(state.filterLungVolume, SettingFilterOptions.LUNG_VOLUMES, SettingFilterOptions::lungVolumeLabel),
        settingFilterSummaryPart(state.filterPrepType, SettingFilterOptions.PREP_TYPES, SettingFilterOptions::prepTypeLabel),
        settingFilterSummaryPart(state.filterTimeOfDay, SettingFilterOptions.timeOfDayOptions(byHour), SettingFilterOptions::timeBucketLabel),
        settingFilterSummaryPart(state.filterPosture, SettingFilterOptions.POSTURES, SettingFilterOptions::postureLabel),
        settingFilterSummaryPart(state.filterAudio, SettingFilterOptions.AUDIOS, SettingFilterOptions::audioLabel)
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
 * A pinch-zoomable line chart showing hold duration progress over the current
 * filtered list. A prominent white trend line — a centered moving average of
 * the visible points — is drawn LAST so it sits on top of everything else.
 * Tapping near a point shows an info bubble for it; the bubble's button fires
 * [onPointTap] with the record it represents.
 */
@Composable
private fun AllRecordsProgressChart(
    records: List<ApneaRecordEntity>,
    modifier: Modifier = Modifier,
    onPointTap: (ApneaRecordEntity) -> Unit = {}
) {
    // Chronological order (oldest → newest); the list is usually newest first.
    val chartRecords = remember(records) { records.reversed() }
    val durations = chartRecords.map { it.durationMs.toFloat() / 1000f }
    if (durations.size < 2) return

    val total = durations.size

    // Pinch-zoom window over the chronological index space:
    // zoom = 1f fits everything; panPos ∈ [0, 1] slides the window.
    var zoom by remember(records) { mutableStateOf(1f) }
    var panPos by remember(records) { mutableStateOf(0f) }
    var chartWidthPx by remember { mutableStateOf(0f) }
    // Chronological index of the point whose info bubble is showing.
    var selectedIdx by remember(records) { mutableStateOf<Int?>(null) }

    val maxZoom = (total / 2f).coerceAtLeast(1f)
    fun visibleCountFor(z: Float): Int = maxOf(2, ceil(total / z).toInt()).coerceAtMost(total)

    val visibleCount = visibleCountFor(zoom)
    val startIdx = (panPos * (total - visibleCount)).roundToInt().coerceIn(0, total - visibleCount)
    val visible = durations.subList(startIdx, (startIdx + visibleCount).coerceAtMost(total))

    val maxDuration = visible.maxOrNull() ?: 1f
    val minDuration = visible.minOrNull() ?: 0f
    val range = (maxDuration - minDuration).coerceAtLeast(1f)

    // Smoothed trend — centered moving average of the visible points.
    val smoothWindow = maxOf(3, (visible.size / 6) or 1)
    val smoothed = visible.mapIndexed { i, _ ->
        val from = maxOf(0, i - smoothWindow / 2)
        val to = minOf(visible.size, i + smoothWindow / 2 + 1)
        visible.subList(from, to).average().toFloat()
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { chartWidthPx = it.width.toFloat() }
                .pointerInput(chartRecords, zoom, panPos) {
                    detectTapGestures { offset ->
                        val vc = visibleCountFor(zoom)
                        val spacing = (chartWidthPx / (vc - 1).coerceAtLeast(1)).coerceAtLeast(1f)
                        // Nearest visible point by 2-D distance, generous radius.
                        var best = -1
                        var bestDist = Float.MAX_VALUE
                        for (i in 0 until vc) {
                            val px = i * spacing
                            val dur = durations.getOrNull(startIdx + i) ?: continue
                            val py = size.height - (((dur - minDuration) / range) * size.height)
                            val d = hypot(offset.x - px, offset.y - py)
                            if (d < bestDist) { bestDist = d; best = i }
                        }
                        val radius = 36.dp.toPx()
                        selectedIdx = if (best >= 0 && bestDist <= radius) startIdx + best else null
                    }
                }
                .pointerInput(total) {
                    // Custom transform handler: only pinch zooms and horizontal
                    // pans are consumed — vertical swipes fall through so the
                    // surrounding LazyColumn still scrolls.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.changes.any { it.pressed }) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val horizontal = abs(panChange.x) > abs(panChange.y)
                            if (zoomChange != 1f || horizontal) {
                                zoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
                                val vc = visibleCountFor(zoom)
                                if (chartWidthPx > 0f && vc < total && panChange.x != 0f) {
                                    val shift = -panChange.x / chartWidthPx * (vc.toFloat() / total)
                                    panPos = (panPos + shift).coerceIn(0f, 1f)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val spacing = width / (visible.size - 1).coerceAtLeast(1)

            val path = Path()
            visible.forEachIndexed { index, duration ->
                val x = index * spacing
                val normalized = (duration - minDuration) / range
                val y = height - (normalized * height)

                if (index == 0) path.moveTo(x, y)
                else path.lineTo(x, y)
            }

            // Connecting line — kept subtle so the trend line dominates.
            drawPath(
                path = path,
                color = EcgCyan.copy(alpha = 0.45f),
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Dots for each visible point — with a dark halo so they stay
            // clearly visible against the background and the trend line.
            visible.forEachIndexed { index, duration ->
                val x = index * spacing
                val normalized = (duration - minDuration) / range
                val y = height - (normalized * height)
                drawCircle(
                    color = SurfaceDark,
                    radius = 4.5.dp.toPx(),
                    center = Offset(x, y)
                )
                drawCircle(
                    color = EcgCyan,
                    radius = 3.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            // Ring around the selected point (anchor of the info bubble).
            selectedIdx?.let { sel ->
                val slice = sel - startIdx
                if (slice in visible.indices) {
                    val x = slice * spacing
                    val y = height - (((visible[slice] - minDuration) / range) * height)
                    drawCircle(
                        color = CoherenceWhite.copy(alpha = 0.8f),
                        radius = 6.dp.toPx(),
                        center = Offset(x, y),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // Smoothed trend line — the moving average of the visible points.
            // Drawn LAST so it sits on top of everything else; this is the
            // most important mark.
            val trendPath = Path()
            smoothed.forEachIndexed { index, value ->
                val x = index * spacing
                val y = height - (((value - minDuration) / range) * height)
                if (index == 0) trendPath.moveTo(x, y)
                else trendPath.lineTo(x, y)
            }
            drawPath(
                path = trendPath,
                color = CoherenceWhite,
                style = Stroke(
                    width = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // Info bubble for the selected point.
        val sel = selectedIdx
        if (sel != null && sel >= startIdx && sel < startIdx + visible.size) {
            val selRecord = chartRecords.getOrNull(sel)
            if (selRecord != null && chartWidthPx > 0f) {
                val spacingPx = chartWidthPx / (visible.size - 1).coerceAtLeast(1)
                val pointX = (sel - startIdx) * spacingPx
                ChartPointBubble(
                    record = selRecord,
                    pointX = pointX,
                    chartWidthPx = chartWidthPx,
                    onGoToRecord = { onPointTap(selRecord) },
                    onDismiss = { selectedIdx = null },
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    }
}

/**
 * Small info bubble shown above the chart at [pointX] for the tapped point.
 * Its "Go to record" button fires [onGoToRecord]; tapping ✕ dismisses.
 */
@Composable
private fun ChartPointBubble(
    record: ApneaRecordEntity,
    pointX: Float,
    chartWidthPx: Float,
    onGoToRecord: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val bubbleWidth = 200.dp
    val widthPx = with(density) { bubbleWidth.toPx() }
    val xPx = (pointX - widthPx / 2f).coerceIn(0f, (chartWidthPx - widthPx).coerceAtLeast(0f))
    val sdf = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault()) }

    Surface(
        modifier = modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(bubbleWidth),
        shape = RoundedCornerShape(10.dp),
        color = SurfaceDark,
        contentColor = TextPrimary,
        border = BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatAllRecordsMs(record.durationMs),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp)
                )
            }
            Text(
                sdf.format(Date(record.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                record.tableType?.replace("_", " ") ?: "FREE HOLD",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Button(
                onClick = onGoToRecord,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonPrimary,
                    contentColor = TextPrimary
                )
            ) {
                Text("Go to record", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Components
// ─────────────────────────────────────────────────────────────────────────────

// FilterRow was replaced by the shared MultiSelectFilterCategory (SettingFilterWidgets.kt).

@Composable
private fun AllRecordsRow(
    record: ApneaRecordEntity,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault()) }
    val dateStr = remember(record.timestamp) { sdf.format(Date(record.timestamp)) }

    // Pulsing glow while this row is the target of a chart-node jump.
    val glowAlpha by if (highlight) {
        rememberInfiniteTransition(label = "rowGlow").animateFloat(
            initialValue = 0.10f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(650),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlight) CoherenceWhite.copy(alpha = glowAlpha) else Color.Transparent)
    ) {
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
