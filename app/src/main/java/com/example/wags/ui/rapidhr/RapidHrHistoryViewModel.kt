package com.example.wags.ui.rapidhr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.RapidHrSessionEntity
import com.example.wags.data.repository.RapidHrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

// ── Chart point ────────────────────────────────────────────────────────────────

data class RapidHrChartPoint(
    val index: Float,
    val valueMs: Long,
    val sessionId: Long,
    val timestamp: Long
)

// ── Time period ────────────────────────────────────────────────────────────────

enum class ChartTimePeriod(val label: String, val durationDays: Int?) {
    WEEK("1W", 7),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    YEAR("1Y", 365),
    ALL("All", null)
}

// ── HR combo (start→end thresholds) ───────────────────────────────────────────

data class HrCombo(val high: Int, val low: Int, val direction: String) {
    val label: String get() = "$high→$low bpm"
}

// ── UI state ──────────────────────────────────────────────────────────────────

data class RapidHrHistoryUiState(
    val allSessions: List<RapidHrSessionEntity> = emptyList(),
    val directionFilter: RapidHrDirection? = null,   // null = all
    val hrComboFilter: HrCombo? = null,              // null = all combos
    val availableCombos: List<HrCombo> = emptyList(),
    val chartPoints: List<RapidHrChartPoint> = emptyList(),
    val datesWithSessions: Set<LocalDate> = emptySet(),
    val selectedDate: LocalDate? = null,
    val selectedDaySessions: List<RapidHrSessionEntity> = emptyList(),
    val selectedTab: RapidHrHistoryTab = RapidHrHistoryTab.GRAPHS,
    val timePeriod: ChartTimePeriod = ChartTimePeriod.ALL,
    val windowStart: Long? = null,  // For sliding window (timestamp in ms)
    val windowEnd: Long? = null     // For sliding window (timestamp in ms)
) {
    val filteredSessions: List<RapidHrSessionEntity>
        get() {
            var list = allSessions
            if (directionFilter != null) list = list.filter { it.direction == directionFilter.name }
            if (hrComboFilter != null) list = list.filter {
                it.highThreshold == hrComboFilter.high && it.lowThreshold == hrComboFilter.low
            }
            // Apply time period filter
            val now = System.currentTimeMillis()
            val periodStart = when (timePeriod) {
                ChartTimePeriod.ALL -> null
                ChartTimePeriod.WEEK -> now - (7L * 24 * 60 * 60 * 1000)
                ChartTimePeriod.MONTH -> now - (30L * 24 * 60 * 60 * 1000)
                ChartTimePeriod.THREE_MONTHS -> now - (90L * 24 * 60 * 60 * 1000)
                ChartTimePeriod.YEAR -> now - (365L * 24 * 60 * 60 * 1000)
            }
            if (periodStart != null) list = list.filter { it.timestamp >= periodStart }
            // Apply sliding window if set (overrides time period)
            if (windowStart != null && windowEnd != null) {
                list = list.filter { it.timestamp in windowStart..windowEnd }
            }
            return list
        }
}

enum class RapidHrHistoryTab { GRAPHS, CALENDAR }

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class RapidHrHistoryViewModel @Inject constructor(
    private val repository: RapidHrRepository
) : ViewModel() {

    private val _extra = MutableStateFlow(
        ExtraHistoryState(
            directionFilter = null,
            hrComboFilter = null,
            selectedTab = RapidHrHistoryTab.GRAPHS,
            selectedDate = null,
            timePeriod = ChartTimePeriod.ALL,
            windowStart = null,
            windowEnd = null
        )
    )

    val uiState: StateFlow<RapidHrHistoryUiState> = combine(
        repository.observeAll(),
        _extra
    ) { sessions, extra ->
        // Build available combos from all sessions (deduplicated, sorted)
        val availableCombos = sessions
            .map { HrCombo(it.highThreshold, it.lowThreshold, it.direction) }
            .distinct()
            .sortedWith(compareBy({ it.high }, { it.low }))

        // Resolve combo filter — if the saved combo no longer exists, clear it
        val resolvedCombo = extra.hrComboFilter?.takeIf { it in availableCombos }

        var filtered = sessions
        if (extra.directionFilter != null) filtered = filtered.filter { it.direction == extra.directionFilter.name }
        if (resolvedCombo != null) filtered = filtered.filter {
            it.highThreshold == resolvedCombo.high && it.lowThreshold == resolvedCombo.low
        }

        val chartPoints = filtered
            .sortedBy { it.timestamp }
            .mapIndexed { i, s ->
                RapidHrChartPoint(i.toFloat(), s.transitionDurationMs, s.id, s.timestamp)
            }

        val zone = ZoneId.systemDefault()
        val datesWithSessions = sessions
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .toSet()

        val selectedDaySessions = extra.selectedDate?.let { date ->
            sessions.filter {
                Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == date
            }
        } ?: emptyList()

        RapidHrHistoryUiState(
            allSessions = sessions,
            directionFilter = extra.directionFilter,
            hrComboFilter = resolvedCombo,
            availableCombos = availableCombos,
            chartPoints = chartPoints,
            datesWithSessions = datesWithSessions,
            selectedDate = extra.selectedDate,
            selectedDaySessions = selectedDaySessions,
            selectedTab = extra.selectedTab,
            timePeriod = extra.timePeriod,
            windowStart = extra.windowStart,
            windowEnd = extra.windowEnd
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RapidHrHistoryUiState())

    fun setDirectionFilter(direction: RapidHrDirection?) {
        _extra.update { it.copy(directionFilter = direction, hrComboFilter = null) }
    }

    fun setHrComboFilter(combo: HrCombo?) {
        _extra.update { it.copy(hrComboFilter = combo) }
    }

    fun selectTab(tab: RapidHrHistoryTab) {
        _extra.update { it.copy(selectedTab = tab) }
    }

    fun selectDate(date: LocalDate?) {
        _extra.update { it.copy(selectedDate = date) }
    }

    fun setTimePeriod(period: ChartTimePeriod) {
        _extra.update {
            it.copy(
                timePeriod = period,
                windowStart = null,
                windowEnd = null
            )
        }
    }

    fun slideWindow(direction: Int) {
        val current = _extra.value
        val period = current.timePeriod
        if (period == ChartTimePeriod.ALL) return

        val now = System.currentTimeMillis()
        val periodDurationMs = when (period) {
            ChartTimePeriod.WEEK -> 7L * 24 * 60 * 60 * 1000
            ChartTimePeriod.MONTH -> 30L * 24 * 60 * 60 * 1000
            ChartTimePeriod.THREE_MONTHS -> 90L * 24 * 60 * 60 * 1000
            ChartTimePeriod.YEAR -> 365L * 24 * 60 * 60 * 1000
            ChartTimePeriod.ALL -> return
        }

        val shiftAmount = (periodDurationMs * 0.25).toLong() // Slide by 25% of period
        val currentEnd = current.windowEnd ?: now
        val currentStart = current.windowStart ?: (currentEnd - periodDurationMs)

        val newStart = currentStart + (shiftAmount * direction)
        val newEnd = newStart + periodDurationMs

        _extra.update {
            it.copy(
                windowStart = newStart.coerceAtLeast(0),
                windowEnd = newEnd
            )
        }
    }
}

private data class ExtraHistoryState(
    val directionFilter: RapidHrDirection?,
    val hrComboFilter: HrCombo?,
    val selectedTab: RapidHrHistoryTab,
    val selectedDate: LocalDate?,
    val timePeriod: ChartTimePeriod,
    val windowStart: Long?,
    val windowEnd: Long?
)
