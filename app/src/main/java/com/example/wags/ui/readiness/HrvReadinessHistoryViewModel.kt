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
import java.time.ZoneId
import javax.inject.Inject

/** A single (x=index, y=value) point for a line chart, with a date label. */
data class HrvChartPoint(val index: Float, val value: Float, val dateLabel: String)

/** Time period filter for the graphs tab. */
enum class HrvChartTimePeriod(val label: String, val days: Int?) {
    WEEK("7d", 7),
    MONTH("1m", 30),
    THREE_MONTHS("3m", 90),
    YEAR("1y", 365),
    ALL("All", null)
}

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
    /** Currently selected time period — controls zoom level (how many points visible on screen). */
    val timePeriod: HrvChartTimePeriod = HrvChartTimePeriod.ALL,
)

@HiltViewModel
class HrvReadinessHistoryViewModel @Inject constructor(
    repository: ReadinessRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val _timePeriod = MutableStateFlow(HrvChartTimePeriod.ALL)

    val uiState: StateFlow<HrvReadinessHistoryUiState> = combine(
        repository.observeAll(),
        _selectedDate,
        _timePeriod
    ) { readings, selectedDate, timePeriod ->
        val zone = ZoneId.systemDefault()

        // Build date → readings map (newest first within each day)
        val byDate: Map<LocalDate, List<DailyReadingEntity>> = readings
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        val dates = byDate.keys

        val selectedDayReadings = if (selectedDate != null) {
            byDate[selectedDate] ?: emptyList()
        } else emptyList()

        // Build chart data from ALL data (time period is now just a zoom control)
        val chronological = readings.reversed()
        val chartData = buildChartData(chronological, zone, timePeriod)

        HrvReadinessHistoryUiState(
            allReadings = readings,
            chartData = chartData,
            datesWithReadings = dates,
            readingsByDate = byDate,
            selectedDate = selectedDate,
            selectedDayReadings = selectedDayReadings,
            timePeriod = timePeriod,
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

    /** Called when user selects a time period filter. */
    fun setTimePeriod(period: HrvChartTimePeriod) {
        _timePeriod.value = period
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private data class FilterResult(
        val readings: List<DailyReadingEntity>,
        val canStepBack: Boolean,
        val canStepForward: Boolean
    )

    private fun filterByPeriod(
        chronological: List<DailyReadingEntity>,
        period: HrvChartTimePeriod,
        offset: Int,
        zone: ZoneId
    ): FilterResult {
        val days = period.days
        if (days == null) {
            // ALL — no filtering, no stepping
            return FilterResult(chronological, canStepBack = false, canStepForward = false)
        }

        val today = LocalDate.now(zone)
        // The end of the window: if offset=0, end = today; offset=-1, end = today - days; etc.
        val windowEnd = today.minusDays((-offset).toLong() * days)
        val windowStart = windowEnd.minusDays(days.toLong())

        val filtered = chronological.filter { entity ->
            val date = Instant.ofEpochMilli(entity.timestamp).atZone(zone).toLocalDate()
            date > windowStart && date <= windowEnd
        }

        // Can step back if there's any data before windowStart
        val canBack = chronological.any { entity ->
            Instant.ofEpochMilli(entity.timestamp).atZone(zone).toLocalDate() <= windowStart
        }

        // Can step forward if offset < 0
        val canFwd = offset < 0

        return FilterResult(filtered, canStepBack = canBack, canStepForward = canFwd)
    }

    // ── Chart builder ──────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<DailyReadingEntity>,
        zone: ZoneId,
        timePeriod: HrvChartTimePeriod
    ): HrvHistoryChartData {
        if (chronological.isEmpty()) return HrvHistoryChartData()

        // For YEAR and ALL time periods, aggregate by month
        val shouldAggregateByMonth = timePeriod == HrvChartTimePeriod.YEAR || timePeriod == HrvChartTimePeriod.ALL

        val readinessScore = mutableListOf<HrvChartPoint>()
        val rmssd          = mutableListOf<HrvChartPoint>()
        val lnRmssd        = mutableListOf<HrvChartPoint>()
        val sdnn           = mutableListOf<HrvChartPoint>()
        val restingHr      = mutableListOf<HrvChartPoint>()

        if (shouldAggregateByMonth) {
            // Group by year-month and calculate averages
            val byMonth: Map<String, List<DailyReadingEntity>> = chronological.groupBy { entity ->
                val date = Instant.ofEpochMilli(entity.timestamp).atZone(zone).toLocalDate()
                "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
            }

            // Sort by month key (chronological order)
            val sortedMonths = byMonth.keys.sorted()
            
            sortedMonths.forEachIndexed { idx, monthKey ->
                val monthData = byMonth[monthKey]!!
                
                // Calculate averages for each metric
                val avgReadinessScore = monthData.map { it.readinessScore.toFloat() }.average().toFloat()
                val avgRmssd = monthData.map { it.rawRmssdMs }.average().toFloat()
                val avgLnRmssd = monthData.map { it.lnRmssd }.average().toFloat()
                val avgSdnn = monthData.map { it.sdnnMs }.average().toFloat()
                val avgRestingHr = monthData.map { it.restingHrBpm }.average().toFloat()

                val x = idx.toFloat()
                val label = monthKey // Use "YYYY-MM" as label

                readinessScore.add(HrvChartPoint(x, avgReadinessScore, label))
                rmssd.add(HrvChartPoint(x, avgRmssd, label))
                lnRmssd.add(HrvChartPoint(x, avgLnRmssd, label))
                sdnn.add(HrvChartPoint(x, avgSdnn, label))
                restingHr.add(HrvChartPoint(x, avgRestingHr, label))
            }
        } else {
            // Use daily data for shorter time periods
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
