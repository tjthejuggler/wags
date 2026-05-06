package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.DrillContext
import com.example.wags.domain.model.PersonalBestEntry
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Sentinel value meaning "don't filter on this setting". */
const val FILTER_ALL = "ALL"

// ── Chart time period ──────────────────────────────────────────────────────────

/** Time period filter for the Graphs tab. Prefixed with Apnea to avoid conflicts. */
enum class ApneaChartTimePeriod(val label: String, val days: Int?) {
    WEEK("7d", 7),
    MONTH("1m", 30),
    THREE_MONTHS("3m", 90),
    YEAR("1y", 365),
    ALL("All", null)
}

/** A single (x=index, y=value) point for a line chart. */
data class ApneaChartPoint(val dayIndex: Float, val value: Float, val label: String)

/** All chart series derived from the apnea free-hold history. */
data class ApneaChartData(
    /** Hold duration (seconds) over time — free holds only. */
    val holdDuration: List<ApneaChartPoint> = emptyList(),
    /** Min HR (bpm) over time — free holds only. */
    val minHr: List<ApneaChartPoint> = emptyList(),
    /** Max HR (bpm) over time — free holds only. */
    val maxHr: List<ApneaChartPoint> = emptyList(),
    /** Lowest SpO₂ (%) over time — only records where oximeter was connected. */
    val lowestSpO2: List<ApneaChartPoint> = emptyList(),
    /** Time to first contraction (seconds) over time — only records where it was tapped. */
    val firstContractionSec: List<ApneaChartPoint> = emptyList(),
)

/**
 * Per-drill-type trophy statistics.
 *
 * @property total          All-time total trophies won.
 * @property dailyAvg       Average trophies per active day (all-time).
 * @property weeklyAvg      Average trophies per rolling 7-day window (all-time).
 * @property monthlyAvg     Average trophies per rolling 30-day window (all-time).
 * @property todayCount     Trophies won today.
 * @property currentWeekCount  Trophies won in the last 7 days (rolling).
 * @property currentMonthCount Trophies won in the last 30 days (rolling).
 * @property highestDay     Highest trophies won in a single day ever.
 * @property highestWeek    Highest trophies won in any rolling 7-day window ever.
 * @property highestMonth   Highest trophies won in any rolling 30-day window ever.
 */
data class DrillTrophyStats(
    val total: Int = 0,
    val dailyAvg: Double = 0.0,
    val weeklyAvg: Double = 0.0,
    val monthlyAvg: Double = 0.0,
    val todayCount: Int = 0,
    val currentWeekCount: Int = 0,
    val currentMonthCount: Int = 0,
    val highestDay: Int = 0,
    val highestWeek: Int = 0,
    val highestMonth: Int = 0
)

/**
 * Aggregated trophy stats across all drill types.
 */
data class TrophyStats(
    val freeHold: DrillTrophyStats = DrillTrophyStats(),
    val progressiveO2: DrillTrophyStats = DrillTrophyStats(),
    val minBreath: DrillTrophyStats = DrillTrophyStats(),
    val total: DrillTrophyStats = DrillTrophyStats()
)

