package com.example.wags.ui.morning

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
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    viewModel: MorningReadinessHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(HistoryTab.GRAPHS) }
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
                contentColor = EcgCyan
            ) {
                HistoryTab.entries.forEach { tab ->
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

            if (uiState.allReadings.isEmpty()) {
                EmptyHistoryContent()
            } else {
                when (selectedTab) {
                    HistoryTab.GRAPHS -> GraphsContent(
                        chartData = uiState.chartData,
                        readingCount = uiState.allReadings.size
                    )
                    HistoryTab.CALENDAR -> CalendarContent(
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
                        onDismissDetail = { viewModel.clearSelection() }
                    )
                }
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@Composable
private fun GraphsContent(
    chartData: MorningReadinessChartData,
    readingCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "$readingCount readings recorded",
            style = MaterialTheme.typography.labelMedium,
            color = TextDisabled
        )

        // ── 1. Overall Readiness Score ─────────────────────────────────────
        GraphSection(title = "Overall Readiness Score", subtitle = "0–100 composite score") {
            ReadinessScoreLineChart(points = chartData.readinessScore)
        }

        // ── 2. Supine HRV ─────────────────────────────────────────────────
        GraphSection(title = "Supine HRV", subtitle = "Measured lying down") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricLineChart(
                    label = "RMSSD (ms)",
                    points = chartData.supineRmssd,
                    lineColor = EcgCyan
                )
                MetricLineChart(
                    label = "HRV Score (ln×20)",
                    points = chartData.supineHrvScore,
                    lineColor = ReadinessGreen
                )
                MetricLineChart(
                    label = "SDNN (ms)",
                    points = chartData.supineSdnn,
                    lineColor = ReadinessBlue
                )
                MetricLineChart(
                    label = "Resting HR (bpm)",
                    points = chartData.restingHr,
                    lineColor = ReadinessOrange,
                    invertGood = true
                )
            }
        }

        // ── 3. Standing HRV ───────────────────────────────────────────────
        GraphSection(title = "Standing HRV", subtitle = "Measured after standing") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricLineChart(
                    label = "RMSSD (ms)",
                    points = chartData.standingRmssd,
                    lineColor = EcgCyan
                )
                MetricLineChart(
                    label = "HRV Score (ln×20)",
                    points = chartData.standingHrvScore,
                    lineColor = ReadinessGreen
                )
                MetricLineChart(
                    label = "SDNN (ms)",
                    points = chartData.standingSdnn,
                    lineColor = ReadinessBlue
                )
                MetricLineChart(
                    label = "Peak Stand HR (bpm)",
                    points = chartData.peakStandHr,
                    lineColor = ReadinessRed,
                    invertGood = true
                )
            }
        }

        // ── 4. Orthostatic Response ───────────────────────────────────────
        if (chartData.thirtyFifteenRatio.isNotEmpty() || chartData.ohrr60s.isNotEmpty()) {
            GraphSection(title = "Orthostatic Response", subtitle = "Autonomic stand-up reflex") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chartData.thirtyFifteenRatio.isNotEmpty()) {
                        MetricLineChart(
                            label = "30:15 Ratio",
                            points = chartData.thirtyFifteenRatio,
                            lineColor = EcgCyan
                        )
                    }
                    if (chartData.ohrr60s.isNotEmpty()) {
                        MetricLineChart(
                            label = "OHRR at 60 s (%)",
                            points = chartData.ohrr60s,
                            lineColor = ReadinessGreen
                        )
                    }
                }
            }
        }

        // ── 5. Respiratory Rate ───────────────────────────────────────────
        if (chartData.respiratoryRate.isNotEmpty()) {
            GraphSection(title = "Respiratory Rate", subtitle = "Breaths per minute (supine)") {
                MetricLineChart(
                    label = "Resp. Rate (brpm)",
                    points = chartData.respiratoryRate,
                    lineColor = ReadinessBlue
                )
            }
        }

        // ── 6. Hooper Wellness Index ──────────────────────────────────────
        if (chartData.hooperTotal.isNotEmpty()) {
            GraphSection(title = "Hooper Wellness Index", subtitle = "Lower = better (0–20 scale)") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricLineChart(
                        label = "Total Score (/20)",
                        points = chartData.hooperTotal,
                        lineColor = EcgCyan,
                        invertGood = true
                    )
                    if (chartData.hooperSleep.isNotEmpty()) {
                        MetricLineChart(
                            label = "Sleep Quality (/5)",
                            points = chartData.hooperSleep,
                            lineColor = ReadinessBlue,
                            invertGood = true
                        )
                    }
                    if (chartData.hooperFatigue.isNotEmpty()) {
                        MetricLineChart(
                            label = "Fatigue (/5)",
                            points = chartData.hooperFatigue,
                            lineColor = ReadinessOrange,
                            invertGood = true
                        )
                    }
                    if (chartData.hooperSoreness.isNotEmpty()) {
                        MetricLineChart(
                            label = "Muscle Soreness (/5)",
                            points = chartData.hooperSoreness,
                            lineColor = ReadinessRed,
                            invertGood = true
                        )
                    }
                    if (chartData.hooperStress.isNotEmpty()) {
                        MetricLineChart(
                            label = "Stress (/5)",
                            points = chartData.hooperStress,
                            lineColor = CoherencePink,
                            invertGood = true
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
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
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
            HorizontalDivider(color = SurfaceDark)
            content()
        }
    }
}

