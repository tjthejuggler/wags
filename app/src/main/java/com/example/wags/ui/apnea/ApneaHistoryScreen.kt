package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class ApneaHistoryTab(val label: String) {
    GRAPHS("Graphs"),
    ALL_RECORDS("All Records"),
    STATS("Stats"),
    CALENDAR("Calendar")
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaHistoryScreen(
    navController: NavController,
    viewModel: ApneaHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(ApneaHistoryTab.GRAPHS.ordinal) }
    val selectedTab = ApneaHistoryTab.entries[selectedTabOrdinal]
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    // Single-session auto-navigate: when exactly 1 record is selected, navigate immediately
    val selectedDayRecords = state.selectedDayRecords
    LaunchedEffect(selectedDayRecords) {
        if (selectedDayRecords.size == 1) {
            navController.navigate(WagsRoutes.apneaRecordDetail(selectedDayRecords.first().recordId))
            viewModel.clearSelection()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    LiveSensorActionsNav(navController)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = SurfaceDark,
                contentColor = TextSecondary,
                edgePadding = 8.dp
            ) {
                ApneaHistoryTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTabOrdinal = tab.ordinal },
                        modifier = Modifier.background(
                            if (isSelected) TextSecondary.copy(alpha = 0.15f)
                            else Color.Transparent
                        ),
                        text = {
                            Text(
                                tab.label,
                                color = if (isSelected) TextPrimary else TextDisabled,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                ApneaHistoryTab.GRAPHS -> GraphsTabContent(
                    chartData = state.chartData,
                    readingCount = state.totalFreeHoldCount,
                    timePeriod = state.timePeriod,
                    canStepBack = state.canStepBack,
                    canStepForward = state.canStepForward,
                    onTimePeriodChange = viewModel::setTimePeriod,
                    onStepBack = viewModel::stepBack,
                    onStepForward = viewModel::stepForward
                )
                ApneaHistoryTab.ALL_RECORDS -> AllApneaRecordsScreen(navController = navController)
                ApneaHistoryTab.STATS -> StatsTabContent(
                    state = state,
                    onToggleShowAll = { viewModel.toggleShowAllStats() },
                    onRecordClick = { recordId ->
                        navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                    },
                    onTimeChartClick = { metricType, drillType, title ->
                        navController.navigate(WagsRoutes.timeChart(metricType, drillType, title))
                    },
                    onSetLungVolume = { viewModel.setLungVolume(it) },
                    onSetPrepType   = { viewModel.setPrepType(it) },
                    onSetTimeOfDay  = { viewModel.setTimeOfDay(it) },
                    onSetPosture    = { viewModel.setPosture(it) },
                    onSetAudio      = { viewModel.setAudio(it) }
                )
                ApneaHistoryTab.CALENDAR -> CalendarTabContent(
                    state = state,
                    displayedMonth = displayedMonth,
                    onDayClick = { date ->
                        if (date in state.allDatesWithRecords) {
                            if (state.selectedDate == date) viewModel.clearSelection()
                            else viewModel.selectDate(date)
                        }
                    },
                    onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                    onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                    onDismissMultiList = { viewModel.clearSelection() },
                    onNavigateToDetail = { recordId ->
                        viewModel.clearSelection()
                        navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
                    }
                )
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@Composable
private fun GraphsTabContent(
    chartData: ApneaChartData,
    readingCount: Int,
    timePeriod: ApneaChartTimePeriod,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onTimePeriodChange: (ApneaChartTimePeriod) -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Time period selector ───────────────────────────────────────────
        ApneaTimePeriodSelector(
            selected = timePeriod,
            onSelect = onTimePeriodChange
        )

        // ── Step navigation (only shown when a finite period is selected) ──
        if (timePeriod != ApneaChartTimePeriod.ALL) {
            ApneaPeriodStepRow(
                timePeriod = timePeriod,
                canStepBack = canStepBack,
                canStepForward = canStepForward,
                onStepBack = onStepBack,
                onStepForward = onStepForward
            )
        }

        Text(
            "$readingCount free holds total · showing ${timePeriod.label}",
            style = MaterialTheme.typography.labelMedium,
            color = TextDisabled
        )

        // ── 1. Hold Duration ───────────────────────────────────────────────
        ApneaGraphSection(title = "Hold Duration", subtitle = "Free holds only · x-axis = date") {
            ApneaMetricLineChart(
                label = "Duration (s)",
                points = chartData.holdDuration,
                lineColor = EcgCyan,
                isLandscape = isLandscape,
                isPrimary = true
            )
        }

        // ── 2. Heart Rate ─────────────────────────────────────────────────
        if (chartData.minHr.isNotEmpty() || chartData.maxHr.isNotEmpty()) {
            ApneaGraphSection(title = "Heart Rate", subtitle = "Free holds only · x-axis = date") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chartData.minHr.isNotEmpty()) {
                        ApneaMetricLineChart(label = "Min HR (bpm)", points = chartData.minHr, lineColor = ReadinessBlue, invertGood = true, isLandscape = isLandscape)
                    }
                    if (chartData.maxHr.isNotEmpty()) {
                        ApneaMetricLineChart(label = "Max HR (bpm)", points = chartData.maxHr, lineColor = ReadinessOrange, invertGood = true, isLandscape = isLandscape)
                    }
                }
            }
        }

        // ── 3. Oximetry ───────────────────────────────────────────────────
        if (chartData.lowestSpO2.isNotEmpty()) {
            ApneaGraphSection(title = "Oximetry", subtitle = "Lowest SpO₂ recorded · x-axis = date") {
                ApneaMetricLineChart(label = "Lowest SpO₂ (%)", points = chartData.lowestSpO2, lineColor = ReadinessGreen, isLandscape = isLandscape)
            }
        }

        // ── 4. Contractions ───────────────────────────────────────────────
        if (chartData.firstContractionSec.isNotEmpty()) {
            ApneaGraphSection(title = "Contractions", subtitle = "Time to first contraction · x-axis = date") {
                ApneaMetricLineChart(label = "First Contraction (s)", points = chartData.firstContractionSec, lineColor = CoherencePink, isLandscape = isLandscape)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Time period selector ───────────────────────────────────────────────────────

@Composable
private fun ApneaTimePeriodSelector(
    selected: ApneaChartTimePeriod,
    onSelect: (ApneaChartTimePeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ApneaChartTimePeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) EcgCyan.copy(alpha = 0.25f) else SurfaceVariant)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) EcgCyan else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Period step navigation row ─────────────────────────────────────────────────

@Composable
private fun ApneaPeriodStepRow(
    timePeriod: ApneaChartTimePeriod,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onStepBack,
            enabled = canStepBack
        ) {
            Text(
                "‹",
                style = MaterialTheme.typography.headlineMedium,
                color = if (canStepBack) EcgCyan else TextDisabled
            )
        }

        Text(
            "Scroll ${timePeriod.label} windows",
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled
        )

        IconButton(
            onClick = onStepForward,
            enabled = canStepForward
        ) {
            Text(
                "›",
                style = MaterialTheme.typography.headlineMedium,
                color = if (canStepForward) EcgCyan else TextDisabled
            )
        }
    }
}

// ── Graph section wrapper ──────────────────────────────────────────────────────

@Composable
private fun ApneaGraphSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

// ── Generic metric line chart ──────────────────────────────────────────────────

@Composable
private fun ApneaMetricLineChart(
    label: String,
    points: List<ApneaChartPoint>,
    lineColor: Color,
    invertGood: Boolean = false,
    isLandscape: Boolean = false,
    isPrimary: Boolean = false
) {
    if (points.isEmpty()) {
        Text(
            "No data yet",
            style = MaterialTheme.typography.bodySmall,
            color = TextDisabled,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        return
    }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val yPad = ((max - min) * 0.1f).coerceAtLeast(1f)

    var tooltipPoint by remember { mutableStateOf<ApneaChartPoint?>(null) }

    val chartHeight = if (isLandscape) {
        if (isPrimary) 200.dp else 160.dp
    } else {
        if (isPrimary) 140.dp else 100.dp
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                "Latest: ${String.format("%.1f", latest.value)}",
                style = MaterialTheme.typography.labelMedium,
                color = lineColor,
                fontWeight = FontWeight.Bold
            )
        }

        ApneaLineChartCanvas(
            points = points,
            lineColor = lineColor,
            fillAlpha = 0.10f,
            yMin = (min - yPad),
            yMax = (max + yPad),
            tooltipPoint = tooltipPoint,
            onTap = { tooltipPoint = if (tooltipPoint == it) null else it },
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        )

        tooltipPoint?.let { tp ->
            ApneaTooltipCard(label = label, value = String.format("%.1f", tp.value), date = tp.label, color = lineColor)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Avg ${String.format("%.1f", avg)}", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            Text("Min ${String.format("%.1f", min)}  Max ${String.format("%.1f", max)}", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
        }
    }
}

