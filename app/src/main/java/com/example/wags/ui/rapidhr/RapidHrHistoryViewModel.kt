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

// ── UI state ──────────────────────────────────────────────────────────────────

data class RapidHrHistoryUiState(
    val allSessions: List<RapidHrSessionEntity> = emptyList(),
    val directionFilter: RapidHrDirection? = null,   // null = all
    val chartPoints: List<RapidHrChartPoint> = emptyList(),
    val datesWithSessions: Set<LocalDate> = emptySet(),
    val selectedDate: LocalDate? = null,
    val selectedDaySessions: List<RapidHrSessionEntity> = emptyList(),
    val selectedTab: RapidHrHistoryTab = RapidHrHistoryTab.GRAPHS
) {
    val filteredSessions: List<RapidHrSessionEntity>
        get() = if (directionFilter == null) allSessions
        else allSessions.filter { it.direction == directionFilter.name }
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
            selectedTab = RapidHrHistoryTab.GRAPHS,
            selectedDate = null
        )
    )

    val uiState: StateFlow<RapidHrHistoryUiState> = combine(
        repository.observeAll(),
        _extra
    ) { sessions, extra ->
        val filtered = if (extra.directionFilter == null) sessions
        else sessions.filter { it.direction == extra.directionFilter.name }

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
            chartPoints = chartPoints,
            datesWithSessions = datesWithSessions,
            selectedDate = extra.selectedDate,
            selectedDaySessions = selectedDaySessions,
            selectedTab = extra.selectedTab
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RapidHrHistoryUiState())

    fun setDirectionFilter(direction: RapidHrDirection?) {
        _extra.update { it.copy(directionFilter = direction) }
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
    val selectedTab: RapidHrHistoryTab,
    val selectedDate: LocalDate?
)