// ── Readiness score chart (colour-coded zones) ─────────────────────────────────

@Composable
private fun ReadinessScoreLineChart(points: List<ChartPoint>) {
    if (points.isEmpty()) {
        NoDataLabel()
        return
    }

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Summary row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatChip("Latest", latest.value.toInt().toString(), scoreColor(latest.value))
            StatChip("Avg", avg.toInt().toString(), scoreColor(avg))
            StatChip("Min", min.toInt().toString(), scoreColor(min))
            StatChip("Max", max.toInt().toString(), scoreColor(max))
        }

        // Chart
        LineChartCanvas(
            points = points,
            lineColor = EcgCyan,
            fillAlpha = 0.15f,
            yMin = 0f,
            yMax = 100f,
            referenceLines = listOf(80f to ReadinessGreen, 60f to ReadinessOrange),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )

        // Zone legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ZoneLegendItem("≥80 Green", ReadinessGreen)
            ZoneLegendItem("60–79 Yellow", ReadinessOrange)
            ZoneLegendItem("<60 Red", ReadinessRed)
        }
    }
}

// ── Generic metric line chart ──────────────────────────────────────────────────

@Composable
private fun MetricLineChart(
    label: String,
    points: List<ChartPoint>,
    lineColor: Color,
    invertGood: Boolean = false
) {
    if (points.isEmpty()) return

    val latest = points.last()
    val avg = points.map { it.value }.average().toFloat()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val yPad = ((max - min) * 0.1f).coerceAtLeast(1f)

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

        LineChartCanvas(
            points = points,
            lineColor = lineColor,
            fillAlpha = 0.10f,
            yMin = (min - yPad),
            yMax = (max + yPad),
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Avg ${String.format("%.1f", avg)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
            Text(
                "Min ${String.format("%.1f", min)}  Max ${String.format("%.1f", max)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
        }
    }
}

// ── Pure Canvas line chart ─────────────────────────────────────────────────────

@Composable
private fun LineChartCanvas(
    points: List<ChartPoint>,
    lineColor: Color,
    fillAlpha: Float,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    referenceLines: List<Pair<Float, Color>> = emptyList()
) {
    if (points.size < 2) {
        // Single point — just draw a dot
        Canvas(modifier = modifier) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(color = lineColor, radius = 6f, center = Offset(cx, cy))
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

        // Reference lines (e.g. zone boundaries)
        referenceLines.forEach { (refY, refColor) ->
            val ry = yOf(refY)
            drawLine(
                color = refColor.copy(alpha = 0.35f),
                start = Offset(0f, ry),
                end = Offset(w, ry),
                strokeWidth = 1.5f
            )
        }

        // Fill path
        val fillPath = Path().apply {
            moveTo(xOf(0), h)
            lineTo(xOf(0), yOf(points[0].value))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].value))
            }
            lineTo(xOf(points.size - 1), h)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = fillAlpha))

        // Line path
        val linePath = Path().apply {
            moveTo(xOf(0), yOf(points[0].value))
            for (i in 1 until points.size) {
                lineTo(xOf(i), yOf(points[i].value))
            }
        }
        drawPath(
            linePath,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )

        // Dot for latest point
        val lastX = xOf(points.size - 1)
        val lastY = yOf(points.last().value)
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
        drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))
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
            selectedDate = uiState.selectedDate,
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        uiState.selectedReading?.let { reading ->
            ReadingDetailCard(reading = reading, onDismiss = onDismissDetail)
        }
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
                Text(
                    "Reading recorded — tap to view",
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

            HorizontalDivider(color = SurfaceDark)
            Text("Standing HRV", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
            DetailRow("RMSSD", "${String.format("%.1f", reading.standingRmssdMs)} ms")
            DetailRow("HRV Score (ln×20)", (reading.standingLnRmssd * 20).toInt().toString())
            DetailRow("SDNN", "${String.format("%.1f", reading.standingSdnnMs)} ms")
            DetailRow("Peak Stand HR", "${reading.peakStandHr} bpm")

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
                    Text(
                        "⚠ Slow breathing detected — score adjusted",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessOrange
                    )
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
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

@Composable
private fun ZoneLegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
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

private fun scoreColor(score: Float) = when {
    score >= 80f -> ReadinessGreen
    score >= 60f -> ReadinessOrange
    else         -> ReadinessRed
}
