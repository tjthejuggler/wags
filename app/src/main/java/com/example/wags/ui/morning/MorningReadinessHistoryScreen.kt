package com.example.wags.ui.morning

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.ui.common.*
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class HistoryTab(val label: String) {
    GRAPHS("Graphs"),
    CALENDAR("Calendar")
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReadinessHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (Long) -> Unit = {},
    viewModel: MorningReadinessHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(HistoryTab.GRAPHS.ordinal) }
    val selectedTab = HistoryTab.entries[selectedTabOrdinal]
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Morning Readiness History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
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
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = SurfaceDark,
                contentColor = EcgCyan
            ) {
                HistoryTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTabOrdinal = tab.ordinal },
                        modifier = Modifier.background(
                            if (isSelected) EcgCyan.copy(alpha = 0.15f)
                            else Color.Transparent
                        ),
                        text = {
                            Text(
                                tab.label,
                                color = if (isSelected) EcgCyan else TextDisabled,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (uiState.allReadings.isEmpty()) {
                EmptyHistoryContent()
            } else {
                when (selectedTab) {
                    HistoryTab.GRAPHS -> GraphsContent(
                        chartData = uiState.chartData,
                        readingCount = uiState.allReadings.size,
                        timePeriod = uiState.timePeriod,
                        onTimePeriodChange = viewModel::setTimePeriod
                    )
                    HistoryTab.CALENDAR -> CalendarContent(
                        uiState = uiState,
                        displayedMonth = displayedMonth,
                        onDayClick = { date ->
                            if (date in uiState.datesWithReadings) {
                                val zone = ZoneId.systemDefault()
                                val reading = uiState.allReadings.firstOrNull { entity ->
                                    Instant.ofEpochMilli(entity.timestamp)
                                        .atZone(zone).toLocalDate() == date
                                }
                                reading?.let { onNavigateToDetail(it.id) }
                            }
                        },
                        onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                        onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                        onDismissDetail = { viewModel.clearSelection() }
                    )
                }
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

/** Convert local ChartPoint to centralized HistoryChartPoint. */
private fun ChartPoint.toHistoryPoint() = HistoryChartPoint(
    index = dayIndex,
    value = value,
    dateLabel = label
)

private fun List<ChartPoint>.toHistoryPoints() = map { it.toHistoryPoint() }

/** Convert local ChartTimePeriod to centralized HistoryTimePeriod. */
private fun ChartTimePeriod.toHistoryPeriod() = when (this) {
    ChartTimePeriod.WEEK -> HistoryTimePeriod.WEEK
    ChartTimePeriod.MONTH -> HistoryTimePeriod.MONTH
    ChartTimePeriod.THREE_MONTHS -> HistoryTimePeriod.THREE_MONTHS
    ChartTimePeriod.YEAR -> HistoryTimePeriod.YEAR
    ChartTimePeriod.ALL -> HistoryTimePeriod.ALL
}

@Composable
private fun GraphsContent(
    chartData: MorningReadinessChartData,
    readingCount: Int,
    timePeriod: ChartTimePeriod,
    onTimePeriodChange: (ChartTimePeriod) -> Unit
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
                    HistoryTimePeriod.WEEK -> ChartTimePeriod.WEEK
                    HistoryTimePeriod.MONTH -> ChartTimePeriod.MONTH
                    HistoryTimePeriod.THREE_MONTHS -> ChartTimePeriod.THREE_MONTHS
                    HistoryTimePeriod.YEAR -> ChartTimePeriod.YEAR
                    HistoryTimePeriod.ALL -> ChartTimePeriod.ALL
                }
            )}
        )

        Text(
            "$readingCount readings total · showing ${timePeriod.label}",
            style = MaterialTheme.typography.labelMedium,
            color = TextDisabled
        )

        // ── 1. Overall Readiness Score ─────────────────────────────────────
        HistoryGraphSection(title = "Overall Readiness Score", subtitle = "0–100 composite score · x-axis = date") {
            val pts = aggregatePoints(chartData.readinessScore.toHistoryPoints(), historyPeriod)
            HistoryScoreChart(points = pts, isLandscape = isLandscape, visiblePoints = visiblePoints)
        }

        // ── 2. Supine HRV ─────────────────────────────────────────────────
        HistoryGraphSection(title = "Supine HRV", subtitle = "Measured lying down · x-axis = date") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryMetricChart(label = "RMSSD (ms)", points = aggregatePoints(chartData.supineRmssd.toHistoryPoints(), historyPeriod), lineColor = EcgCyan, isLandscape = isLandscape, visiblePoints = visiblePoints)
                HistoryMetricChart(label = "HRV Score (ln×20)", points = aggregatePoints(chartData.supineHrvScore.toHistoryPoints(), historyPeriod), lineColor = ReadinessGreen, isLandscape = isLandscape, visiblePoints = visiblePoints)
                HistoryMetricChart(label = "SDNN (ms)", points = aggregatePoints(chartData.supineSdnn.toHistoryPoints(), historyPeriod), lineColor = ReadinessBlue, isLandscape = isLandscape, visiblePoints = visiblePoints)
                HistoryMetricChart(label = "Resting HR (bpm)", points = aggregatePoints(chartData.restingHr.toHistoryPoints(), historyPeriod), lineColor = ReadinessOrange, isLandscape = isLandscape, visiblePoints = visiblePoints)
            }
        }

        // ── 3. Standing HRV ───────────────────────────────────────────────
        HistoryGraphSection(title = "Standing HRV", subtitle = "Measured after standing · x-axis = date") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryMetricChart(label = "RMSSD (ms)", points = aggregatePoints(chartData.standingRmssd.toHistoryPoints(), historyPeriod), lineColor = EcgCyan, isLandscape = isLandscape, visiblePoints = visiblePoints)
                HistoryMetricChart(label = "HRV Score (ln×20)", points = aggregatePoints(chartData.standingHrvScore.toHistoryPoints(), historyPeriod), lineColor = ReadinessGreen, isLandscape = isLandscape, visiblePoints = visiblePoints)
                HistoryMetricChart(label = "SDNN (ms)", points = aggregatePoints(chartData.standingSdnn.toHistoryPoints(), historyPeriod), lineColor = ReadinessBlue, isLandscape = isLandscape, visiblePoints = visiblePoints)
                HistoryMetricChart(label = "Peak Stand HR (bpm)", points = aggregatePoints(chartData.peakStandHr.toHistoryPoints(), historyPeriod), lineColor = ReadinessRed, isLandscape = isLandscape, visiblePoints = visiblePoints)
            }
        }

        // ── 4. Orthostatic Response ───────────────────────────────────────
        if (chartData.thirtyFifteenRatio.isNotEmpty() || chartData.ohrr60s.isNotEmpty()) {
            HistoryGraphSection(title = "Orthostatic Response", subtitle = "Autonomic stand-up reflex · x-axis = date") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chartData.thirtyFifteenRatio.isNotEmpty()) {
                        HistoryMetricChart(label = "30:15 Ratio", points = aggregatePoints(chartData.thirtyFifteenRatio.toHistoryPoints(), historyPeriod), lineColor = EcgCyan, isLandscape = isLandscape, visiblePoints = visiblePoints)
                    }
                    if (chartData.ohrr60s.isNotEmpty()) {
                        HistoryMetricChart(label = "OHRR at 60 s (%)", points = aggregatePoints(chartData.ohrr60s.toHistoryPoints(), historyPeriod), lineColor = ReadinessGreen, isLandscape = isLandscape, visiblePoints = visiblePoints)
                    }
                }
            }
        }

        // ── 5. Respiratory Rate ───────────────────────────────────────────
        if (chartData.respiratoryRate.isNotEmpty()) {
            HistoryGraphSection(title = "Respiratory Rate", subtitle = "Breaths per minute (supine) · x-axis = date") {
                HistoryMetricChart(label = "Resp. Rate (brpm)", points = aggregatePoints(chartData.respiratoryRate.toHistoryPoints(), historyPeriod), lineColor = ReadinessBlue, isLandscape = isLandscape, visiblePoints = visiblePoints)
            }
        }

        // ── 6. Hooper Wellness Index ──────────────────────────────────────
        if (chartData.hooperTotal.isNotEmpty()) {
            HistoryGraphSection(title = "Hooper Wellness Index", subtitle = "Lower = better (0–20 scale) · x-axis = date") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HistoryMetricChart(label = "Total Score (/20)", points = aggregatePoints(chartData.hooperTotal.toHistoryPoints(), historyPeriod), lineColor = EcgCyan, isLandscape = isLandscape, visiblePoints = visiblePoints)
                    if (chartData.hooperSleep.isNotEmpty()) HistoryMetricChart(label = "Sleep Quality (/5)", points = aggregatePoints(chartData.hooperSleep.toHistoryPoints(), historyPeriod), lineColor = ReadinessBlue, isLandscape = isLandscape, visiblePoints = visiblePoints)
                    if (chartData.hooperFatigue.isNotEmpty()) HistoryMetricChart(label = "Fatigue (/5)", points = aggregatePoints(chartData.hooperFatigue.toHistoryPoints(), historyPeriod), lineColor = ReadinessOrange, isLandscape = isLandscape, visiblePoints = visiblePoints)
                    if (chartData.hooperSoreness.isNotEmpty()) HistoryMetricChart(label = "Muscle Soreness (/5)", points = aggregatePoints(chartData.hooperSoreness.toHistoryPoints(), historyPeriod), lineColor = ReadinessRed, isLandscape = isLandscape, visiblePoints = visiblePoints)
                    if (chartData.hooperStress.isNotEmpty()) HistoryMetricChart(label = "Stress (/5)", points = aggregatePoints(chartData.hooperStress.toHistoryPoints(), historyPeriod), lineColor = CoherencePink, isLandscape = isLandscape, visiblePoints = visiblePoints)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Calendar tab ───────────────────────────────────────────────────────────────

@Composable
private fun CalendarContent(
    uiState: MorningReadinessHistoryUiState,
    displayedMonth: YearMonth,
    onDayClick: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDismissDetail: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ReadinessCalendar(
            displayedMonth = displayedMonth,
            datesWithReadings = uiState.datesWithReadings,
            selectedDate = null,
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )
    }
}

