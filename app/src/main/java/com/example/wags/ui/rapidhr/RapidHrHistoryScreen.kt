package com.example.wags.ui.rapidhr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.wags.data.db.entity.RapidHrSessionEntity
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "%d:%02d.%d".format(min, sec, tenths)
    else "%d.%d s".format(sec, tenths)
}

private val sessionDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val sessionTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RapidHrHistoryScreen(
    navController: NavController,
    viewModel: RapidHrHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Rapid HR History", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
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
            // Tab row
            TabRow(
                selectedTabIndex = state.selectedTab.ordinal,
                containerColor = SurfaceDark,
                contentColor = TextPrimary
            ) {
                RapidHrHistoryTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                when (tab) {
                                    RapidHrHistoryTab.GRAPHS -> "Graphs"
                                    RapidHrHistoryTab.CALENDAR -> "Calendar"
                                }
                            )
                        }
                    )
                }
            }

            when (state.selectedTab) {
                RapidHrHistoryTab.GRAPHS -> GraphsTab(
                    state = state,
                    onFilterDirection = viewModel::setDirectionFilter,
                    onFilterHrCombo = viewModel::setHrComboFilter,
                    onSessionClick = { session ->
                        navController.navigate(WagsRoutes.rapidHrDetail(session.id))
                    }
                )
                RapidHrHistoryTab.CALENDAR -> CalendarTab(
                    state = state,
                    onSelectDate = viewModel::selectDate,
                    onSessionClick = { session ->
                        navController.navigate(WagsRoutes.rapidHrDetail(session.id))
                    }
                )
            }
        }
    }
}

