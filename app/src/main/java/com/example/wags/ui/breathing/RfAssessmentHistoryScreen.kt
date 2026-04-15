package com.example.wags.ui.breathing

import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.common.LiveSensorActionsCallback
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
                    RfHistoryTab.GRAPHS -> RfGraphsContent(uiState = uiState)
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
private fun RfGraphsContent(uiState: RfAssessmentHistoryUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── ASSESSMENT GRAPHS ───────────────────────────────────────────
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

            // ── 1. Optimal BPM ──────────────────────────────────────────
            RfGraphSection(
                title = "Optimal Breathing Rate",
                subtitle = "Your resonance frequency (BPM) over time"
            ) {
                RfMetricChart(
                    label = "Optimal BPM",
                    points = uiState.chartData.optimalBpm,
                    lineColor = TextPrimary
                )
            }

            // ── 2. Coherence Ratio ──────────────────────────────────────
            RfGraphSection(
                title = "Coherence Ratio",
                subtitle = "Peak coherence achieved per assessment"
            ) {
                RfMetricChart(
                    label = "Coherence Ratio",
                    points = uiState.chartData.coherenceRatio,
                    lineColor = Color(0xFFD0D0D0)
                )
            }

            // ── 3. LF Power ────────────────────────────────────────────
            RfGraphSection(
                title = "LF Power",
                subtitle = "Absolute low-frequency power at resonance (ms²/Hz)"
            ) {
                RfMetricChart(
                    label = "LF Power (ms²/Hz)",
                    points = uiState.chartData.lfPower,
                    lineColor = Color(0xFFB0B0B0)
                )
            }

            // ── 4. RMSSD ───────────────────────────────────────────────
            RfGraphSection(
                title = "RMSSD",
                subtitle = "Root mean square of successive differences (ms)"
            ) {
                RfMetricChart(
                    label = "RMSSD (ms)",
                    points = uiState.chartData.rmssd,
                    lineColor = Color(0xFF909090)
                )
            }

            // ── 5. SDNN ────────────────────────────────────────────────
            RfGraphSection(
                title = "SDNN",
                subtitle = "Standard deviation of NN intervals (ms)"
            ) {
                RfMetricChart(
                    label = "SDNN (ms)",
                    points = uiState.chartData.sdnn,
                    lineColor = Color(0xFF808080)
                )
            }

            // ── 6. Composite Score ─────────────────────────────────────
            RfGraphSection(
                title = "Composite Score",
                subtitle = "Overall assessment quality score"
            ) {
                RfScoreChart(points = uiState.chartData.compositeScore)
            }
        }

        // ── SESSION GRAPHS ──────────────────────────────────────────────
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
            RfGraphSection(
                title = "Session Coherence",
                subtitle = "Mean coherence ratio per session"
            ) {
                RfMetricChart(
                    label = "Coherence Ratio",
                    points = uiState.sessionChartData.coherenceRatio,
                    lineColor = Color(0xFFD0D0D0)
                )
            }

            // Session RMSSD
            RfGraphSection(
                title = "Session RMSSD",
                subtitle = "Mean RMSSD per session (ms)"
            ) {
                RfMetricChart(
                    label = "RMSSD (ms)",
                    points = uiState.sessionChartData.rmssd,
                    lineColor = Color(0xFF909090)
                )
            }

            // Session SDNN
            RfGraphSection(
                title = "Session SDNN",
                subtitle = "Mean SDNN per session (ms)"
            ) {
                RfMetricChart(
                    label = "SDNN (ms)",
                    points = uiState.sessionChartData.sdnn,
                    lineColor = Color(0xFF808080)
                )
            }

            // Session Duration
            RfGraphSection(
                title = "Session Duration",
                subtitle = "Duration per session (minutes)"
            ) {
                RfMetricChart(
                    label = "Duration (min)",
                    points = uiState.sessionChartData.duration,
                    lineColor = Color(0xFFB0B0B0)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
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
                    "Coherence ${String.format("%.1f", assessment.maxCoherenceRatio)}  ·  " +
                    "Score ${String.format("%.0f", assessment.compositeScore)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format("%.1f", assessment.optimalBpm),
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
                    String.format("%.1f", session.breathingRateBpm),
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
                        value = String.format("%.1f", it),
                        color = TextPrimary
                    )
                }
                avgBpm?.let {
                    RfSummaryChip(
                        label = "Avg BPM",
                        value = String.format("%.1f", it),
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

// ── Graph section wrapper ──────────────────────────────────────────────────────

@Composable
private fun RfGraphSection(
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

// ── Score chart (with zone reference lines) ────────────────────────────────────

@Composable
private fun RfScoreChart(points: List<RfChartPoint>) {
    if (points.isEmpty()) { RfNoDataLabel(); return }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val yPad = ((max - min) * 0.1f).coerceAtLeast(1f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RfStatChip("Latest", String.format("%.0f", latest.value), rfScoreColor(latest.value))
            RfStatChip("Avg", String.format("%.0f", avg), rfScoreColor(avg))
            RfStatChip("Min", String.format("%.0f", min), rfScoreColor(min))
            RfStatChip("Max", String.format("%.0f", max), rfScoreColor(max))
        }

        RfLineChartCanvas(
            points = points.map { it.index to it.value },
            lineColor = TextPrimary,
            fillAlpha = 0.15f,
            yMin = (min - yPad).coerceAtLeast(0f),
            yMax = max + yPad,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
    }
}

// ── Generic metric chart ───────────────────────────────────────────────────────

@Composable
private fun RfMetricChart(
    label: String,
    points: List<RfChartPoint>,
    lineColor: Color
) {
    if (points.isEmpty()) { RfNoDataLabel(); return }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val yPad = ((max - min) * 0.1f).coerceAtLeast(0.1f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                "Latest: ${String.format("%.2f", latest.value)}",
                style = MaterialTheme.typography.labelMedium,
                color = lineColor,
                fontWeight = FontWeight.Bold
            )
        }

        RfLineChartCanvas(
            points = points.map { it.index to it.value },
            lineColor = lineColor,
            fillAlpha = 0.10f,
            yMin = min - yPad,
            yMax = max + yPad,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
        )

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

// ── Pure Canvas line chart ─────────────────────────────────────────────────────

@Composable
private fun RfLineChartCanvas(
    points: List<Pair<Float, Float>>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Canvas(modifier = modifier) {
            drawCircle(
                color = lineColor,
                radius = 6f,
                center = Offset(size.width / 2f, size.height / 2f)
            )
        }
        return
    }

    val yRange = (yMax - yMin).coerceAtLeast(0.001f)
    val yMid = (yMin + yMax) / 2f

    // Smart format: use integers when values are large enough, decimals otherwise
    fun fmtLabel(v: Float): String = when {
        yRange >= 10f -> String.format("%.0f", v)
        yRange >= 1f  -> String.format("%.1f", v)
        else          -> String.format("%.2f", v)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // ── Y-axis labels ────────────────────────────────────────────────
        val textPaint = NativePaint().apply {
            color = android.graphics.Color.rgb(0x70, 0x70, 0x70) // Ash
            textSize = 24f  // ~10sp at mdpi
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            textAlign = NativePaint.Align.RIGHT
        }
        val labelMargin = 48f  // px reserved for Y-axis labels on the left
        val chartLeft = labelMargin + 4f
        val chartWidth = w - chartLeft

        val xStep = chartWidth / (points.size - 1).toFloat()

        fun xOf(i: Int) = chartLeft + i * xStep
        fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

        // Draw 3 horizontal grid lines (bottom=yMin, middle=yMid, top=yMax)
        // with Y-axis labels
        val gridColor = Color(0xFF2A2A2A)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
        val gridValues = listOf(yMin, yMid, yMax)
        for (v in gridValues) {
            val gy = yOf(v)
            // Grid line
            drawLine(
                color = gridColor,
                start = Offset(chartLeft, gy),
                end = Offset(w, gy),
                strokeWidth = 1f,
                pathEffect = dashEffect
            )
            // Y-axis label
            drawContext.canvas.nativeCanvas.drawText(
                fmtLabel(v),
                labelMargin - 2f,
                gy + 8f,  // offset down slightly to vertically center on the line
                textPaint
            )
        }

        // ── Fill ─────────────────────────────────────────────────────────
        val fillPath = Path().apply {
            moveTo(xOf(0), h)
            lineTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].second))
            }
            lineTo(xOf(points.size - 1), h)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = fillAlpha))

        // ── Line ─────────────────────────────────────────────────────────
        val linePath = Path().apply {
            moveTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].second))
            }
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // ── Latest dot ───────────────────────────────────────────────────
        val lastX = xOf(points.size - 1)
        val lastY = yOf(points.last().second)
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
        drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))
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
private fun RfStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun RfNoDataLabel() {
    Text(
        "No data yet",
        style = MaterialTheme.typography.bodySmall,
        color = TextDisabled,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
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
