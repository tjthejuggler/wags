package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DayValue(val date: LocalDate, val ms: Long)

data class TimeChartUiState(
    val title: String = "",
    val metricType: String = "hold",   // "hold" or "session"
    val drillType: String = "TOTAL",
    val isLoading: Boolean = true,
    /** Per-day values sorted by date ascending. */
    val dailyValues: List<DayValue> = emptyList(),
    /** Cumulative values (running sum) sorted by date ascending. */
    val cumulativeValues: List<DayValue> = emptyList(),
    /** Whether to show bar chart (false) or cumulative line (true). */
    val showCumulative: Boolean = false,
)

@HiltViewModel
class TimeChartViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository
) : ViewModel() {

    private val metricType = savedStateHandle.get<String>("metricType") ?: "hold"
    private val drillType = savedStateHandle.get<String>("drillType") ?: "TOTAL"
    private val title = savedStateHandle.get<String>("title") ?: "Time Chart"

    private val _uiState = MutableStateFlow(
        TimeChartUiState(title = title, metricType = metricType, drillType = drillType)
    )
    val uiState: StateFlow<TimeChartUiState> = _uiState

    init {
        loadData()
    }

    fun toggleCumulative() {
        _uiState.value = _uiState.value.copy(showCumulative = !_uiState.value.showCumulative)
    }

    private fun loadData() {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()

            if (metricType == "hold") {
                loadHoldTimeData(zone)
            } else {
                loadSessionTimeData(zone)
            }
        }
    }

    private suspend fun loadHoldTimeData(zone: ZoneId) {
        // For hold time, we use apnea_records.durationMs
        val records = apneaRepository.getAllRecordsOnce()
        val filtered = filterRecordsByDrill(records)

        val byDay = filtered
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .map { (date, recs) -> DayValue(date, recs.sumOf { it.durationMs }) }
            .sortedBy { it.date }

        val cumulative = buildCumulative(byDay)

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            dailyValues = byDay,
            cumulativeValues = cumulative
        )
    }

    private suspend fun loadSessionTimeData(zone: ZoneId) {
        if (drillType == "FREE_HOLD" || drillType == "TOTAL") {
            // Free hold session time = hold time; for TOTAL we need both records + sessions
            val records = apneaRepository.getAllRecordsOnce()
            val sessions = sessionRepository.getAllSessionsOnce()

            val dailyMap = mutableMapOf<LocalDate, Long>()

            // Free hold records: session time = durationMs
            if (drillType == "FREE_HOLD" || drillType == "TOTAL") {
                val freeHolds = records.filter { it.tableType == null }
                freeHolds.forEach { r ->
                    val date = Instant.ofEpochMilli(r.timestamp).atZone(zone).toLocalDate()
                    dailyMap[date] = (dailyMap[date] ?: 0L) + r.durationMs
                }
            }

            // Table-based sessions
            if (drillType == "TOTAL") {
                sessions.forEach { s ->
                    val date = Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate()
                    dailyMap[date] = (dailyMap[date] ?: 0L) + s.totalSessionDurationMs
                }
            } else {
                // Specific drill type from sessions
                val tableType = drillType // e.g. "O2", "CO2", etc.
                sessions.filter { it.tableType == tableType }.forEach { s ->
                    val date = Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate()
                    dailyMap[date] = (dailyMap[date] ?: 0L) + s.totalSessionDurationMs
                }
            }

            val byDay = dailyMap.map { (date, ms) -> DayValue(date, ms) }.sortedBy { it.date }
            val cumulative = buildCumulative(byDay)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                dailyValues = byDay,
                cumulativeValues = cumulative
            )
        } else {
            // Specific drill type — use sessions table
            val sessions = sessionRepository.getAllSessionsOnce()
            val filtered = sessions.filter { it.tableType == drillType }

            val byDay = filtered
                .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                .map { (date, sess) -> DayValue(date, sess.sumOf { it.totalSessionDurationMs }) }
                .sortedBy { it.date }

            val cumulative = buildCumulative(byDay)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                dailyValues = byDay,
                cumulativeValues = cumulative
            )
        }
    }

    private fun filterRecordsByDrill(records: List<ApneaRecordEntity>): List<ApneaRecordEntity> {
        return when (drillType) {
            "FREE_HOLD" -> records.filter { it.tableType == null }
            "TOTAL"     -> records // all records
            else        -> records.filter { it.tableType == drillType }
        }
    }

    private fun buildCumulative(daily: List<DayValue>): List<DayValue> {
        var running = 0L
        return daily.map { dv ->
            running += dv.ms
            DayValue(dv.date, running)
        }
    }
}
