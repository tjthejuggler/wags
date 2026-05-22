package com.example.wags.ui.breathing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.ui.common.*
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class RfHistoryTab(val label: String) {
    GRAPHS("Graphs"),
    CALENDAR("Calendar")
}

// ── Conversion helpers ────────────────────────────────────────────────────────

private fun RfChartPoint.toHistoryPoint() = HistoryChartPoint(
    index = index,
    value = value,
    dateLabel = dateLabel
)

private fun List<RfChartPoint>.toHistoryPoints() = map { it.toHistoryPoint() }

private fun RfChartTimePeriod.toHistoryPeriod() = when (this) {
    RfChartTimePeriod.WEEK -> HistoryTimePeriod.WEEK
    RfChartTimePeriod.MONTH -> HistoryTimePeriod.MONTH
    RfChartTimePeriod.THREE_MONTHS -> HistoryTimePeriod.THREE_MONTHS
    RfChartTimePeriod.YEAR -> HistoryTimePeriod.YEAR
    RfChartTimePeriod.ALL -> HistoryTimePeriod.ALL
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RfAssessmentHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (sessionTimestamp: Long) -> Unit,
    onNavigateToSessionDetail: (sessionId: Long) -> Unit = {},
    viewModel: RfAssessmentHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(RfHistoryTab.GRAPHS) }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    // Auto-navigate when exactly 1 event on the selected day
    val selectedDayAssessments = uiState.selectedDayAssessments
    val selectedDaySessions = uiState.selectedDaySessions
    val totalEvents = selectedDayAssessments.size + selectedDaySessions.size

    LaunchedEffect(selectedDayAssessments, selectedDaySessions) {
        if (totalEvents == 1) {
            if (selectedDayAssessments.size == 1) {
                onNavigateToDetail(selectedDayAssessments.first().timestamp)
            } else if (selectedDaySessions.size == 1) {
                onNavigateToSessionDetail(selectedDaySessions.first().sessionId)
            }
            viewModel.clearSelection()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("RF Assessment History") },
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
                RfHistoryTab.entries.forEach { tab ->
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

            if (uiState.allAssessments.isEmpty() && uiState.allSessions.isEmpty()) {
                RfEmptyHistoryContent()
            } else {
                when (selectedTab) {
                    RfHistoryTab.GRAPHS -> RfGraphsContent(
                        uiState = uiState,
                        onTimePeriodChange = viewModel::setTimePeriod
                    )
                    RfHistoryTab.CALENDAR -> RfCalendarContent(
                        uiState = uiState,
                        displayedMonth = displayedMonth,
                        viewModel = viewModel,
                        onDayClick = { date ->
                            if (date in uiState.datesWithAssessments || date in uiState.datesWithSessions) {
                                if (uiState.selectedDate == date) viewModel.clearSelection()
                                else viewModel.selectDate(date)
                            }
                        },
                        onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                        onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                        onDismissMultiList = { viewModel.clearSelection() },
                        onNavigateToDetail = { timestamp ->
                            viewModel.clearSelection()
                            onNavigateToDetail(timestamp)
                        },
                        onNavigateToSessionDetail = { sessionId ->
                            viewModel.clearSelection()
                            onNavigateToSessionDetail(sessionId)
                        }
                    )
                }
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@Composable
private fun RfGraphsContent(
    uiState: RfAssessmentHistoryUiState,
    onTimePeriodChange: (RfChartTimePeriod) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val historyPeriod = uiState.timePeriod.toHistoryPeriod()
    val visiblePoints = historyPeriod.visiblePoints

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Pinned controls at the top ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundDark)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryTimePeriodSelector(
                selected = historyPeriod,
                onSelect = { onTimePeriodChange(
                    when (it) {
                        HistoryTimePeriod.WEEK -> RfChartTimePeriod.WEEK
                        HistoryTimePeriod.MONTH -> RfChartTimePeriod.MONTH
                        HistoryTimePeriod.THREE_MONTHS -> RfChartTimePeriod.THREE_MONTHS
                        HistoryTimePeriod.YEAR -> RfChartTimePeriod.YEAR
                        HistoryTimePeriod.ALL -> RfChartTimePeriod.ALL
                    }
                )}
            )

            val totalReadings = uiState.allAssessments.size + uiState.allSessions.size
            Text(
                "$totalReadings readings total · showing ${uiState.timePeriod.label}",
                style = MaterialTheme.typography.labelMedium,
                color = TextDisabled
            )
        }

        HorizontalDivider(color = SurfaceDark, thickness = 1.dp)

        // ── Scrollable chart area ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── ASSESSMENT GRAPHS ─────────────────────────────────────────
            if (uiState.allAssessments.isNotEmpty()) {
                Text(
                    "ASSESSMENT HISTORY",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Header summary
                RfHistorySummaryCard(uiState = uiState)

                // ── 1. Optimal BPM ──────────────────────────────────────
                HistoryGraphSection(
                    title = "Optimal Breathing Rate",
                    subtitle = "Your resonance frequency (BPM) over time · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "Optimal BPM",
                        points = aggregatePoints(uiState.chartData.optimalBpm.toHistoryPoints(), historyPeriod),
                        lineColor = TextPrimary,
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // ── 2. Coherence Ratio ──────────────────────────────────
                HistoryGraphSection(
                    title = "Coherence Ratio",
                    subtitle = "Peak coherence achieved per assessment · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "Coherence Ratio",
                        points = aggregatePoints(uiState.chartData.coherenceRatio.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFFD0D0D0),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // ── 3. LF Power ────────────────────────────────────────
                HistoryGraphSection(
                    title = "LF Power",
                    subtitle = "Absolute low-frequency power at resonance (ms²/Hz) · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "LF Power (ms²/Hz)",
                        points = aggregatePoints(uiState.chartData.lfPower.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFFB0B0B0),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // ── 4. RMSSD ───────────────────────────────────────────
                HistoryGraphSection(
                    title = "RMSSD",
                    subtitle = "Root mean square of successive differences (ms) · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "RMSSD (ms)",
                        points = aggregatePoints(uiState.chartData.rmssd.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFF909090),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // ── 5. SDNN ────────────────────────────────────────────
                HistoryGraphSection(
                    title = "SDNN",
                    subtitle = "Standard deviation of NN intervals (ms) · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "SDNN (ms)",
                        points = aggregatePoints(uiState.chartData.sdnn.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFF808080),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // ── 6. Composite Score ─────────────────────────────────
                HistoryGraphSection(
                    title = "Composite Score",
                    subtitle = "Overall assessment quality score · x-axis = date"
                ) {
                    val pts = aggregatePoints(uiState.chartData.compositeScore.toHistoryPoints(), historyPeriod)
                    HistoryScoreChart(
                        points = pts,
                        scoreColorFn = { v -> rfScoreColor(v) },
                        referenceLines = emptyList(),
                        zoneLegendItems = emptyList(),
                        tooltipLabel = "Composite Score",
                        isLandscape = isLandscape, visiblePoints = visiblePoints
                    )
                }
            }

            // ── SESSION GRAPHS ──────────────────────────────────────────
            if (uiState.allSessions.isNotEmpty()) {
                HorizontalDivider(color = SurfaceDark, modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    "SESSION HISTORY",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Session Coherence
                HistoryGraphSection(
                    title = "Session Coherence",
                    subtitle = "Mean coherence ratio per session · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "Coherence Ratio",
                        points = aggregatePoints(uiState.sessionChartData.coherenceRatio.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFFD0D0D0),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // Session RMSSD
                HistoryGraphSection(
                    title = "Session RMSSD",
                    subtitle = "Mean RMSSD per session (ms) · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "RMSSD (ms)",
                        points = aggregatePoints(uiState.sessionChartData.rmssd.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFF909090),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // Session SDNN
                HistoryGraphSection(
                    title = "Session SDNN",
                    subtitle = "Mean SDNN per session (ms) · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "SDNN (ms)",
                        points = aggregatePoints(uiState.sessionChartData.sdnn.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFF808080),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }

                // Session Duration
                HistoryGraphSection(
                    title = "Session Duration",
                    subtitle = "Duration per session (minutes) · x-axis = date"
                ) {
                    HistoryMetricChart(
                        label = "Duration (min)",
                        points = aggregatePoints(uiState.sessionChartData.duration.toHistoryPoints(), historyPeriod),
                        lineColor = Color(0xFFB0B0B0),
                        isLandscape = isLandscape, visiblePoints = visiblePoints,
                        valueFormat = "%.2f"
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Calendar tab ───────────────────────────────────────────────────────────────

@Composable
private fun RfCalendarContent(
    uiState: RfAssessmentHistoryUiState,
    displayedMonth: YearMonth,
    viewModel: RfAssessmentHistoryViewModel,
    onDayClick: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDismissMultiList: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToSessionDetail: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RfCalendar(
            displayedMonth = displayedMonth,
            datesWithAssessments = uiState.datesWithAssessments,
            datesWithSessions = uiState.datesWithSessions,
            selectedDate = uiState.selectedDate,
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        // Show combined event list when >1 total events on the selected day
        val totalEvents = uiState.selectedDayAssessments.size + uiState.selectedDaySessions.size
        if (totalEvents > 1) {
            RfDayEventList(
                assessments = uiState.selectedDayAssessments,
                sessions = uiState.selectedDaySessions,
                onDismiss = onDismissMultiList,
                onAssessmentClick = onNavigateToDetail,
                onSessionClick = onNavigateToSessionDetail
            )
        }
    }
}

// ── Calendar widget ────────────────────────────────────────────────────────────

@Composable
private fun RfCalendar(
    displayedMonth: YearMonth,
    datesWithAssessments: Set<LocalDate>,
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
                            val hasAssessment = date in datesWithAssessments
                            val hasSession = date in datesWithSessions
                            val isSelected = date == selectedDate
                            val isToday = date == LocalDate.now()
                            RfCalendarDay(
                                dayNumber = dayNumber,
                                hasAssessment = hasAssessment,
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

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(TextSecondary))
                Spacer(Modifier.width(4.dp))
                Text("Assessment", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(EcgCyan.copy(alpha = 0.6f)))
                Spacer(Modifier.width(4.dp))
                Text("Session", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
    }
}

@Composable
private fun RfCalendarDay(
    dayNumber: Int,
    hasAssessment: Boolean,
    hasSession: Boolean,
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
        isSelected                  -> TextPrimary
        isToday                     -> TextPrimary
        hasAssessment || hasSession -> TextPrimary
        else                        -> TextDisabled
    }

    Column(
        modifier = modifier
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .clickable(enabled = hasAssessment || hasSession, onClick = onClick)
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasAssessment) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) TextPrimary else TextSecondary)
                )
            }
            if (hasSession) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) EcgCyan else EcgCyan.copy(alpha = 0.6f))
                )
            }
            if (!hasAssessment && !hasSession) {
                Spacer(Modifier.height(5.dp))
            }
        }
    }
}

// ── Combined day event list (assessments + sessions) ────────────────────────────

@Composable
private fun RfDayEventList(
    assessments: List<RfAssessmentEntity>,
    sessions: List<ResonanceSessionEntity>,
    onDismiss: () -> Unit,
    onAssessmentClick: (Long) -> Unit,
    onSessionClick: (Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dateLabel = (assessments.firstOrNull()?.let { a ->
        Instant.ofEpochMilli(a.timestamp).atZone(zone)
    } ?: sessions.firstOrNull()?.let { s ->
        Instant.ofEpochMilli(s.timestamp).atZone(zone)
    })?.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy")) ?: ""

    val totalEvents = assessments.size + sessions.size

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
                        "$totalEvents events — tap one to view details",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = TextDisabled)
                }
            }

            HorizontalDivider(color = SurfaceDark)

            // Assessment cards
            assessments.forEach { assessment ->
                RfAssessmentEventCard(
                    assessment = assessment,
                    onClick = { onAssessmentClick(assessment.timestamp) }
                )
            }

            // Session cards
            sessions.forEach { session ->
                RfSessionEventCard(
                    session = session,
                    onClick = { onSessionClick(session.sessionId) }
                )
            }
        }
    }
}

