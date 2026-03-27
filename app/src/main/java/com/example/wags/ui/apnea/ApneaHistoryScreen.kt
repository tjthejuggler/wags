package com.example.wags.ui.apnea

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

// ── Tab definitions ────────────────────────────────────────────────────────────

private enum class ApneaHistoryTab(val label: String) {
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
    var selectedTab by remember { mutableStateOf(ApneaHistoryTab.ALL_RECORDS) }
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
                ApneaHistoryTab.entries.forEach { tab ->
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

            when (selectedTab) {
                ApneaHistoryTab.ALL_RECORDS -> AllApneaRecordsScreen(navController = navController)
                ApneaHistoryTab.STATS -> StatsTabContent(
                    state = state,
                    onToggleShowAll = { viewModel.toggleShowAllStats() },
                    onRecordClick = { recordId ->
                        navController.navigate(WagsRoutes.apneaRecordDetail(recordId))
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

// ── Stats tab ──────────────────────────────────────────────────────────────────

@Composable
private fun StatsTabContent(
    state: ApneaHistoryUiState,
    onToggleShowAll: () -> Unit,
    onRecordClick: (Long) -> Unit,
    onSetLungVolume: (String) -> Unit,
    onSetPrepType: (PrepType) -> Unit,
    onSetTimeOfDay: (TimeOfDay) -> Unit,
    onSetPosture: (Posture) -> Unit,
    onSetAudio: (AudioSetting) -> Unit
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
                        "${state.lungVolume.displayLungVolume()}  ·  ${state.prepType.displayName()}  ·  " +
                        "${state.timeOfDay.displayName()}  ·  ${state.posture.displayName()}  ·  ${state.audio.displayName()}",
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

        ApneaStatsContent(stats = stats, onRecordClick = onRecordClick)
    }
}

// ── Settings popup dialog (for Stats tab) ─────────────────────────────────────

@Composable
private fun StatsSettingsDialog(
    lungVolume: String,
    prepType: PrepType,
    timeOfDay: TimeOfDay,
    posture: Posture,
    audio: AudioSetting,
    onSetLungVolume: (String) -> Unit,
    onSetPrepType: (PrepType) -> Unit,
    onSetTimeOfDay: (TimeOfDay) -> Unit,
    onSetPosture: (Posture) -> Unit,
    onSetAudio: (AudioSetting) -> Unit,
    onDismiss: () -> Unit
) {
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
                    listOf("FULL", "PARTIAL", "EMPTY").forEach { lv ->
                        FilterChip(
                            selected = lungVolume == lv,
                            onClick = { onSetLungVolume(lv) },
                            label = { Text(lv.displayLungVolume(), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }
                // Prep Type
                SettingRow(label = "Prep") {
                    PrepType.entries.forEach { pt ->
                        FilterChip(
                            selected = prepType == pt,
                            onClick = { onSetPrepType(pt) },
                            label = { Text(pt.displayName(), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }
                // Time of Day
                SettingRow(label = "Time of Day") {
                    TimeOfDay.entries.forEach { tod ->
                        FilterChip(
                            selected = timeOfDay == tod,
                            onClick = { onSetTimeOfDay(tod) },
                            label = { Text(tod.displayName(), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }
                // Posture
                SettingRow(label = "Posture") {
                    Posture.entries.forEach { pos ->
                        FilterChip(
                            selected = posture == pos,
                            onClick = { onSetPosture(pos) },
                            label = { Text(pos.displayName(), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }
                // Audio
                SettingRow(label = "Audio") {
                    AudioSetting.entries.forEach { aud ->
                        FilterChip(
                            selected = audio == aud,
                            onClick = { onSetAudio(aud) },
                            label = { Text(aud.displayName(), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
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
private fun SettingRow(label: String, chips: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
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
    onRecordClick: (Long) -> Unit
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
private fun HistoryStatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatHistoryDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