// ── Tooltip card ───────────────────────────────────────────────────────────────

@Composable
private fun ApneaTooltipCard(label: String, value: String, date: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            Text(date, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

// ── Pure Canvas line chart with x-axis labels and tap interaction ──────────────

@Composable
private fun ApneaLineChartCanvas(
    points: List<ApneaChartPoint>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    referenceLines: List<Pair<Float, Color>> = emptyList(),
    tooltipPoint: ApneaChartPoint? = null,
    onTap: (ApneaChartPoint) -> Unit = {}
) {
    if (points.size < 2) {
        Canvas(modifier = modifier) {
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }

    val xAxisHeight = 18.dp
    val yRange = (yMax - yMin).coerceAtLeast(0.001f)

    // Pick up to 5 evenly-spaced label indices, always including first and last
    val maxLabels = 5
    val labelIndices: List<Int> = when {
        points.size <= maxLabels -> points.indices.toList()
        else -> {
            val step = (points.size - 1).toFloat() / (maxLabels - 1).toFloat()
            (0 until maxLabels).map { i -> (i * step).toInt().coerceIn(0, points.size - 1) }
        }
    }

    // Format "MMM d" from ISO date string "yyyy-MM-dd"
    fun shortDate(isoDate: String): String = try {
        val parts = isoDate.split("-")
        val month = java.time.Month.of(parts[1].toInt()).name.take(3).lowercase()
            .replaceFirstChar { it.uppercase() }
        "$month ${parts[2].trimStart('0')}"
    } catch (_: Exception) { isoDate }

    Column(modifier = modifier) {
        // ── Chart canvas ──────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points) {
                    detectTapGestures { tapOffset ->
                        val w = size.width.toFloat()
                        val xStep = w / (points.size - 1).toFloat()
                        val tappedIdx = (tapOffset.x / xStep).toInt().coerceIn(0, points.size - 1)
                        val closest = points.minByOrNull { p -> abs(p.dayIndex - tappedIdx.toFloat()) }
                        closest?.let { onTap(it) }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val xStep = w / (points.size - 1).toFloat()

            fun xOf(i: Int) = i * xStep
            fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

            // Reference lines
            referenceLines.forEach { (refY, refColor) ->
                val ry = yOf(refY)
                drawLine(color = refColor.copy(alpha = 0.35f), start = Offset(0f, ry), end = Offset(w, ry), strokeWidth = 1.5f)
            }

            // Fill path
            val fillPath = Path().apply {
                moveTo(xOf(0), h)
                lineTo(xOf(0), yOf(points[0].value))
                for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].value))
                lineTo(xOf(points.size - 1), h)
                close()
            }
            drawPath(fillPath, color = lineColor.copy(alpha = fillAlpha))

            // Line path
            val linePath = Path().apply {
                moveTo(xOf(0), yOf(points[0].value))
                for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].value))
            }
            drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // Latest point dot
            val lastX = xOf(points.size - 1)
            val lastY = yOf(points.last().value)
            drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
            drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))

            // Highlighted tapped point
            tooltipPoint?.let { tp ->
                val tpIdx = points.indexOfFirst { it.label == tp.label && it.value == tp.value }
                if (tpIdx >= 0) {
                    val tx = xOf(tpIdx)
                    val ty = yOf(tp.value)
                    drawLine(color = lineColor.copy(alpha = 0.4f), start = Offset(tx, 0f), end = Offset(tx, h), strokeWidth = 1f)
                    drawCircle(color = lineColor, radius = 7f, center = Offset(tx, ty))
                    drawCircle(color = BackgroundDark, radius = 4f, center = Offset(tx, ty))
                }
            }

            // Tick marks at label positions
            labelIndices.forEach { idx ->
                val tx = xOf(idx)
                drawLine(color = TextDisabled.copy(alpha = 0.5f), start = Offset(tx, h - 4f), end = Offset(tx, h), strokeWidth = 1f)
            }
        }

        // ── X-axis date labels ────────────────────────────────────────────
        // Use a BoxWithConstraints so we can position each label by fraction of width
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(xAxisHeight)
        ) {
            val totalWidth = maxWidth
            labelIndices.forEach { idx ->
                val fraction = if (points.size > 1) idx.toFloat() / (points.size - 1).toFloat() else 0f
                val dateStr = shortDate(points[idx].label)
                // Estimate label width ~30dp; clamp so it doesn't overflow edges
                val labelWidthEst = 30.dp
                val rawOffset = totalWidth * fraction - labelWidthEst / 2
                val clampedOffset = rawOffset.coerceIn(0.dp, totalWidth - labelWidthEst)
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = TextDisabled,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .absoluteOffset(x = clampedOffset)
                        .width(labelWidthEst)
                )
            }
        }
    }
}

