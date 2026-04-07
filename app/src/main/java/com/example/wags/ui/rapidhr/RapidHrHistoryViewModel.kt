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
import javax.inject.Inject

// ── Chart point ────────────────────────────────────────────────────────────────

data class RapidHrChartPoint(val index: Float, val valueMs: Long, val sessionId: Long)

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
    val selectedTab: RapidHrHistoryTab = RapidHrHistoryTab.GRAPHS
) {
    val filteredSessions: List<RapidHrSessionEntity>
        get() {
            var list = allSessions
            if (directionFilter != null) list = list.filter { it.direction == directionFilter.name }
            if (hrComboFilter != null) list = list.filter {
                it.highThreshold == hrComboFilter.high && it.lowThreshold == hrComboFilter.low
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
            selectedDate = null
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
                RapidHrChartPoint(i.toFloat(), s.transitionDurationMs, s.id)
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
            selectedTab = extra.selectedTab
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
}

private data class ExtraHistoryState(
    val directionFilter: RapidHrDirection?,
    val hrComboFilter: HrCombo?,
    val selectedTab: RapidHrHistoryTab,
    val selectedDate: LocalDate?
)