data class ApneaHistoryUiState(
    val lungVolume: String = "FULL",
    val prepType: String = PrepType.NO_PREP.name,
    val timeOfDay: String = TimeOfDay.DAY.name,
    val posture: String = Posture.LAYING.name,
    val audio: String = AudioSetting.SILENCE.name,
    val isLoading: Boolean = true,
    // Stats tab
    val filteredStats: ApneaStats = ApneaStats(),
    val allStats: ApneaStats = ApneaStats(),
    val showAllStats: Boolean = false,
    /** Detailed trophy stats broken down by drill type. */
    val trophyStats: TrophyStats = TrophyStats(),
    // Calendar tab — ALL records (not filtered by settings)
    /** Set of dates that have at least one apnea record of any kind. */
    val allDatesWithRecords: Set<LocalDate> = emptySet(),
    /** Map from date → all records on that date (sorted newest first). */
    val allRecordsByDate: Map<LocalDate, List<ApneaRecordEntity>> = emptyMap(),
    /** Currently selected calendar date (null = nothing selected). */
    val selectedDate: LocalDate? = null,
    /**
     * Records for the selected date.
     * - Empty  → nothing selected
     * - Size 1 → navigate directly to detail
     * - Size >1 → show multi-session picker list
     */
    val selectedDayRecords: List<ApneaRecordEntity> = emptyList(),
    // Graphs tab
    /** Pre-computed chart series (chronological order, oldest → newest). */
    val chartData: ApneaChartData = ApneaChartData(),
    /** Total number of free-hold records (for the reading count label). */
    val totalFreeHoldCount: Int = 0,
    /** Currently selected time period filter for graphs. */
    val timePeriod: ApneaChartTimePeriod = ApneaChartTimePeriod.ALL,
    /** Step offset from the present (0 = current period, -1 = one step back, etc.). */
    val periodOffset: Int = 0,
    /** Whether stepping further back is possible (data exists before the window). */
    val canStepBack: Boolean = true,
    /** Whether stepping forward is possible (not already at the present). */
    val canStepForward: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ApneaHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository
) : ViewModel() {

    // ── Mutable settings (start from nav args, can be changed in Stats tab) ──────
    private val _lungVolume = MutableStateFlow(
        savedStateHandle.get<String>("lungVolume") ?: "FULL"
    )
    private val _prepType = MutableStateFlow(
        savedStateHandle.get<String>("prepType") ?: PrepType.NO_PREP.name
    )
    private val _timeOfDay = MutableStateFlow(
        savedStateHandle.get<String>("timeOfDay") ?: TimeOfDay.DAY.name
    )
    private val _posture = MutableStateFlow(
        savedStateHandle.get<String>("posture") ?: Posture.LAYING.name
    )
    private val _audio = MutableStateFlow(
        savedStateHandle.get<String>("audio") ?: AudioSetting.SILENCE.name
    )

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val _showAllStats = MutableStateFlow(false)

    // ── Graphs tab state ──────────────────────────────────────────────────────
    private val _timePeriod = MutableStateFlow(ApneaChartTimePeriod.ALL)
    private val _periodOffset = MutableStateFlow(0)

    // ── Settings tuple flow (triggers re-subscription when any setting changes) ──
    private val settingsFlow = combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) {
        lv, pt, tod, pos, aud -> Quintuple(lv, pt, tod, pos, aud)
    }

    // ── Filtered stats (react to settings changes) ────────────────────────────
    private val filteredStatsFlow = settingsFlow.flatMapLatest { (lv, pt, tod, pos, aud) ->
        apneaRepository.getStats(lv, pt, tod, pos, aud)
    }

    private val _trophyStats = MutableStateFlow(TrophyStats())

    val uiState: StateFlow<ApneaHistoryUiState> = combine(
        combine(
            settingsFlow,
            filteredStatsFlow,
            _showAllStats
        ) { settings, filteredStats, showAll -> Triple(settings, filteredStats, showAll) },
        combine(
            apneaRepository.getStatsAll(),
            apneaRepository.getAllRecords(),
            combine(_selectedDate, MutableStateFlow(Unit)) { d, _ -> d }
        ) { allStats, allRecords, selectedDate -> Triple(allStats, allRecords, selectedDate) },
        combine(_trophyStats, _timePeriod, _periodOffset) { trophy, period, offset ->
            Triple(trophy, period, offset)
        }
    ) { (settings, filteredStats, showAll), (allStats, allRecords, selectedDate), (trophyStats, timePeriod, periodOffset) ->
        val (lv, pt, tod, pos, aud) = settings
        val zone = ZoneId.systemDefault()

        // Build date → records map for ALL records (calendar is not filtered by settings)
        val byDate: Map<LocalDate, List<ApneaRecordEntity>> = allRecords
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        val selectedDayRecords = if (selectedDate != null) {
            byDate[selectedDate] ?: emptyList()
        } else emptyList()

        // Build chart data from free-hold records only (chronological, oldest first)
        val freeHolds = allRecords.filter { it.tableType == null }.reversed()
        val totalFreeHoldCount = freeHolds.size
        val (filteredForChart, canBack, canFwd) = filterByPeriod(freeHolds, timePeriod, periodOffset, zone)
        val chartData = buildChartData(filteredForChart, zone)

        ApneaHistoryUiState(
            lungVolume          = lv,
            prepType            = pt,
            timeOfDay           = tod,
            posture             = pos,
            audio               = aud,
            isLoading           = false,
            filteredStats       = filteredStats,
            allStats            = allStats,
            showAllStats        = showAll,
            trophyStats         = trophyStats,
            allDatesWithRecords = byDate.keys,
            allRecordsByDate    = byDate,
            selectedDate        = selectedDate,
            selectedDayRecords  = selectedDayRecords,
            chartData           = chartData,
            totalFreeHoldCount  = totalFreeHoldCount,
            timePeriod          = timePeriod,
            periodOffset        = periodOffset,
            canStepBack         = canBack,
            canStepForward      = canFwd,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ApneaHistoryUiState()
    )

    init {
        // Recompute trophy stats whenever settings or showAllStats changes
        viewModelScope.launch {
            combine(settingsFlow, _showAllStats) { settings, showAll ->
                Pair(settings, showAll)
            }.collect { (settings, showAll) ->
                val (lv, pt, tod, pos, aud) = settings
                _trophyStats.value = computeTrophyStats(
                    showAll = showAll,
                    lungVolume = lv,
                    prepType = pt,
                    timeOfDay = tod,
                    posture = pos,
                    audio = aud
                )
            }
        }
    }

    // ── Settings setters (for Stats tab popup) ────────────────────────────────
    fun setLungVolume(v: String)   { _lungVolume.value = v }
    fun setPrepType(v: String)     { _prepType.value = v }
    fun setTimeOfDay(v: String)    { _timeOfDay.value = v }
    fun setPosture(v: String)      { _posture.value = v }
    fun setAudio(v: String)        { _audio.value = v }

    /** Called when user taps a day on the calendar. */
    fun selectDate(date: LocalDate) { _selectedDate.value = date }

    /** Called when user dismisses the multi-session list or navigates away. */
    fun clearSelection() { _selectedDate.value = null }

    fun toggleShowAllStats() { _showAllStats.value = !_showAllStats.value }

    // ── Graphs tab actions ────────────────────────────────────────────────────

    /** Called when user selects a time period filter. Resets offset to 0. */
    fun setTimePeriod(period: ApneaChartTimePeriod) {
        _timePeriod.value = period
        _periodOffset.value = 0
    }

    /** Step backward in time by one period. */
    fun stepBack() {
        _periodOffset.value = _periodOffset.value - 1
    }

    /** Step forward in time by one period. */
    fun stepForward() {
        if (_periodOffset.value < 0) {
            _periodOffset.value = _periodOffset.value + 1
        }
    }

    // ── Period filtering ──────────────────────────────────────────────────────

    private data class FilterResult(
        val records: List<ApneaRecordEntity>,
        val canStepBack: Boolean,
        val canStepForward: Boolean
    )

    private fun filterByPeriod(
        chronological: List<ApneaRecordEntity>,
        period: ApneaChartTimePeriod,
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

    // ── Chart builder ─────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<ApneaRecordEntity>,
        zone: ZoneId
    ): ApneaChartData {
        if (chronological.isEmpty()) return ApneaChartData()

        val holdDuration       = mutableListOf<ApneaChartPoint>()
        val minHr              = mutableListOf<ApneaChartPoint>()
        val maxHr              = mutableListOf<ApneaChartPoint>()
        val lowestSpO2         = mutableListOf<ApneaChartPoint>()
        val firstContractionSec = mutableListOf<ApneaChartPoint>()

        chronological.forEachIndexed { idx, e ->
            val x = idx.toFloat()
            val label = Instant.ofEpochMilli(e.timestamp)
                .atZone(zone).toLocalDate().toString()

            holdDuration.add(ApneaChartPoint(x, (e.durationMs / 1000f), label))
            minHr.add(ApneaChartPoint(x, e.minHrBpm, label))
            maxHr.add(ApneaChartPoint(x, e.maxHrBpm, label))
            e.lowestSpO2?.let { lowestSpO2.add(ApneaChartPoint(x, it.toFloat(), label)) }
            e.firstContractionMs?.let {
                firstContractionSec.add(ApneaChartPoint(x, it / 1000f, label))
            }
        }

        return ApneaChartData(
            holdDuration        = holdDuration,
            minHr               = minHr,
            maxHr               = maxHr,
            lowestSpO2          = lowestSpO2,
            firstContractionSec = firstContractionSec,
        )
    }

    // ── Trophy stats computation ──────────────────────────────────────────────

    /**
     * Returns true if a [PersonalBestEntry] matches the current filter settings.
     *
     * The filter applies to Free Hold only. An entry's setting field is either:
     * - empty string → "any" (matches everything)
     * - a specific value → must match the filter value (unless filter is FILTER_ALL)
     *
     * When [showAll] is true, all entries pass.
     */
    private fun PersonalBestEntry.matchesFilter(
        showAll: Boolean,
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Boolean {
        if (showAll) return true
        // Each entry field: empty = "any" (always matches). Non-empty must match filter (unless filter is ALL).
        fun fieldMatches(entryField: String, filterValue: String): Boolean {
            if (entryField.isEmpty()) return true          // entry covers "any" value
            if (filterValue == FILTER_ALL) return true     // filter is "all"
            return entryField == filterValue
        }
        return fieldMatches(this.lungVolume, lungVolume) &&
               fieldMatches(this.prepType, prepType) &&
               fieldMatches(this.timeOfDay, timeOfDay) &&
               fieldMatches(this.posture, posture) &&
               fieldMatches(this.audio, audio)
    }

    /**
     * Builds a [DrillTrophyStats] from a map of date → total trophies on that date.
     * Uses rolling 7-day and 30-day windows for weekly/monthly stats.
     */
    private fun buildDrillStats(
        byDate: Map<LocalDate, Int>,
        today: LocalDate
    ): DrillTrophyStats {
        if (byDate.isEmpty()) return DrillTrophyStats()

        val total = byDate.values.sum()
        val activeDays = byDate.size

        // Daily average = total / number of active days
        val dailyAvg = if (activeDays > 0) total.toDouble() / activeDays else 0.0

        // Weekly/monthly average = total / span in weeks/months
        val sortedDates = byDate.keys.sorted()
        val firstDay = sortedDates.first()
        val lastDay = sortedDates.last()
        val spanDays = (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt()
        val spanWeeks = maxOf(1, (spanDays + 6) / 7)
        val spanMonths = maxOf(1, (spanDays + 29) / 30)
        val weeklyAvg = total.toDouble() / spanWeeks
        val monthlyAvg = total.toDouble() / spanMonths

        // Today / current 7-day / current 30-day
        val todayCount = byDate[today] ?: 0
        val weekAgo = today.minusDays(6)
        val monthAgo = today.minusDays(29)
        val currentWeekCount = byDate.entries
            .filter { !it.key.isBefore(weekAgo) && !it.key.isAfter(today) }
            .sumOf { it.value }
        val currentMonthCount = byDate.entries
            .filter { !it.key.isBefore(monthAgo) && !it.key.isAfter(today) }
            .sumOf { it.value }

        // Highest single day ever
        val highestDay = byDate.values.maxOrNull() ?: 0

        // Highest rolling 7-day window ever
        var highestWeek = 0
        for (windowEnd in sortedDates) {
            val windowStart = windowEnd.minusDays(6)
            val windowSum = byDate.entries
                .filter { !it.key.isBefore(windowStart) && !it.key.isAfter(windowEnd) }
                .sumOf { it.value }
            if (windowSum > highestWeek) highestWeek = windowSum
        }

        // Highest rolling 30-day window ever
        var highestMonth = 0
        for (windowEnd in sortedDates) {
            val windowStart = windowEnd.minusDays(29)
            val windowSum = byDate.entries
                .filter { !it.key.isBefore(windowStart) && !it.key.isAfter(windowEnd) }
                .sumOf { it.value }
            if (windowSum > highestMonth) highestMonth = windowSum
        }

        return DrillTrophyStats(
            total = total,
            dailyAvg = dailyAvg,
            weeklyAvg = weeklyAvg,
            monthlyAvg = monthlyAvg,
            todayCount = todayCount,
            currentWeekCount = currentWeekCount,
            currentMonthCount = currentMonthCount,
            highestDay = highestDay,
            highestWeek = highestWeek,
            highestMonth = highestMonth
        )
    }

    /**
     * Computes trophy stats for each drill type and the combined total.
     *
     * For Free Hold: respects the current filter settings (or all if [showAll]).
     * For Progressive O₂ and Min Breath: always uses all records (filter doesn't apply).
     */
    private suspend fun computeTrophyStats(
        showAll: Boolean,
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): TrophyStats {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // Build recordId → trophyCount maps per drill
        val freeHoldTrophies = mutableMapOf<Long, Int>()
        val progressiveO2Trophies = mutableMapOf<Long, Int>()
        val minBreathTrophies = mutableMapOf<Long, Int>()

        fun addToMap(map: MutableMap<Long, Int>, id: Long, count: Int) {
            val existing = map[id] ?: 0
            if (count > existing) map[id] = count
        }

        // Free Hold — apply filter
        for (entry in apneaRepository.getAllPersonalBests(DrillContext.FREE_HOLD)) {
            if (!entry.matchesFilter(showAll, lungVolume, prepType, timeOfDay, posture, audio)) continue
            val id = entry.recordId ?: continue
            addToMap(freeHoldTrophies, id, entry.trophyCount)
        }
        // Progressive O₂ — no filter
        for (entry in apneaRepository.getAllPersonalBests(DrillContext.PROGRESSIVE_O2_ANY)) {
            val id = entry.recordId ?: continue
            addToMap(progressiveO2Trophies, id, entry.trophyCount)
        }
        // Min Breath — no filter
        for (entry in apneaRepository.getAllPersonalBests(DrillContext.MIN_BREATH_ANY)) {
            val id = entry.recordId ?: continue
            addToMap(minBreathTrophies, id, entry.trophyCount)
        }

        // Load all records to get timestamps
        val allRecords = apneaRepository.getAllRecordsOnce()
        val recordById = allRecords.associateBy { it.recordId }

        fun buildDateMap(trophyMap: Map<Long, Int>): Map<LocalDate, Int> {
            val byDate = mutableMapOf<LocalDate, Int>()
            for ((recordId, trophyCount) in trophyMap) {
                val record = recordById[recordId] ?: continue
                val date = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
                byDate[date] = (byDate[date] ?: 0) + trophyCount
            }
            return byDate
        }

        val freeHoldByDate = buildDateMap(freeHoldTrophies)
        val progressiveO2ByDate = buildDateMap(progressiveO2Trophies)
        val minBreathByDate = buildDateMap(minBreathTrophies)

        // Combined total: merge all three maps
        val totalByDate = mutableMapOf<LocalDate, Int>()
        for ((date, count) in freeHoldByDate) totalByDate[date] = (totalByDate[date] ?: 0) + count
        for ((date, count) in progressiveO2ByDate) totalByDate[date] = (totalByDate[date] ?: 0) + count
        for ((date, count) in minBreathByDate) totalByDate[date] = (totalByDate[date] ?: 0) + count

        return TrophyStats(
            freeHold = buildDrillStats(freeHoldByDate, today),
            progressiveO2 = buildDrillStats(progressiveO2ByDate, today),
            minBreath = buildDrillStats(minBreathByDate, today),
            total = buildDrillStats(totalByDate, today)
        )
    }
}

/** Simple 5-tuple to avoid Pair-of-Pairs nesting. */
private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component1() = first
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component2() = second
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component3() = third
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component4() = fourth
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component5() = fifth