// ── Calendar ───────────────────────────────────────────────────────────────────

@Composable
private fun ReadinessCalendar(
    displayedMonth: YearMonth,
    datesWithReadings: Set<LocalDate>,
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                }
                Text(
                    text = displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onNextMonth) {
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
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
                            val hasReading = date in datesWithReadings
                            val isSelected = date == selectedDate
                            val isToday = date == LocalDate.now()
                            CalendarDay(
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(EcgCyan))
                Spacer(Modifier.width(6.dp))
                Text("Reading recorded — tap to view", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
    }
}

@Composable
private fun CalendarDay(
    dayNumber: Int,
    hasReading: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isSelected -> EcgCyan.copy(alpha = 0.25f)
        isToday    -> SurfaceDark
        else       -> Color.Transparent
    }
    val textColor = when {
        isSelected -> EcgCyan
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
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (isSelected) EcgCyan else EcgCyanDim))
        } else {
            Spacer(Modifier.height(5.dp))
        }
    }
}

// ── Detail card ────────────────────────────────────────────────────────────────

@Composable
private fun ReadingDetailCard(
    reading: MorningReadinessEntity,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(reading.timestamp).atZone(zone)
    val dateLabel = dateTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
    val timeLabel = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))

    val scoreColor = when (reading.readinessColor) {
        "GREEN"  -> ReadinessGreen
        "RED"    -> ReadinessRed
        else     -> ReadinessOrange
    }

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
                    Text(dateLabel, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(timeLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                TextButton(onClick = onDismiss) { Text("✕", color = TextDisabled) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = reading.readinessScore.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = scoreColor
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Readiness", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(reading.readinessColor, style = MaterialTheme.typography.labelMedium, color = scoreColor, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = SurfaceDark)
            Text("Supine HRV", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
            DetailRow("RMSSD", "${String.format("%.1f", reading.supineRmssdMs)} ms")
            DetailRow("HRV Score (ln×20)", (reading.supineLnRmssd * 20).toInt().toString())
            DetailRow("SDNN", "${String.format("%.1f", reading.supineSdnnMs)} ms")
            DetailRow("Resting HR", "${reading.supineRhr} bpm")

            if (reading.standingRmssdMs != null) {
                HorizontalDivider(color = SurfaceDark)
                Text("Standing HRV", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
                DetailRow("RMSSD", "${String.format("%.1f", reading.standingRmssdMs)} ms")
                reading.standingLnRmssd?.let { DetailRow("HRV Score (ln×20)", (it * 20).toInt().toString()) }
                reading.standingSdnnMs?.let { DetailRow("SDNN", "${String.format("%.1f", it)} ms") }
                reading.peakStandHr?.let { DetailRow("Peak Stand HR", "$it bpm") }
            }

            HorizontalDivider(color = SurfaceDark)
            Text("Orthostatic Response", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
            reading.thirtyFifteenRatio?.let { DetailRow("30:15 Ratio", String.format("%.3f", it)) }
            reading.ohrrAt20sPercent?.let { DetailRow("OHRR at 20s", "${String.format("%.1f", it)} %") }
            reading.ohrrAt60sPercent?.let { DetailRow("OHRR at 60s", "${String.format("%.1f", it)} %") }

            reading.respiratoryRateBpm?.let {
                HorizontalDivider(color = SurfaceDark)
                Text("Respiratory", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
                DetailRow("Respiratory Rate", "${String.format("%.1f", it)} brpm")
                if (reading.slowBreathingFlagged) {
                    Text("⚠ Slow breathing detected — score adjusted", style = MaterialTheme.typography.bodySmall, color = ReadinessOrange)
                }
            }

            reading.hooperTotal?.let { total ->
                HorizontalDivider(color = SurfaceDark)
                Text("Hooper Wellness Index", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
                DetailRow("Total", "${String.format("%.0f", total)} / 20")
                reading.hooperSleep?.let { DetailRow("Sleep Quality", "$it / 5") }
                reading.hooperFatigue?.let { DetailRow("Fatigue", "$it / 5") }
                reading.hooperSoreness?.let { DetailRow("Muscle Soreness", "$it / 5") }
                reading.hooperStress?.let { DetailRow("Stress", "$it / 5") }
            }

            HorizontalDivider(color = SurfaceDark)
            Text("Recording", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            DetailRow("HR Device", reading.hrDeviceId ?: "None recorded")

            HorizontalDivider(color = SurfaceDark)
            Text("Data Quality", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            DetailRow("Artifact % (Supine)", "${String.format("%.1f", reading.artifactPercentSupine)} %")
            DetailRow("Artifact % (Standing)", "${String.format("%.1f", reading.artifactPercentStanding)} %")
        }
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyHistoryContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("No readings yet", style = MaterialTheme.typography.headlineSmall, color = TextSecondary)
        Text(
            "Complete a Morning Readiness test to see your history here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDisabled,
            textAlign = TextAlign.Center
        )
    }
}