@Composable
private fun RfAssessmentEventCard(
    assessment: RfAssessmentEntity,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val timeLabel = Instant.ofEpochMilli(assessment.timestamp).atZone(zone)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TextSecondary)
                    )
                    Text(
                        "$timeLabel  •  Assessment",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "${assessment.protocolType}  ·  " +
                    "Coherence ${String.format("%.2f", assessment.maxCoherenceRatio)}  ·  " +
                    "Score ${String.format("%.0f", assessment.compositeScore)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format("%.2f", assessment.optimalBpm),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

@Composable
private fun RfSessionEventCard(
    session: ResonanceSessionEntity,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val timeLabel = Instant.ofEpochMilli(session.timestamp).atZone(zone)
        .format(DateTimeFormatter.ofPattern("h:mm a"))
    val durationMin = session.durationSeconds / 60
    val durationSec = session.durationSeconds % 60

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(EcgCyan.copy(alpha = 0.6f))
                    )
                    Text(
                        "$timeLabel  •  Session",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "%d:%02d  ·  Coherence %.1f  ·  RMSSD %.0f ms".format(
                        durationMin, durationSec,
                        session.meanCoherenceRatio,
                        session.meanRmssdMs
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format("%.2f", session.breathingRateBpm),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

// ── Summary card ───────────────────────────────────────────────────────────────

@Composable
private fun RfHistorySummaryCard(uiState: RfAssessmentHistoryUiState) {
    val count = uiState.allAssessments.size
    val latestBpm = uiState.chartData.optimalBpm.lastOrNull()?.value
    val avgBpm = if (uiState.chartData.optimalBpm.isNotEmpty())
        uiState.chartData.optimalBpm.map { it.value }.average().toFloat() else null
    val latestCoherence = uiState.chartData.coherenceRatio.lastOrNull()?.value
    val avgCoherence = if (uiState.chartData.coherenceRatio.isNotEmpty())
        uiState.chartData.coherenceRatio.map { it.value }.average().toFloat() else null

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
                RfSummaryChip(label = "Assessments", value = count.toString(), color = TextPrimary)
                latestBpm?.let {
                    RfSummaryChip(
                        label = "Latest BPM",
                        value = String.format("%.2f", it),
                        color = TextPrimary
                    )
                }
                avgBpm?.let {
                    RfSummaryChip(
                        label = "Avg BPM",
                        value = String.format("%.2f", it),
                        color = TextSecondary
                    )
                }
            }
            if (latestCoherence != null && avgCoherence != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RfSummaryChip(
                        label = "Latest Coherence",
                        value = String.format("%.2f", latestCoherence),
                        color = TextPrimary
                    )
                    RfSummaryChip(
                        label = "Avg Coherence",
                        value = String.format("%.2f", avgCoherence),
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun RfSummaryChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun RfEmptyHistoryContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "No history yet",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary
        )
        Text(
            "Complete an RF Assessment or breathing session to see your history here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
    }
}

private fun rfScoreColor(score: Float) = when {
    score >= 80f -> Color(0xFFE8E8E8)
    score >= 60f -> Color(0xFFB0B0B0)
    else         -> Color(0xFF888888)
}
