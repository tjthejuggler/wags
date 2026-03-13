package com.example.wags.ui.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.repository.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** A single (x=index, y=value) point for a line chart, with a date label. */
data class HrvChartPoint(val index: Float, val value: Float, val dateLabel: String)

data class HrvHistoryChartData(
    /** Readiness score (0–100) over time. */
    val readinessScore: List<HrvChartPoint> = emptyList(),
    /** RMSSD (ms) over time. */
    val rmssd: List<HrvChartPoint> = emptyList(),
    /** ln(RMSSD) over time. */
    val lnRmssd: List<HrvChartPoint> = emptyList(),
    /** SDNN (ms) over time. */
    val sdnn: List<HrvChartPoint> = emptyList(),
    /** Resting HR (bpm) over time. */
    val restingHr: List<HrvChartPoint> = emptyList(),
)

data class HrvReadinessHistoryUiState(
    val allReadings: List<DailyReadingEntity> = emptyList(),
    val chartData: HrvHistoryChartData = HrvHistoryChartData(),
    /** Set of dates that have at least one reading — used to show calendar dots. */
    val datesWithReadings: Set<LocalDate> = emptySet(),
    /** Map from date → all readings on that date (sorted newest first). */
    val readingsByDate: Map<LocalDate, List<DailyReadingEntity>> = emptyMap(),
    /** Currently selected calendar date (null = nothing selected). */
    val selectedDate: LocalDate? = null,
    /**
     * Readings for the selected date.
     * - Empty  → nothing selected
     * - Size 1 → history screen should navigate directly to detail
     * - Size >1 → history screen should show the multi-session picker list
     */
    val selectedDayReadings: List<DailyReadingEntity> = emptyList(),
)

@HiltViewModel
class HrvReadinessHistoryViewModel @Inject constructor(
    repository: ReadinessRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<HrvReadinessHistoryUiState> = combine(
        repository.observeAll(),
        _selectedDate
    ) { readings, selectedDate ->
        val zone = ZoneId.systemDefault()

        // Build date → readings map (newest first within each day)
        val byDate: Map<LocalDate, List<DailyReadingEntity>> = readings
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        val dates = byDate.keys

        val selectedDayReadings = if (selectedDate != null) {
            byDate[selectedDate] ?: emptyList()
        } else emptyList()

        // Chronological order (oldest → newest) for charts
        val chronological = readings.reversed()

        HrvReadinessHistoryUiState(
            allReadings = readings,
            chartData = buildChartData(chronological, zone),
            datesWithReadings = dates,
            readingsByDate = byDate,
            selectedDate = selectedDate,
            selectedDayReadings = selectedDayReadings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HrvReadinessHistoryUiState()
    )

    /** Called when user taps a day on the calendar. */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /** Called when user dismisses the multi-session list or navigates away. */
    fun clearSelection() {
        _selectedDate.value = null
    }

    // ── Chart builder ──────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<DailyReadingEntity>,
        zone: ZoneId
    ): HrvHistoryChartData {
        if (chronological.isEmpty()) return HrvHistoryChartData()

        val readinessScore = mutableListOf<HrvChartPoint>()
        val rmssd          = mutableListOf<HrvChartPoint>()
        val lnRmssd        = mutableListOf<HrvChartPoint>()
        val sdnn           = mutableListOf<HrvChartPoint>()
        val restingHr      = mutableListOf<HrvChartPoint>()

        chronological.forEachIndexed { idx, e ->
            val x = idx.toFloat()
            val label = Instant.ofEpochMilli(e.timestamp)
                .atZone(zone).toLocalDate().toString()

            readinessScore.add(HrvChartPoint(x, e.readinessScore.toFloat(), label))
            rmssd.add(HrvChartPoint(x, e.rawRmssdMs, label))
            lnRmssd.add(HrvChartPoint(x, e.lnRmssd, label))
            sdnn.add(HrvChartPoint(x, e.sdnnMs, label))
            restingHr.add(HrvChartPoint(x, e.restingHrBpm, label))
        }

        return HrvHistoryChartData(
            readinessScore = readinessScore,
            rmssd          = rmssd,
            lnRmssd        = lnRmssd,
            sdnn           = sdnn,
            restingHr      = restingHr,
        )
    }
}