// ── Graphs tab ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphsTab(
    state: RapidHrHistoryUiState,
    onFilterDirection: (RapidHrDirection?) -> Unit,
    onFilterHrCombo: (HrCombo?) -> Unit,
    onSessionClick: (RapidHrSessionEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Direction filter chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.directionFilter == null,
                    onClick = { onFilterDirection(null) },
                    label = { Text("All") }
                )
                RapidHrDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = state.directionFilter == dir,
                        onClick = { onFilterDirection(dir) },
                        label = { Text(dir.label) }
                    )
                }
            }
        }

        // HR combo dropdown (only shown when there are multiple combos)
        if (state.availableCombos.size > 1) {
            item {
                HrComboDropdown(
                    selected = state.hrComboFilter,
                    options = state.availableCombos,
                    onSelect = onFilterHrCombo
                )
            }
        }

        // Transition time chart
        if (state.chartPoints.size >= 2) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Transition Time",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(8.dp))
                        TransitionTimeChart(
                            points = state.chartPoints,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
            }
        }

        // Summary stats
        if (state.filteredSessions.isNotEmpty()) {
            item {
                val best = state.filteredSessions.minOf { it.transitionDurationMs }
                val avg = state.filteredSessions.map { it.transitionDurationMs }.average()
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SummaryCell("Sessions", "${state.filteredSessions.size}")
                        SummaryCell("Best", formatMs(best))
                        SummaryCell("Avg", formatMs(avg.toLong()))
                    }
                }
            }
        }

        // Session list
        if (state.filteredSessions.isEmpty()) {
            item {
                Text(
                    "No sessions yet",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = TextSecondary
                )
            }
        } else {
            items(state.filteredSessions) { session ->
                SessionRow(session = session, onClick = { onSessionClick(session) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HrComboDropdown(
    selected: HrCombo?,
    options: List<HrCombo>,
    onSelect: (HrCombo?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.label ?: "All HR combos",
            onValueChange = {},
            readOnly = true,
            label = { Text("HR Range", style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All HR combos") },
                onClick = { onSelect(null); expanded = false }
            )
            options.forEach { combo ->
                DropdownMenuItem(
                    text = { Text(combo.label) },
                    onClick = { onSelect(combo); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun TransitionTimeChart(
    points: List<RapidHrChartPoint>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return
    val values = points.map { it.valueMs.toFloat() }
    val yMin = values.min()
    val yMax = values.max()
    val yPad = ((yMax - yMin) * 0.15f).coerceAtLeast(100f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val yRange = (yMax + yPad) - (yMin - yPad)
        val xStep = if (points.size > 1) w / (points.size - 1) else w

        fun xOf(i: Int) = i * xStep
        fun yOf(v: Float) = h - ((v - (yMin - yPad)) / yRange * h)

        // Fill path
        val fillPath = Path().apply {
            moveTo(xOf(0), h)
            points.forEachIndexed { i, p ->
                lineTo(xOf(i), yOf(p.valueMs.toFloat()))
            }
            lineTo(xOf(points.size - 1), h)
            close()
        }
        drawPath(fillPath, color = Color(0xFFD0D0D0).copy(alpha = 0.08f))

        // Line
        val linePath = Path().apply {
            points.forEachIndexed { i, p ->
                val x = xOf(i)
                val y = yOf(p.valueMs.toFloat())
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(linePath, color = Color(0xFFD0D0D0), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // Dots
        points.forEachIndexed { i, p ->
            drawCircle(Color(0xFFD0D0D0), radius = 3.dp.toPx(), center = Offset(xOf(i), yOf(p.valueMs.toFloat())))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}

@Composable
private fun SessionRow(
    session: RapidHrSessionEntity,
    onClick: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(session.timestamp).atZone(zone)
    val direction = runCatching { RapidHrDirection.valueOf(session.direction) }
        .getOrDefault(RapidHrDirection.HIGH_TO_LOW)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        direction.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (session.isPersonalBest) {
                        Text("PB", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCCCCCC))
                    }
                }
                Text(
                    "${session.highThreshold}→${session.lowThreshold} bpm  •  ${zdt.format(sessionDateFmt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Text(
                formatMs(session.transitionDurationMs),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

// ── Calendar tab ───────────────────────────────────────────────────────────────

@Composable
private fun CalendarTab(
    state: RapidHrHistoryUiState,
    onSelectDate: (LocalDate?) -> Unit,
    onSessionClick: (RapidHrSessionEntity) -> Unit
) {
    var displayMonth by remember { mutableStateOf(YearMonth.now()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            RapidHrCalendar(
                month = displayMonth,
                datesWithSessions = state.datesWithSessions,
                selectedDate = state.selectedDate,
                onSelectDate = onSelectDate,
                onPrevMonth = { displayMonth = displayMonth.minusMonths(1) },
                onNextMonth = { displayMonth = displayMonth.plusMonths(1) }
            )
        }

        if (state.selectedDate != null && state.selectedDaySessions.isNotEmpty()) {
            item {
                Text(
                    state.selectedDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
            }
            items(state.selectedDaySessions) { session ->
                SessionRow(session = session, onClick = { onSessionClick(session) })
            }
        } else if (state.selectedDate != null) {
            item {
                Text(
                    "No sessions on this day",
                    color = TextSecondary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun RapidHrCalendar(
    month: YearMonth,
    datesWithSessions: Set<LocalDate>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate?) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
    val firstDay = month.atDay(1)
    val daysInMonth = month.lengthOfMonth()
    val startDow = (firstDay.dayOfWeek.value % 7) // 0=Sun

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Month navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrevMonth) { Text("‹", color = TextSecondary) }
                Text(month.format(monthFmt), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                TextButton(onClick = onNextMonth) { Text("›", color = TextSecondary) }
            }

            // Day-of-week headers
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
                    Text(
                        d,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // Day grid
            val totalCells = startDow + daysInMonth
            val rows = (totalCells + 6) / 7
            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val cellIndex = row * 7 + col
                        val dayNum = cellIndex - startDow + 1
                        if (dayNum < 1 || dayNum > daysInMonth) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val date = month.atDay(dayNum)
                            val hasSession = date in datesWithSessions
                            val isSelected = date == selectedDate
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isSelected -> Color(0xFF555555)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        onSelectDate(if (isSelected) null else date)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "$dayNum",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) TextPrimary else if (hasSession) Color(0xFFCCCCCC) else TextSecondary
                                    )
                                    if (hasSession) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFAAAAAA))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
