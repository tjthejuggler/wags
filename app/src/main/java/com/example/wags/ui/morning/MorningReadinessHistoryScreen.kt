package com.example.wags.ui.morning

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
import androidx.compose.ui.graphics.Color
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
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReadinessHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: MorningReadinessHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Readiness History") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.allReadings.isEmpty()) {
                EmptyHistoryContent()
            } else {
                ReadinessCalendar(
                    displayedMonth = displayedMonth,
                    datesWithReadings = uiState.datesWithReadings,
                    selectedDate = uiState.selectedDate,
                    onDayClick = { date ->
                        if (date in uiState.datesWithReadings) {
                            if (uiState.selectedDate == date) {
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectDate(date)
                            }
                        }
                    },
                    onPreviousMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                    onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) }
                )

                uiState.selectedReading?.let { reading ->
                    ReadingDetailCard(
                        reading = reading,
                        onDismiss = { viewModel.clearSelection() }
                    )
                }
            }
        }
    }
}

// ── Calendar ──────────────────────────────────────────────────────────────────

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
            // Month navigation header
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

            // Calendar grid
            val firstDayOfMonth = displayedMonth.atDay(1)
            val startOffset = firstDayOfMonth.dayOfWeek.value % 7 // Sunday = 0
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

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(EcgCyan)
                )
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
        // Dot indicator for days with readings
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

// ── Detail card ───────────────────────────────────────────────────────────────

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
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(dateLabel, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(timeLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = TextDisabled)
                }
            }

            // Score badge
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

            // Supine HRV section
            Text("Supine HRV", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
            DetailRow("RMSSD", "${String.format("%.1f", reading.supineRmssdMs)} ms")
            DetailRow("HRV Score (ln×20)", (reading.supineLnRmssd * 20).toInt().toString())
            DetailRow("SDNN", "${String.format("%.1f", reading.supineSdnnMs)} ms")
            DetailRow("Resting HR", "${reading.supineRhr} bpm")

            HorizontalDivider(color = SurfaceDark)

            // Standing HRV section
            Text("Standing HRV", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
            DetailRow("RMSSD", "${String.format("%.1f", reading.standingRmssdMs)} ms")
            DetailRow("HRV Score (ln×20)", (reading.standingLnRmssd * 20).toInt().toString())
            DetailRow("SDNN", "${String.format("%.1f", reading.standingSdnnMs)} ms")
            DetailRow("Peak Stand HR", "${reading.peakStandHr} bpm")

            HorizontalDivider(color = SurfaceDark)

            // Orthostatic section
            Text("Orthostatic Response", style = MaterialTheme.typography.labelLarge, color = EcgCyan)
            reading.thirtyFifteenRatio?.let { DetailRow("30:15 Ratio", String.format("%.3f", it)) }
            reading.ohrrAt20sPercent?.let { DetailRow("OHRR at 20s", "${String.format("%.1f", it)} %") }
            reading.ohrrAt60sPercent?.let { DetailRow("OHRR at 60s", "${String.format("%.1f", it)} %") }

            // Respiratory
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

            // Hooper Index
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

            // Data quality
            Text("Data Quality", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            DetailRow("Artifact % (Supine)", "${String.format("%.1f", reading.artifactPercentSupine)} %")
            DetailRow("Artifact % (Standing)", "${String.format("%.1f", reading.artifactPercentStanding)} %")
        }
    }
}

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