// ── Stats tab ──────────────────────────────────────────────────────────────────

@Composable
private fun StatsTabContent(
    state: ApneaHistoryUiState,
    onToggleShowAll: () -> Unit,
    onRecordClick: (Long) -> Unit,
    onTimeChartClick: (metricType: String, drillType: String, title: String) -> Unit,
    onSetLungVolume: (String) -> Unit,
    onSetPrepType: (String) -> Unit,
    onSetTimeOfDay: (String) -> Unit,
    onSetPosture: (String) -> Unit,
    onSetAudio: (String) -> Unit
) {
    val stats = if (state.showAllStats) state.allStats else state.filteredStats
    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        StatsSettingsDialog(
            lungVolume = state.lungVolume,
            prepType   = state.prepType,
            timeOfDay  = state.timeOfDay,
            posture    = state.posture,
            audio      = state.audio,
            onSetLungVolume = onSetLungVolume,
            onSetPrepType   = onSetPrepType,
            onSetTimeOfDay  = onSetTimeOfDay,
            onSetPosture    = onSetPosture,
            onSetAudio      = onSetAudio,
            onDismiss = { showSettingsDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // All-settings toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.showAllStats) "All settings" else "Current settings",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.showAllStats) TextPrimary else TextSecondary
                )
                if (!state.showAllStats) {
                    // Clickable settings label — opens the settings popup
                    Text(
                        "${state.lungVolume.displaySettingLabel()}  ·  ${state.prepType.displaySettingLabel()}  ·  " +
                        "${state.timeOfDay.displaySettingLabel()}  ·  ${state.posture.displaySettingLabel()}  ·  ${state.audio.displaySettingLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.clickable { showSettingsDialog = true }
                    )
                    Text(
                        "Tap to change settings",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Switch(
                    checked = state.showAllStats,
                    onCheckedChange = { onToggleShowAll() },
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        HorizontalDivider(color = SurfaceVariant)

        ApneaStatsContent(
            stats = stats,
            trophyStats = state.trophyStats,
            onRecordClick = onRecordClick,
            onTimeChartClick = onTimeChartClick
        )
    }
}

// ── Settings popup dialog (for Stats tab) ─────────────────────────────────────

@Composable
private fun StatsSettingsDialog(
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    posture: String,
    audio: String,
    onSetLungVolume: (String) -> Unit,
    onSetPrepType: (String) -> Unit,
    onSetTimeOfDay: (String) -> Unit,
    onSetPosture: (String) -> Unit,
    onSetAudio: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = SurfaceVariant,
        selectedLabelColor = TextPrimary
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Filter Settings", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Lung Volume
                SettingRow(label = "Lung Volume") {
                    SettingChip(FILTER_ALL, "All", lungVolume == FILTER_ALL, { onSetLungVolume(FILTER_ALL) }, chipColors)
                    listOf("FULL", "PARTIAL", "EMPTY").forEach { lv ->
                        SettingChip(lv, lv.displayLungVolume(), lungVolume == lv, { onSetLungVolume(lv) }, chipColors)
                    }
                }
                // Prep Type
                SettingRow(label = "Prep") {
                    SettingChip(FILTER_ALL, "All", prepType == FILTER_ALL, { onSetPrepType(FILTER_ALL) }, chipColors)
                    PrepType.entries.forEach { pt ->
                        SettingChip(pt.name, pt.displayName(), prepType == pt.name, { onSetPrepType(pt.name) }, chipColors)
                    }
                }
                // Time of Day
                SettingRow(label = "Time of Day") {
                    SettingChip(FILTER_ALL, "All", timeOfDay == FILTER_ALL, { onSetTimeOfDay(FILTER_ALL) }, chipColors)
                    TimeOfDay.entries.forEach { tod ->
                        SettingChip(tod.name, tod.displayName(), timeOfDay == tod.name, { onSetTimeOfDay(tod.name) }, chipColors)
                    }
                }
                // Posture
                SettingRow(label = "Posture") {
                    SettingChip(FILTER_ALL, "All", posture == FILTER_ALL, { onSetPosture(FILTER_ALL) }, chipColors)
                    Posture.entries.forEach { pos ->
                        SettingChip(pos.name, pos.displayName(), posture == pos.name, { onSetPosture(pos.name) }, chipColors)
                    }
                }
                // Audio
                SettingRow(label = "Audio") {
                    SettingChip(FILTER_ALL, "All", audio == FILTER_ALL, { onSetAudio(FILTER_ALL) }, chipColors)
                    AudioSetting.entries.forEach { aud ->
                        SettingChip(aud.name, aud.displayName(), audio == aud.name, { onSetAudio(aud.name) }, chipColors)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = TextPrimary)
            }
        }
    )
}

@Composable
private fun SettingChip(
    @Suppress("UNUSED_PARAMETER") key: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: SelectableChipColors
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = colors
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(label: String, chips: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) { chips() }
    }
}

