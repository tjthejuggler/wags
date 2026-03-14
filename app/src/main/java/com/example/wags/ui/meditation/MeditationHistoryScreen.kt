package com.example.wags.ui.meditation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class MeditationHistoryTab(val label: String) {
    GRAPHS("Graphs"),
    CALENDAR("Calendar")
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationHistoryScreen(
    navController: NavController,
    viewModel: MeditationHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(MeditationHistoryTab.GRAPHS) }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    // Single-session auto-navigate
    val selectedDaySessions = uiState.selectedDaySessions
    LaunchedEffect(selectedDaySessions) {
        if (selectedDaySessions.size == 1) {
            navController.navigate(WagsRoutes.meditationSessionDetail(selectedDaySessions.first().sessionId))
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
                            tint = EcgCyan
                        )
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
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = SurfaceDark,
                contentColor = EcgCyan
            ) {
                MeditationHistoryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                tab.label,
                                color = if (selectedTab == tab) EcgCyan else TextDisabled
                            )
                        }
                    )
                }
            }

            if (uiState.allSessions.isEmpty()) {
                EmptyHistoryContent()
            } else {
                when (selectedTab) {
                    MeditationHistoryTab.GRAPHS -> GraphsContent(uiState = uiState)
                    MeditationHistoryTab.CALENDAR -> CalendarContent(
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
                            viewModel.clearSelection()
                            navController.navigate(WagsRoutes.meditationSessionDetail(id))
                        }
                    )
                }
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@Composable
private fun GraphsContent(uiState: MeditationHistoryUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SummaryCard(uiState = uiState)

        // Duration
        GraphSection(title = "Session Duration", subtitle = "Minutes per session") {
            MeditationLineChart(
                points = uiState.chartData.durationMin,
                lineColor = EcgCyan,
                label = "Duration (min)"
            )
        }

        // Avg HR
        if (uiState.chartData.avgHr.isNotEmpty()) {
            GraphSection(title = "Average Heart Rate", subtitle = "BPM over session") {
                MeditationLineChart(
                    points = uiState.chartData.avgHr,
                    lineColor = ReadinessOrange,
                    label = "Avg HR (bpm)"
                )
            }
        }

        // Start vs End RMSSD
        if (uiState.chartData.startRmssd.isNotEmpty()) {
            GraphSection(
                title = "RMSSD",
                subtitle = "Start (cyan) vs End (green) — higher is better"
            ) {
                DualLineChart(
                    pointsA = uiState.chartData.startRmssd,
                    pointsB = uiState.chartData.endRmssd,
                    colorA = EcgCyan,
                    colorB = ReadinessGreen,
                    labelA = "Start RMSSD",
                    labelB = "End RMSSD"
                )
            }
        }

        // ln(RMSSD) slope
        if (uiState.chartData.lnRmssdSlope.isNotEmpty()) {
            GraphSection(
                title = "ln(RMSSD) Slope",
                subtitle = "Positive = HRV improving during session"
            ) {
                MeditationLineChart(
                    points = uiState.chartData.lnRmssdSlope,
                    lineColor = ReadinessBlue,
                    label = "ln(RMSSD) slope",
                    showZeroLine = true
                )
            }
        }

        Spacer(Modifier.height(16.dp))
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
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(EcgCyan))
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
        isSelected -> EcgCyan.copy(alpha = 0.25f)
        isToday    -> SurfaceDark
        else       -> Color.Transparent
    }
    val textColor = when {
        isSelected -> EcgCyan
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
                    .background(if (isSelected) EcgCyan else EcgCyanDim)
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
                    color = EcgCyan
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
                SummaryChip("Sessions", count.toString(), EcgCyan)
                SummaryChip("Total Min", totalMinutes.toString(), ReadinessGreen)
                avgDuration?.let {
                    SummaryChip("Avg Min", String.format("%.1f", it), EcgCyanDim)
                }
                latestAvgHr?.let {
                    SummaryChip("Last HR", "${String.format("%.0f", it)} bpm", ReadinessOrange)
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
    showZeroLine: Boolean = false
) {
    if (points.isEmpty()) { NoDataLabel(); return }

    val values = points.map { it.value }
    val min = values.min()
    val max = values.max()
    val latest = values.last()
    val avg = values.average().toFloat()
    val yPad = ((max - min) * 0.1f).coerceAtLeast(0.1f)

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
            points = points.map { it.index to it.value },
            lineColor = lineColor,
            fillAlpha = 0.12f,
            yMin = min - yPad,
            yMax = max + yPad,
            zeroLine = if (showZeroLine) 0f else null,
            modifier = Modifier.fillMaxWidth().height(90.dp)
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

// ── Dual-series line chart ─────────────────────────────────────────────────────

@Composable
private fun DualLineChart(
    pointsA: List<MeditationChartPoint>,
    pointsB: List<MeditationChartPoint>,
    colorA: Color,
    colorB: Color,
    labelA: String,
    labelB: String
) {
    if (pointsA.isEmpty()) { NoDataLabel(); return }

    val allValues = (pointsA + pointsB).map { it.value }
    val min = allValues.min()
    val max = allValues.max()
    val yPad = ((max - min) * 0.1f).coerceAtLeast(0.1f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(labelA, style = MaterialTheme.typography.labelSmall, color = colorA)
            Text(labelB, style = MaterialTheme.typography.labelSmall, color = colorB)
        }

        // Draw both series on the same canvas
        Box(modifier = Modifier.fillMaxWidth().height(90.dp)) {
            LineChartCanvas(
                points = pointsA.map { it.index to it.value },
                lineColor = colorA,
                fillAlpha = 0.08f,
                yMin = min - yPad,
                yMax = max + yPad,
                modifier = Modifier.fillMaxSize()
            )
            LineChartCanvas(
                points = pointsB.map { it.index to it.value },
                lineColor = colorB,
                fillAlpha = 0.08f,
                yMin = min - yPad,
                yMax = max + yPad,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── Pure Canvas line chart ─────────────────────────────────────────────────────

@Composable
private fun LineChartCanvas(
    points: List<Pair<Float, Float>>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    zeroLine: Float? = null
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

        // Zero reference line
        zeroLine?.let { z ->
            val zy = yOf(z)
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, zy),
                end = Offset(w, zy),
                strokeWidth = 1.5f
            )
        }

        // Fill
        val fillPath = Path().apply {
            moveTo(xOf(0), h)
            lineTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].second))
            lineTo(xOf(points.size - 1), h)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = fillAlpha))

        // Line
        val linePath = Path().apply {
            moveTo(xOf(0), yOf(points[0].second))
            for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].second))
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // Latest dot
        val lastX = xOf(points.size - 1)
        val lastY = yOf(points.last().second)
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
        drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))
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
