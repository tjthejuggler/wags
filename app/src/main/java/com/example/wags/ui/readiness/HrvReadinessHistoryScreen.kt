package com.example.wags.ui.readiness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.DailyReadingEntity
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

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvReadinessHistoryScreen(
    onNavigateBack: () -> Unit,
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
                    HrvHistoryTab.GRAPHS -> HrvGraphsContent(uiState = uiState)
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
private fun HrvGraphsContent(uiState: HrvReadinessHistoryUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header summary
        HrvHistorySummaryCard(uiState = uiState)

        // ── 1. Readiness Score ─────────────────────────────────────────────
        HrvGraphSection(
            title = "Readiness Score",
            subtitle = "0–100 composite score over time"
        ) {
            HrvReadinessScoreChart(points = uiState.chartData.readinessScore)
        }

        // ── 2. RMSSD ──────────────────────────────────────────────────────
        HrvGraphSection(
            title = "RMSSD",
            subtitle = "Root mean square of successive differences (ms)"
        ) {
            HrvMetricChart(
                label = "RMSSD (ms)",
                points = uiState.chartData.rmssd,
                lineColor = TextPrimary
            )
        }

        // ── 3. ln(RMSSD) ──────────────────────────────────────────────────
        HrvGraphSection(
            title = "ln(RMSSD)",
            subtitle = "Natural log of RMSSD — used for baseline scoring"
        ) {
            HrvMetricChart(
                label = "ln(RMSSD)",
                points = uiState.chartData.lnRmssd,
                lineColor = Color(0xFFD0D0D0)
            )
        }

        // ── 4. SDNN ───────────────────────────────────────────────────────
        HrvGraphSection(
            title = "SDNN",
            subtitle = "Standard deviation of NN intervals (ms)"
        ) {
            HrvMetricChart(
                label = "SDNN (ms)",
                points = uiState.chartData.sdnn,
                lineColor = Color(0xFFB0B0B0)
            )
        }

        // ── 5. Resting HR ─────────────────────────────────────────────────
        HrvGraphSection(
            title = "Resting Heart Rate",
            subtitle = "Average HR during 2-minute session (bpm)"
        ) {
            HrvMetricChart(
                label = "Resting HR (bpm)",
                points = uiState.chartData.restingHr,
                lineColor = Color(0xFF909090),
                invertGood = true
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

    val scoreColor = TextPrimary

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
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold
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

// ── Summary card ───────────────────────────────────────────────────────────────

@Composable
private fun HrvHistorySummaryCard(uiState: HrvReadinessHistoryUiState) {
    val count = uiState.allReadings.size
    val latestScore = uiState.chartData.readinessScore.lastOrNull()?.value?.toInt()
    val avgScore = if (uiState.chartData.readinessScore.isNotEmpty())
        uiState.chartData.readinessScore.map { it.value }.average().toInt() else null
    val latestRmssd = uiState.chartData.rmssd.lastOrNull()?.value
    val avgRmssd = if (uiState.chartData.rmssd.isNotEmpty())
        uiState.chartData.rmssd.map { it.value }.average().toFloat() else null

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
                HrvSummaryChip(label = "Sessions", value = count.toString(), color = TextPrimary)
                latestScore?.let {
                    HrvSummaryChip(label = "Latest Score", value = it.toString(), color = TextPrimary)
                }
                avgScore?.let {
                    HrvSummaryChip(label = "Avg Score", value = it.toString(), color = TextSecondary)
                }
            }
            if (latestRmssd != null && avgRmssd != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    HrvSummaryChip(
                        label = "Latest RMSSD",
                        value = String.format("%.1f ms", latestRmssd),
                        color = TextPrimary
                    )
                    HrvSummaryChip(
                        label = "Avg RMSSD",
                        value = String.format("%.1f ms", avgRmssd),
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ── Graph section wrapper ──────────────────────────────────────────────────────

@Composable
private fun HrvGraphSection(
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

// ── Readiness score chart ──────────────────────────────────────────────────────

@Composable
private fun HrvReadinessScoreChart(points: List<HrvChartPoint>) {
    if (points.isEmpty()) { HrvNoDataLabel(); return }

    val latest = points.last()
    val avg    = points.map { it.value }.average().toFloat()
    val min    = points.minOf { it.value }
    val max    = points.maxOf { it.value }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HrvStatChip("Latest", latest.value.toInt().toString(), hrvScoreColor(latest.value))
            HrvStatChip("Avg",    avg.toInt().toString(),          hrvScoreColor(avg))
            HrvStatChip("Min",    min.toInt().toString(),          hrvScoreColor(min))
            HrvStatChip("Max",    max.toInt().toString(),          hrvScoreColor(max))
        }

        HrvLineChartCanvas(
            points = points.map { it.index to it.value },
            lineColor = TextPrimary,
            fillAlpha = 0.15f,
            yMin = 0f,
            yMax = 100f,
            referenceLines = listOf(80f to Color(0xFF606060), 60f to Color(0xFF404040)),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HrvZoneLegend("≥80",   Color(0xFF606060))
            HrvZoneLegend("60–79", Color(0xFF404040))
            HrvZoneLegend("<60",   Color(0xFF282828))
        }
    }
}

// ── Generic metric chart ───────────────────────────────────────────────────────

@Composable
private fun HrvMetricChart(
    label: String,
    points: List<HrvChartPoint>,
    lineColor: Color,
    invertGood: Boolean = false
) {
    if (points.isEmpty()) { HrvNoDataLabel(); return }

    val latest = points.last()
    val avg    = points.map { it.value }.average().toFloat()
    val min    = points.minOf { it.value }
    val max    = points.maxOf { it.value }
    val yPad   = ((max - min) * 0.1f).coerceAtLeast(0.1f)

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

        HrvLineChartCanvas(
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
private fun HrvLineChartCanvas(
    points: List<Pair<Float, Float>>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    referenceLines: List<Pair<Float, Color>> = emptyList()
) {
    if (points.size < 2) {
        Canvas(modifier = modifier) {
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }

    val yRange = (yMax - yMin).coerceAtLeast(0.001f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val xStep = w / (points.size - 1).toFloat()

        fun xOf(i: Int) = i * xStep
        fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

        // Reference lines
        referenceLines.forEach { (refY, refColor) ->
            val ry = yOf(refY)
            drawLine(
                color = refColor.copy(alpha = 0.35f),
                start = Offset(0f, ry),
                end = Offset(w, ry),
                strokeWidth = 1.5f
            )
        }

        // Fill
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

        // Line
        val linePath = Path().apply {
            moveTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].second))
            }
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // Latest dot
        val lastX = xOf(points.size - 1)
        val lastY = yOf(points.last().second)
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
        drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun HrvSummaryChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun HrvStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun HrvZoneLegend(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun HrvNoDataLabel() {
    Text(
        "No data yet",
        style = MaterialTheme.typography.bodySmall,
        color = TextDisabled,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

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