// ── Calendar tab ───────────────────────────────────────────────────────────────

@Composable
private fun CalendarTabContent(
    state: ApneaHistoryUiState,
    displayedMonth: YearMonth,
    onDayClick: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDismissMultiList: () -> Unit,
    onNavigateToDetail: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ApneaCalendar(
            displayedMonth = displayedMonth,
            datesWithRecords = state.allDatesWithRecords,
            selectedDate = state.selectedDate,
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        // Show multi-session list only when >1 record on the selected day
        // (single-session case is handled by LaunchedEffect in the parent)
        if (state.selectedDayRecords.size > 1) {
            ApneaMultiSessionList(
                records = state.selectedDayRecords,
                onDismiss = onDismissMultiList,
                onSessionClick = onNavigateToDetail
            )
        }
    }
}

// ── Calendar widget ────────────────────────────────────────────────────────────

@Composable
private fun ApneaCalendar(
    displayedMonth: YearMonth,
    datesWithRecords: Set<LocalDate>,
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Month navigation header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                }
                Text(
                    text = displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onNextMonth) {
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                }
            }

            // Day-of-week headers
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
            }

            // Day grid
            val firstDayOfMonth = displayedMonth.atDay(1)
            val startOffset = firstDayOfMonth.dayOfWeek.value % 7
            val daysInMonth = displayedMonth.lengthOfMonth()
            val totalCells = startOffset + daysInMonth
            val rows = (totalCells + 6) / 7

            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val cellIndex = row * 7 + col
                        val dayNumber = cellIndex - startOffset + 1
                        if (dayNumber < 1 || dayNumber > daysInMonth) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            val date = displayedMonth.atDay(dayNumber)
                            val hasRecord = date in datesWithRecords
                            val isSelected = date == selectedDate
                            val isToday = date == LocalDate.now()
                            ApneaCalendarDay(
                                dayNumber = dayNumber,
                                hasRecord = hasRecord,
                                isSelected = isSelected,
                                isToday = isToday,
                                onClick = { onDayClick(date) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(TextSecondary))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Session recorded — tap to view",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

@Composable
private fun ApneaCalendarDay(
    dayNumber: Int,
    hasRecord: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isSelected -> TextSecondary.copy(alpha = 0.25f)
        isToday    -> SurfaceDark
        else       -> Color.Transparent
    }
    val textColor = when {
        isSelected -> TextPrimary
        isToday    -> TextPrimary
        hasRecord  -> TextPrimary
        else       -> TextDisabled
    }

    Column(
        modifier = modifier
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .clickable(enabled = hasRecord, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal
        )
        if (hasRecord) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) TextPrimary else TextSecondary)
            )
        } else {
            Spacer(Modifier.height(5.dp))
        }
    }
}

