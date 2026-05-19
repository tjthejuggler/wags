package com.example.wags.ui.readiness

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.ui.common.*
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class HrvHistoryTab(val label: String) {
    GRAPHS("Graphs"),
    CALENDAR("Calendar")
}

// ── Conversion helpers ────────────────────────────────────────────────────────

private fun HrvChartPoint.toHistoryPoint() = HistoryChartPoint(
    index = index,
    value = value,
    dateLabel = dateLabel
)

private fun List<HrvChartPoint>.toHistoryPoints() = map { it.toHistoryPoint() }

private fun HrvChartTimePeriod.toHistoryPeriod() = when (this) {
    HrvChartTimePeriod.WEEK -> HistoryTimePeriod.WEEK
    HrvChartTimePeriod.MONTH -> HistoryTimePeriod.MONTH
    HrvChartTimePeriod.THREE_MONTHS -> HistoryTimePeriod.THREE_MONTHS
    HrvChartTimePeriod.YEAR -> HistoryTimePeriod.YEAR
    HrvChartTimePeriod.ALL -> HistoryTimePeriod.ALL
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvReadinessHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (readingId: Long) -> Unit,
    viewModel: HrvReadinessHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(HrvHistoryTab.GRAPHS) }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    // Single-session auto-navigate: when exactly 1 reading is selected, navigate immediately
    val selectedDayReadings = uiState.selectedDayReadings
    LaunchedEffect(selectedDayReadings) {
        if (selectedDayReadings.size == 1) {
            onNavigateToDetail(selectedDayReadings.first().readingId)
            viewModel.clearSelection()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("HRV Readiness History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    LiveSensorActionsCallback(onNavigateToSettings)
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
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = SurfaceDark,
                contentColor = TextSecondary
            ) {
                HrvHistoryTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
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

            if (uiState.allReadings.isEmpty()) {
                HrvEmptyHistoryContent()
            } else {
                when (selectedTab) {
                    HrvHistoryTab.GRAPHS -> HrvGraphsContent(
                        chartData = uiState.chartData,
                        readingCount = uiState.allReadings.size,
                        timePeriod = uiState.timePeriod,
                        onTimePeriodChange = viewModel::setTimePeriod
                    )
                    HrvHistoryTab.CALENDAR -> HrvCalendarContent(
                        uiState = uiState,
                        displayedMonth = displayedMonth,
                        onDayClick = { date ->
                            if (date in uiState.datesWithReadings) {
                                if (uiState.selectedDate == date) viewModel.clearSelection()
                                else viewModel.selectDate(date)
                            }
                        },
                        onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                        onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                        onDismissMultiList = { viewModel.clearSelection() },
                        onNavigateToDetail = { id ->
                            viewModel.clearSelection()
                            onNavigateToDetail(id)
                        }
                    )
                }
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@Composable
private fun HrvGraphsContent(
    chartData: HrvHistoryChartData,
    readingCount: Int,
    timePeriod: HrvChartTimePeriod,
    onTimePeriodChange: (HrvChartTimePeriod) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val historyPeriod = timePeriod.toHistoryPeriod()
    val visiblePoints = historyPeriod.visiblePoints

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Time period selector ───────────────────────────────────────────
        HistoryTimePeriodSelector(
            selected = historyPeriod,
            onSelect = { onTimePeriodChange(
                when (it) {
                    HistoryTimePeriod.WEEK -> HrvChartTimePeriod.WEEK
                    HistoryTimePeriod.MONTH -> HrvChartTimePeriod.MONTH
                    HistoryTimePeriod.THREE_MONTHS -> HrvChartTimePeriod.THREE_MONTHS
                    HistoryTimePeriod.YEAR -> HrvChartTimePeriod.YEAR
                    HistoryTimePeriod.ALL -> HrvChartTimePeriod.ALL
                }
            )}
        )

        Text(
            "$readingCount readings total · showing ${timePeriod.label}",
            style = MaterialTheme.typography.labelMedium,
            color = TextDisabled
        )

        // ── 1. Readiness Score ─────────────────────────────────────────────
        HistoryGraphSection(
            title = "Readiness Score",
            subtitle = "0–100 composite score · x-axis = date"
        ) {
            val pts = aggregatePoints(chartData.readinessScore.toHistoryPoints(), historyPeriod)
            HistoryScoreChart(
                points = pts,
                scoreColorFn = { v -> hrvScoreColor(v) },
                referenceLines = listOf(80f to Color(0xFF606060), 60f to Color(0xFF404040)),
                zoneLegendItems = listOf(
                    "≥80" to Color(0xFF606060),
                    "60–79" to Color(0xFF404040),
                    "<60" to Color(0xFF282828)
                ),
                isLandscape = isLandscape, visiblePoints = visiblePoints
            )
        }

        // ── 2. RMSSD ──────────────────────────────────────────────────────
        HistoryGraphSection(
            title = "RMSSD",
            subtitle = "Root mean square of successive differences (ms) · x-axis = date"
        ) {
            HistoryMetricChart(
                label = "RMSSD (ms)",
                points = aggregatePoints(chartData.rmssd.toHistoryPoints(), historyPeriod),
                lineColor = TextPrimary,
                isLandscape = isLandscape, visiblePoints = visiblePoints,
                valueFormat = "%.2f"
            )
        }

        // ── 3. ln(RMSSD) ──────────────────────────────────────────────────
        HistoryGraphSection(
            title = "ln(RMSSD)",
            subtitle = "Natural log of RMSSD — used for baseline scoring · x-axis = date"
        ) {
            HistoryMetricChart(
                label = "ln(RMSSD)",
                points = aggregatePoints(chartData.lnRmssd.toHistoryPoints(), historyPeriod),
                lineColor = Color(0xFFD0D0D0),
                isLandscape = isLandscape, visiblePoints = visiblePoints,
                valueFormat = "%.2f"
            )
        }

        // ── 4. SDNN ───────────────────────────────────────────────────────
        HistoryGraphSection(
            title = "SDNN",
            subtitle = "Standard deviation of NN intervals (ms) · x-axis = date"
        ) {
            HistoryMetricChart(
                label = "SDNN (ms)",
                points = aggregatePoints(chartData.sdnn.toHistoryPoints(), historyPeriod),
                lineColor = Color(0xFFB0B0B0),
                isLandscape = isLandscape, visiblePoints = visiblePoints,
                valueFormat = "%.2f"
            )
        }

        // ── 5. Resting HR ─────────────────────────────────────────────────
        HistoryGraphSection(
            title = "Resting Heart Rate",
            subtitle = "Average HR during 2-minute session (bpm) · x-axis = date"
        ) {
            HistoryMetricChart(
                label = "Resting HR (bpm)",
                points = aggregatePoints(chartData.restingHr.toHistoryPoints(), historyPeriod),
                lineColor = Color(0xFF909090),
                invertGood = true,
                isLandscape = isLandscape, visiblePoints = visiblePoints,
                valueFormat = "%.2f"
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Calendar tab ───────────────────────────────────────────────────────────────

@Composable
private fun HrvCalendarContent(
    uiState: HrvReadinessHistoryUiState,
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
        HrvReadinessCalendar(
            displayedMonth = displayedMonth,
            datesWithReadings = uiState.datesWithReadings,
            selectedDate = uiState.selectedDate,
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        // Show multi-session list only when >1 reading on the selected day
        // (single-session case is handled by LaunchedEffect in the parent)
        if (uiState.selectedDayReadings.size > 1) {
            HrvMultiSessionList(
                readings = uiState.selectedDayReadings,
                onDismiss = onDismissMultiList,
                onSessionClick = onNavigateToDetail
            )
        }
    }
}

// ── Calendar widget ────────────────────────────────────────────────────────────

@Composable
private fun HrvReadinessCalendar(
    displayedMonth: YearMonth,
    datesWithReadings: Set<LocalDate>,
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
                            val hasReading = date in datesWithReadings
                            val isSelected = date == selectedDate
                            val isToday = date == LocalDate.now()
                            HrvCalendarDay(
                                dayNumber = dayNumber,
                                hasReading = hasReading,
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
private fun HrvCalendarDay(
    dayNumber: Int,
    hasReading: Boolean,
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
        hasReading -> TextPrimary
        else       -> TextDisabled
    }

    Column(
        modifier = modifier
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .clickable(enabled = hasReading, onClick = onClick)
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
        if (hasReading) {
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
private fun HrvMultiSessionList(
    readings: List<DailyReadingEntity>,
    onDismiss: () -> Unit,
    onSessionClick: (Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dateLabel = readings.firstOrNull()?.let { r ->
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
                        "${readings.size} sessions — tap one to view details",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = TextDisabled)
                }
            }

            HorizontalDivider(color = SurfaceDark)

            readings.forEach { reading ->
                HrvSessionSummaryCard(
                    reading = reading,
                    onClick = { onSessionClick(reading.readingId) }
                )
            }
        }
    }
}

@Composable
private fun HrvSessionSummaryCard(
    reading: DailyReadingEntity,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val timeLabel = Instant.ofEpochMilli(reading.timestamp).atZone(zone)
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
            // Time + basic metrics
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "RMSSD ${String.format("%.1f", reading.rawRmssdMs)} ms  ·  " +
                    "SDNN ${String.format("%.1f", reading.sdnnMs)} ms  ·  " +
                    "HR ${String.format("%.0f", reading.restingHrBpm)} bpm",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            // Score badge
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    reading.readinessScore.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    "score",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun HrvEmptyHistoryContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
            "Complete a 2-minute HRV Readiness session to see your history here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
    }
}

private fun hrvScoreColor(score: Float) = when {
    score >= 80f -> Color(0xFFE8E8E8)
    score >= 60f -> Color(0xFFB0B0B0)
    else         -> Color(0xFF888888)
}
