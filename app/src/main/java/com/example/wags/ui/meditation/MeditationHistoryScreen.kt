package com.example.wags.ui.meditation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ── Shared mini-chart composables (reused by detail screen) ───────────────────

/**
 * A single-series line chart identical in style to the history graphs,
 * but sized for embedding inside a detail card.
 */
@Composable
internal fun MeditationDetailLineChart(
    points: List<Pair<Float, Float>>,   // (x-index, value)
    lineColor: Color,
    modifier: Modifier = Modifier,
    showZeroLine: Boolean = false
) {
    if (points.size < 2) {
        Canvas(modifier = modifier) {
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }
    val values = points.map { it.second }
    val yMin = values.min()
    val yMax = values.max()
    val yPad = ((yMax - yMin) * 0.15f).coerceAtLeast(0.1f)
    LineChartCanvas(
        points = points.mapIndexed { i, p -> MeditationChartPoint(p.first, p.second, 0L, "") },
        lineColor = lineColor,
        fillAlpha = 0.12f,
        yMin = yMin - yPad,
        yMax = yMax + yPad,
        zeroLine = if (showZeroLine) 0f else null,
        modifier = modifier
    )
}

// ── Tab definitions ────────────────────────────────────────────────────────────

private val MeditationHistoryTabSelection.label: String
    get() = when (this) {
        MeditationHistoryTabSelection.GRAPHS   -> "Graphs"
        MeditationHistoryTabSelection.CALENDAR -> "Calendar"
    }

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationHistoryScreen(
    navController: NavController,
    viewModel: MeditationHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab = uiState.selectedTab
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    // Single-session auto-navigate: when exactly 1 session on the selected day,
    // navigate immediately and clear the selection so back doesn't re-trigger.
    val selectedDaySessions = uiState.selectedDaySessions
    LaunchedEffect(selectedDaySessions) {
        if (selectedDaySessions.size == 1) {
            val sessionId = selectedDaySessions.first().sessionId
            // Persist Calendar tab so system back returns to it
            viewModel.selectTab(MeditationHistoryTabSelection.CALENDAR)
            navController.navigate(WagsRoutes.meditationSessionDetail(sessionId))
            // Clear selection so that when we return, the LaunchedEffect
            // doesn't re-fire and navigate again.
            viewModel.clearSelection()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Meditation History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
                        )
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
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = SurfaceDark,
                contentColor = TextSecondary
            ) {
                MeditationHistoryTabSelection.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        modifier = Modifier.background(
                            if (isSelected) SurfaceVariant
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

            if (uiState.allSessions.isEmpty()) {
                EmptyHistoryContent()
            } else {
                when (selectedTab) {
                    MeditationHistoryTabSelection.GRAPHS -> GraphsContent(
                        uiState = uiState,
                        onPostureFilterSelected = { viewModel.setPostureFilter(it) },
                        onTimePeriodChange = viewModel::setTimePeriod,
                        onStepBack = viewModel::stepBack,
                        onStepForward = viewModel::stepForward
                    )
                    MeditationHistoryTabSelection.CALENDAR -> CalendarContent(
                        uiState = uiState,
                        displayedMonth = displayedMonth,
                        onDayClick = { date ->
                            if (date in uiState.datesWithSessions) {
                                if (uiState.selectedDate == date) viewModel.clearSelection()
                                else viewModel.selectDate(date)
                            }
                        },
                        onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                        onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                        onDismissMultiList = { viewModel.clearSelection() },
                        onNavigateToDetail = { id ->
                            viewModel.selectTab(MeditationHistoryTabSelection.CALENDAR)
                            navController.navigate(WagsRoutes.meditationSessionDetail(id))
                            // Clear selection so back doesn't re-trigger auto-navigate
                            viewModel.clearSelection()
                        }
                    )
                }
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@Composable
private fun GraphsContent(
    uiState: MeditationHistoryUiState,
    onPostureFilterSelected: (PostureFilter) -> Unit,
    onTimePeriodChange: (MeditationChartTimePeriod) -> Unit,
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
        MeditationTimePeriodSelector(
            selected = uiState.timePeriod,
            onSelect = onTimePeriodChange
        )

        // ── Step navigation (only shown when a finite period is selected) ──
        if (uiState.timePeriod != MeditationChartTimePeriod.ALL) {
            MeditationPeriodStepRow(
                timePeriod = uiState.timePeriod,
                canStepBack = uiState.canStepBack,
                canStepForward = uiState.canStepForward,
                onStepBack = onStepBack,
                onStepForward = onStepForward
            )
        }

        Text(
            "${uiState.allSessions.size} readings total · showing ${uiState.timePeriod.label}",
            style = MaterialTheme.typography.labelMedium,
            color = TextDisabled
        )

        SummaryCard(uiState = uiState)

        // ── Posture filter ──────────────────────────────────────────────────
        PostureFilterRow(
            selected = uiState.postureFilter,
            onSelected = onPostureFilterSelected
        )

        // Duration
        GraphSection(title = "Session Duration", subtitle = "Minutes per session · x-axis = date") {
            MeditationLineChart(
                points = uiState.chartData.durationMin,
                lineColor = TextPrimary,
                label = "Duration (min)",
                isLandscape = isLandscape
            )
        }

        // Avg HR
        if (uiState.chartData.avgHr.isNotEmpty()) {
            GraphSection(title = "Average Heart Rate", subtitle = "BPM over session · x-axis = date") {
                MeditationLineChart(
                    points = uiState.chartData.avgHr,
                    lineColor = TextSecondary,
                    label = "Avg HR (bpm)",
                    isLandscape = isLandscape
                )
            }
        }

        // Start vs End RMSSD
        if (uiState.chartData.startRmssd.isNotEmpty()) {
            GraphSection(
                title = "RMSSD",
                subtitle = "Start (cyan) vs End (green) — higher is better · x-axis = date"
            ) {
                DualLineChart(
                    pointsA = uiState.chartData.startRmssd,
                    pointsB = uiState.chartData.endRmssd,
                    colorA = TextPrimary,
                    colorB = TextSecondary,
                    labelA = "Start RMSSD",
                    labelB = "End RMSSD",
                    isLandscape = isLandscape
                )
            }
        }

        // ln(RMSSD) slope
        if (uiState.chartData.lnRmssdSlope.isNotEmpty()) {
            GraphSection(
                title = "ln(RMSSD) Slope",
                subtitle = "Positive = HRV improving during session · x-axis = date"
            ) {
                MeditationLineChart(
                    points = uiState.chartData.lnRmssdSlope,
                    lineColor = TextSecondary,
                    label = "ln(RMSSD) slope",
                    showZeroLine = true,
                    isLandscape = isLandscape
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Time period selector ───────────────────────────────────────────────────────

@Composable
private fun MeditationTimePeriodSelector(
    selected: MeditationChartTimePeriod,
    onSelect: (MeditationChartTimePeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MeditationChartTimePeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) TextSecondary.copy(alpha = 0.25f) else SurfaceVariant)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Period step navigation row ─────────────────────────────────────────────────

@Composable
private fun MeditationPeriodStepRow(
    timePeriod: MeditationChartTimePeriod,
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
                color = if (canStepBack) TextSecondary else TextDisabled
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
                color = if (canStepForward) TextSecondary else TextDisabled
            )
        }
    }
}

// ── Calendar tab ───────────────────────────────────────────────────────────────

@Composable
private fun CalendarContent(
    uiState: MeditationHistoryUiState,
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
        MeditationCalendar(
            displayedMonth = displayedMonth,
            datesWithSessions = uiState.datesWithSessions,
            selectedDate = uiState.selectedDate,
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        if (uiState.selectedDaySessions.size > 1) {
            MultiSessionList(
                sessions = uiState.selectedDaySessions,
                audioMap = uiState.audioMap,
                onDismiss = onDismissMultiList,
                onSessionClick = onNavigateToDetail
            )
        }
    }
}

// ── Calendar widget ────────────────────────────────────────────────────────────

@Composable
private fun MeditationCalendar(
    displayedMonth: YearMonth,
    datesWithSessions: Set<LocalDate>,
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
                            val hasSession = date in datesWithSessions
                            val isSelected = date == selectedDate
                            val isToday = date == LocalDate.now()
                            CalendarDay(
                                dayNumber = dayNumber,
                                hasSession = hasSession,
                                isSelected = isSelected,
                                isToday = isToday,
                                onClick = { onDayClick(date) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

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
private fun CalendarDay(
    dayNumber: Int,
    hasSession: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isSelected -> SurfaceVariant
        isToday    -> SurfaceDark
        else       -> Color.Transparent
    }
    val textColor = when {
        isSelected -> TextPrimary
        isToday    -> TextPrimary
        hasSession -> TextPrimary
        else       -> TextDisabled
    }

    Column(
        modifier = modifier
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .clickable(enabled = hasSession, onClick = onClick)
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
        if (hasSession) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) TextSecondary else TextDisabled)
            )
        } else {
            Spacer(Modifier.height(5.dp))
        }
    }
}

// ── Multi-session list ─────────────────────────────────────────────────────────

@Composable
private fun MultiSessionList(
    sessions: List<MeditationSessionEntity>,
    audioMap: Map<Long, MeditationAudioEntity>,
    onDismiss: () -> Unit,
    onSessionClick: (Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dateLabel = sessions.firstOrNull()?.let { s ->
        Instant.ofEpochMilli(s.timestamp).atZone(zone)
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
                        "${sessions.size} sessions — tap one to view details",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = TextDisabled)
                }
            }

            HorizontalDivider(color = SurfaceDark)

            sessions.forEach { session ->
                SessionSummaryCard(
                    session = session,
                    audioMap = audioMap,
                    onClick = { onSessionClick(session.sessionId) }
                )
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    session: MeditationSessionEntity,
    audioMap: Map<Long, MeditationAudioEntity>,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val timeLabel = Instant.ofEpochMilli(session.timestamp).atZone(zone)
        .format(DateTimeFormatter.ofPattern("h:mm a"))
    val audioName = session.audioId?.let { audioMap[it]?.let { a ->
        if (a.isNone) "Silent" else a.fileName
    } } ?: "Unknown"
    val durationMin = session.durationMs / 60_000L
    val durationSec = (session.durationMs % 60_000L) / 1_000L

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
                    audioName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
                Text(
                    "${durationMin}m ${durationSec}s" +
                        (session.avgHrBpm?.let { "  ·  ${String.format("%.0f", it)} bpm" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = TextDisabled)
        }
    }
}

// ── Summary card ───────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(uiState: MeditationHistoryUiState) {
    val count = uiState.allSessions.size
    val totalMinutes = uiState.allSessions.sumOf { it.durationMs } / 60_000L
    val avgDuration = if (count > 0) totalMinutes.toFloat() / count else null
    val latestAvgHr = uiState.chartData.avgHr.lastOrNull()?.value

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Summary",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(color = SurfaceDark)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryChip("Sessions", count.toString(), TextPrimary)
                SummaryChip("Total Min", totalMinutes.toString(), TextSecondary)
                avgDuration?.let {
                    SummaryChip("Avg Min", String.format("%.1f", it), TextDisabled)
                }
                latestAvgHr?.let {
                    SummaryChip("Last HR", "${String.format("%.0f", it)} bpm", TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

// ── Graph section wrapper ──────────────────────────────────────────────────────

@Composable
private fun GraphSection(
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
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

// ── Single-series line chart ───────────────────────────────────────────────────

@Composable
private fun MeditationLineChart(
    points: List<MeditationChartPoint>,
    lineColor: Color,
    label: String,
    showZeroLine: Boolean = false,
    isLandscape: Boolean = false
) {
    if (points.isEmpty()) { NoDataLabel(); return }

    val values = points.map { it.value }
    val min = values.min()
    val max = values.max()
    val latest = values.last()
    val avg = values.average().toFloat()
    val yPad = ((max - min) * 0.1f).coerceAtLeast(0.1f)

    var tooltipPoint by remember { mutableStateOf<MeditationChartPoint?>(null) }
    val chartHeight = if (isLandscape) 160.dp else 100.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                "Latest: ${String.format("%.2f", latest)}",
                style = MaterialTheme.typography.labelMedium,
                color = lineColor,
                fontWeight = FontWeight.Bold
            )
        }

        LineChartCanvas(
            points = points,
            lineColor = lineColor,
            fillAlpha = 0.12f,
            yMin = min - yPad,
            yMax = max + yPad,
            zeroLine = if (showZeroLine) 0f else null,
            tooltipPoint = tooltipPoint,
            onTap = { tooltipPoint = if (tooltipPoint == it) null else it },
            modifier = Modifier.fillMaxWidth().height(chartHeight)
        )

        tooltipPoint?.let { tp ->
            MeditationTooltipCard(label = label, value = String.format("%.2f", tp.value), date = tp.label, color = lineColor)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Avg ${String.format("%.2f", avg)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
            Text(
                "Min ${String.format("%.2f", min)}  Max ${String.format("%.2f", max)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }
    }
}

// ── Dual-series line chart ─────────────────────────────────────────────────────

@Composable
private fun DualLineChart(
    pointsA: List<MeditationChartPoint>,
    pointsB: List<MeditationChartPoint>,
    colorA: Color,
    colorB: Color,
    labelA: String,
    labelB: String,
    isLandscape: Boolean = false
) {
    if (pointsA.isEmpty()) { NoDataLabel(); return }

    val allValues = (pointsA + pointsB).map { it.value }
    val min = allValues.min()
    val max = allValues.max()
    val yPad = ((max - min) * 0.1f).coerceAtLeast(0.1f)

    var tooltipPointA by remember { mutableStateOf<MeditationChartPoint?>(null) }
    var tooltipPointB by remember { mutableStateOf<MeditationChartPoint?>(null) }
    val chartHeight = if (isLandscape) 160.dp else 100.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(labelA, style = MaterialTheme.typography.labelSmall, color = colorA)
            Text(labelB, style = MaterialTheme.typography.labelSmall, color = colorB)
        }

        // Draw both series on the same canvas
        Box(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            LineChartCanvas(
                points = pointsA,
                lineColor = colorA,
                fillAlpha = 0.08f,
                yMin = min - yPad,
                yMax = max + yPad,
                tooltipPoint = tooltipPointA,
                onTap = {
                    tooltipPointA = if (tooltipPointA == it) null else it
                    tooltipPointB = null
                },
                modifier = Modifier.fillMaxSize()
            )
            LineChartCanvas(
                points = pointsB,
                lineColor = colorB,
                fillAlpha = 0.08f,
                yMin = min - yPad,
                yMax = max + yPad,
                tooltipPoint = tooltipPointB,
                onTap = {
                    tooltipPointB = if (tooltipPointB == it) null else it
                    tooltipPointA = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        tooltipPointA?.let { tp ->
            MeditationTooltipCard(label = labelA, value = String.format("%.2f", tp.value), date = tp.label, color = colorA)
        }
        tooltipPointB?.let { tp ->
            MeditationTooltipCard(label = labelB, value = String.format("%.2f", tp.value), date = tp.label, color = colorB)
        }
    }
}

// ── Tooltip card ───────────────────────────────────────────────────────────────

@Composable
private fun MeditationTooltipCard(label: String, value: String, date: String, color: Color) {
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

// ── Pure Canvas line chart ─────────────────────────────────────────────────────

@Composable
private fun LineChartCanvas(
    points: List<MeditationChartPoint>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    zeroLine: Float? = null,
    tooltipPoint: MeditationChartPoint? = null,
    onTap: (MeditationChartPoint) -> Unit = {}
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
                        val closest = points.minByOrNull { p -> abs(p.index - tappedIdx.toFloat()) }
                        closest?.let { onTap(it) }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val xStep = w / (points.size - 1).toFloat()

            fun xOf(i: Int) = i * xStep
            fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

            // Zero reference line
            zeroLine?.let { z ->
                val zy = yOf(z)
                drawLine(
                    color = TextDisabled.copy(alpha = 0.4f),
                    start = Offset(0f, zy),
                    end = Offset(w, zy),
                    strokeWidth = 1.5f
                )
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

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun EmptyHistoryContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "No sessions yet",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary
        )
        Text(
            "Complete a Meditation / NSDR session to see your history here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
    }
}

// ── Posture filter chip row ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostureFilterRow(
    selected: PostureFilter,
    onSelected: (PostureFilter) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Filter by posture",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // "All" chip
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelected(null) },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TextSecondary.copy(alpha = 0.2f),
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected == null,
                        selectedBorderColor = TextSecondary,
                        borderColor = SurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
                MeditationPosture.entries.forEach { posture ->
                    FilterChip(
                        selected = selected == posture,
                        onClick = { onSelected(if (selected == posture) null else posture) },
                        label = {
                            Text(
                                posture.label,
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TextSecondary.copy(alpha = 0.2f),
                            selectedLabelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected == posture,
                            selectedBorderColor = TextSecondary,
                            borderColor = SurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoDataLabel() {
    Text(
        "No data yet",
        style = MaterialTheme.typography.bodySmall,
        color = TextDisabled,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}