// ── Multi-session list (shown when >1 session on a day) ────────────────────────

@Composable
private fun ApneaMultiSessionList(
    records: List<ApneaRecordEntity>,
    onDismiss: () -> Unit,
    onSessionClick: (Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dateLabel = records.firstOrNull()?.let { r ->
        Instant.ofEpochMilli(r.timestamp).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
    } ?: ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${records.size} sessions — tap one to view details",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = TextDisabled)
                }
            }

            HorizontalDivider(color = SurfaceDark)

            records.forEach { record ->
                ApneaSessionSummaryCard(
                    record = record,
                    onClick = { onSessionClick(record.recordId) }
                )
            }
        }
    }
}

@Composable
private fun ApneaSessionSummaryCard(
    record: ApneaRecordEntity,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val timeLabel = Instant.ofEpochMilli(record.timestamp).atZone(zone)
        .format(DateTimeFormatter.ofPattern("h:mm a"))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    record.lungVolume.displayLungVolume() + "  ·  " + record.prepType,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(
                formatHistoryDuration(record.durationMs),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Stats content ──────────────────────────────────────────────────────────────

@Composable
private fun ApneaStatsContent(
    stats: ApneaStats,
    trophyStats: TrophyStats = TrophyStats(),
    onRecordClick: (Long) -> Unit,
    onTimeChartClick: (metricType: String, drillType: String, title: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Activity counts
        Text(
            "Activity Counts",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        val activities = listOf(
            "Free Hold"           to stats.freeHoldCount,
            "O₂ Table"            to stats.o2TableCount,
            "CO₂ Table"           to stats.co2TableCount,
            "Progressive O₂"      to stats.progressiveO2Count,
            "Min Breath"          to stats.minBreathCount,
            "Wonka: Contraction"  to stats.wonkaContractionCount,
            "Wonka: Endurance"    to stats.wonkaEnduranceCount,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            activities.forEach { (label, count) ->
                HistoryStatsRow(label = label, value = count.toString())
            }
        }

        HorizontalDivider(color = SurfaceVariant)

        // Trophy stats section (after Activity Counts)
        if (trophyStats.total.total > 0) {
            TrophyStatsSection(trophyStats = trophyStats)
            HorizontalDivider(color = SurfaceVariant)
        }

        // ── Total Times section ────────────────────────────────────────────
        Text(
            "Total Times",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Per drill type — hold time
            Text("Hold Time", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            val holdTimes = listOf(
                Triple("Free Hold",      stats.freeHoldTotalHoldMs,      "FREE_HOLD"),
                Triple("O₂ Table",       stats.o2TableTotalHoldMs,       "O2"),
                Triple("CO₂ Table",      stats.co2TableTotalHoldMs,      "CO2"),
                Triple("Progressive O₂", stats.progressiveO2TotalHoldMs, "PROGRESSIVE_O2"),
                Triple("Min Breath",     stats.minBreathTotalHoldMs,     "MIN_BREATH"),
            )
            holdTimes.forEach { (label, ms, drill) ->
                HistoryStatsRow(
                    label = label,
                    value = formatStatsDuration(ms),
                    onClick = { onTimeChartClick("hold", drill, "$label — Hold Time") }
                )
            }
            HistoryStatsRow(
                label = "Total",
                value = formatStatsDuration(stats.totalHoldMs),
                bold = true,
                onClick = { onTimeChartClick("hold", "TOTAL", "Total Hold Time") }
            )
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Per drill type — session time
            Text("Session Time", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            val sessionTimes = listOf(
                Triple("Free Hold",      stats.freeHoldTotalSessionMs,      "FREE_HOLD"),
                Triple("O₂ Table",       stats.o2TableTotalSessionMs,       "O2"),
                Triple("CO₂ Table",      stats.co2TableTotalSessionMs,      "CO2"),
                Triple("Progressive O₂", stats.progressiveO2TotalSessionMs, "PROGRESSIVE_O2"),
                Triple("Min Breath",     stats.minBreathTotalSessionMs,     "MIN_BREATH"),
            )
            sessionTimes.forEach { (label, ms, drill) ->
                HistoryStatsRow(
                    label = label,
                    value = formatStatsDuration(ms),
                    onClick = { onTimeChartClick("session", drill, "$label — Session Time") }
                )
            }
            HistoryStatsRow(
                label = "Total",
                value = formatStatsDuration(stats.totalSessionMs),
                bold = true,
                onClick = { onTimeChartClick("session", "TOTAL", "Total Session Time") }
            )
        }

        HorizontalDivider(color = SurfaceVariant)

        Text(
            "Overall Session Extremes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HistoryExtremeRow("Highest HR",   stats.maxHrEver?.let { "%.0f bpm".format(it) } ?: "—", stats.maxHrEverRecordId, onRecordClick)
            HistoryExtremeRow("Lowest HR",    stats.minHrEver?.let { "%.0f bpm".format(it) } ?: "—", stats.minHrEverRecordId, onRecordClick)
            HistoryExtremeRow("Lowest SpO₂",  stats.lowestSpO2Ever?.let { "$it%" } ?: "—",           stats.lowestSpO2EverRecordId, onRecordClick)
        }

        HorizontalDivider(color = SurfaceVariant)

        Text(
            "Session Start Extremes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HistoryExtremeRow("Highest HR at start",  stats.maxStartHr?.let { "$it bpm" } ?: "—",  stats.maxStartHrRecordId, onRecordClick)
            HistoryExtremeRow("Lowest HR at start",   stats.minStartHr?.let { "$it bpm" } ?: "—",  stats.minStartHrRecordId, onRecordClick)
            HistoryExtremeRow("Highest SpO₂ at start",stats.maxStartSpO2?.let { "$it%" } ?: "—",   stats.maxStartSpO2RecordId, onRecordClick)
            HistoryExtremeRow("Lowest SpO₂ at start", stats.minStartSpO2?.let { "$it%" } ?: "—",   stats.minStartSpO2RecordId, onRecordClick)
        }

        HorizontalDivider(color = SurfaceVariant)

        Text(
            "Session End Extremes",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HistoryExtremeRow("Highest HR at end",   stats.maxEndHr?.let { "$it bpm" } ?: "—",   stats.maxEndHrRecordId, onRecordClick)
            HistoryExtremeRow("Lowest HR at end",    stats.minEndHr?.let { "$it bpm" } ?: "—",   stats.minEndHrRecordId, onRecordClick)
            HistoryExtremeRow("Highest SpO₂ at end", stats.maxEndSpO2?.let { "$it%" } ?: "—",    stats.maxEndSpO2RecordId, onRecordClick)
            HistoryExtremeRow("Lowest SpO₂ at end",  stats.minEndSpO2?.let { "$it%" } ?: "—",    stats.minEndSpO2RecordId, onRecordClick)
        }
    }
}

// ── Trophy stats section ───────────────────────────────────────────────────────

/** Formats an integer with comma separators (e.g. 1234567 → "1,234,567"). */
private fun Int.formatWithCommas(): String =
    NumberFormat.getNumberInstance(java.util.Locale.US).format(this)

/** Formats a Double average to 1 decimal place with comma separators. */
private fun Double.formatAvg(): String {
    val nf = NumberFormat.getNumberInstance(java.util.Locale.US)
    nf.maximumFractionDigits = 1
    nf.minimumFractionDigits = 1
    return nf.format(this)
}

@Composable
private fun TrophyStatsSection(trophyStats: TrophyStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "🏆 Trophy Stats",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )

        val drills = listOf(
            "Free Hold"      to trophyStats.freeHold,
            "Progressive O₂" to trophyStats.progressiveO2,
            "Min Breath"     to trophyStats.minBreath,
            "Total"          to trophyStats.total
        )

        drills.forEach { (label, drill) ->
            if (drill.total > 0) {
                // Total is expanded by default; others start collapsed
                TrophyDrillBlock(
                    label = label,
                    drill = drill,
                    isTotal = label == "Total",
                    initiallyExpanded = label == "Total"
                )
            }
        }
    }
}

@Composable
private fun TrophyDrillBlock(
    label: String,
    drill: DrillTrophyStats,
    isTotal: Boolean,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // Tappable header row — toggles expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isTotal) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isTotal) TextPrimary else TextSecondary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    drill.total.formatWithCommas(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Current period counts
                HistoryStatsRow(label = "Today",        value = drill.todayCount.formatWithCommas())
                HistoryStatsRow(label = "Last 7 days",  value = drill.currentWeekCount.formatWithCommas())
                HistoryStatsRow(label = "Last 30 days", value = drill.currentMonthCount.formatWithCommas())

                Spacer(Modifier.height(4.dp))
                Text("Averages", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                HistoryStatsRow(label = "Daily avg",   value = drill.dailyAvg.formatAvg())
                HistoryStatsRow(label = "Weekly avg",  value = drill.weeklyAvg.formatAvg())
                HistoryStatsRow(label = "Monthly avg", value = drill.monthlyAvg.formatAvg())

                Spacer(Modifier.height(4.dp))
                Text("Records", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                HistoryStatsRow(label = "Best day",     value = drill.highestDay.formatWithCommas())
                HistoryStatsRow(label = "Best 7 days",  value = drill.highestWeek.formatWithCommas())
                HistoryStatsRow(label = "Best 30 days", value = drill.highestMonth.formatWithCommas())
            }
        }

        if (!isTotal) {
            HorizontalDivider(color = SurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun HistoryExtremeRow(
    label: String,
    value: String,
    recordId: Long?,
    onRecordClick: (Long) -> Unit
) {
    val clickable = recordId != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onRecordClick(recordId!!) } else Modifier),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (clickable) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                if (clickable) {
                    Text("›", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun HistoryStatsRow(label: String, value: String, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (bold) TextPrimary else TextSecondary,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/** Converts a setting string (enum name or "ALL") to a display label. */
private fun String.displaySettingLabel(): String = when (this) {
    FILTER_ALL -> "All"
    // Lung volume
    "FULL"     -> "Full"
    "PARTIAL"  -> "Half"
    "EMPTY"    -> "Empty"
    // PrepType
    "NO_PREP"  -> "No Prep"
    "RESONANCE"-> "Resonance"
    "HYPER"    -> "Hyper"
    // TimeOfDay
    "MORNING"  -> "Morning"
    "DAY"      -> "Day"
    "NIGHT"    -> "Night"
    // Posture
    "SITTING"  -> "Sitting"
    "LAYING"   -> "Laying"
    // AudioSetting
    "SILENCE"  -> "Silence"
    "MUSIC"    -> "Music"
    "MOVIE"    -> "Movie"
    "GUIDED"   -> "Guided"
    else       -> lowercase().replaceFirstChar { it.uppercase() }
}

private fun formatHistoryDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

/** Formats a duration in ms to a human-readable string with hours if needed. */
private fun formatStatsDuration(ms: Long): String {
    if (ms <= 0L) return "0s"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}
