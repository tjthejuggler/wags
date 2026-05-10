package com.example.wags.ui.apnea

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.DrillContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * A single bar in the trophy chart — one day's trophy data.
 *
 * @property date         The calendar date.
 * @property totalTrophies Sum of trophyCount for all PB records set on this day.
 * @property maxTrophies  The highest single-record trophyCount on this day.
 */
data class TrophyChartDay(
    val date: LocalDate,
    val totalTrophies: Int,
    val maxTrophies: Int
)

data class TrophyChartUiState(
    val days: List<TrophyChartDay> = emptyList(),
    /** When true, show total trophies per day; when false, show max single-record trophies. */
    val showTotal: Boolean = true,
    /** When true, include Free Hold records in the chart. */
    val includeFreeHold: Boolean = true,
    /** When true, include Progressive O₂ records in the chart. */
    val includeProgressiveO2: Boolean = true,
    /** When true, include Min Breath records in the chart. */
    val includeMinBreath: Boolean = true,
    /** When true, show the drill-include settings popup. */
    val showSettingsPopup: Boolean = false,
    val isLoading: Boolean = true
)

@HiltViewModel
class TrophyChartViewModel @Inject constructor(
    private val apneaRepository: ApneaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrophyChartUiState())
    val uiState: StateFlow<TrophyChartUiState> = _uiState.asStateFlow()

    init {
        loadChart()
    }

    private fun loadChart() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val state = _uiState.value
            val days = computeDays(state.includeFreeHold, state.includeProgressiveO2, state.includeMinBreath)
            _uiState.update { it.copy(days = days, isLoading = false) }
        }
    }

    fun toggleShowTotal() {
        _uiState.update { it.copy(showTotal = !it.showTotal) }
    }

    fun toggleSettingsPopup() {
        _uiState.update { it.copy(showSettingsPopup = !it.showSettingsPopup) }
    }

    fun setIncludeFreeHold(include: Boolean) {
        _uiState.update { it.copy(includeFreeHold = include) }
        loadChart()
    }

    fun setIncludeProgressiveO2(include: Boolean) {
        _uiState.update { it.copy(includeProgressiveO2 = include) }
        loadChart()
    }

    fun setIncludeMinBreath(include: Boolean) {
        _uiState.update { it.copy(includeMinBreath = include) }
        loadChart()
    }

    /**
     * Computes per-day trophy data.
     *
     * For each record that is a PB (i.e. it was the best for its exact 5-setting combo
     * at the time it was saved), we assign it a trophyCount based on how broad a PB it was.
     *
     * Uses per-duration drill contexts (e.g. minBreath(120), progressiveO2(60))
     * instead of *_ANY so that a 2-min min breath PB shows up even if there's
     * a better 5-min record. This matches what the PersonalBests screen shows.
     */
    private suspend fun computeDays(
        includeFreeHold: Boolean,
        includeProgressiveO2: Boolean,
        includeMinBreath: Boolean
    ): List<TrophyChartDay> {
        val zone = ZoneId.systemDefault()

        // Load all records up front — needed for timestamps and to discover drill param values
        val allRecords = apneaRepository.getAllRecordsOnce()
        val recordById = allRecords.associateBy { it.recordId }

        // Build per-duration drill contexts instead of *_ANY.
        // This ensures a 2-min min breath PB appears even if a 5-min record is better overall.
        val drills = mutableListOf<DrillContext>()
        if (includeFreeHold) {
            drills += DrillContext.FREE_HOLD
        }
        if (includeProgressiveO2) {
            val breathPeriods = allRecords
                .filter { it.tableType == "PROGRESSIVE_O2" && it.drillParamValue != null }
                .map { it.drillParamValue!! }
                .toSet()
            if (breathPeriods.isNotEmpty()) {
                drills += breathPeriods.map { DrillContext.progressiveO2(it) }
            } else {
                // Fallback: no records with param yet, use ANY to find any PBs
                drills += DrillContext.PROGRESSIVE_O2_ANY
            }
        }
        if (includeMinBreath) {
            val sessionDurations = allRecords
                .filter { it.tableType == "MIN_BREATH" && it.drillParamValue != null }
                .map { it.drillParamValue!! }
                .toSet()
            if (sessionDurations.isNotEmpty()) {
                drills += sessionDurations.map { DrillContext.minBreath(it) }
            } else {
                drills += DrillContext.MIN_BREATH_ANY
            }
        }

        // Map from recordId → trophyCount (only records that are current PBs)
        val recordTrophies = mutableMapOf<Long, Int>()
        for (drill in drills) {
            val entries = apneaRepository.getAllPersonalBests(drill)
            for (entry in entries) {
                val id = entry.recordId ?: continue
                // A record can appear in multiple entries (e.g. the global PB is also the exact PB).
                // We want the highest trophyCount for each record.
                val existing = recordTrophies[id] ?: 0
                if (entry.trophyCount > existing) {
                    recordTrophies[id] = entry.trophyCount
                }
            }
        }

        if (recordTrophies.isEmpty()) return emptyList()

        // Group by date
        val byDate = mutableMapOf<LocalDate, MutableList<Int>>()
        for ((recordId, trophyCount) in recordTrophies) {
            val record = recordById[recordId] ?: continue
            val date = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
            byDate.getOrPut(date) { mutableListOf() }.add(trophyCount)
        }

        // Fill in empty days between first and last trophy date
        val sortedDates = byDate.keys.sorted()
        val firstDate = sortedDates.first()
        val lastDate = sortedDates.last()
        var d = firstDate
        while (d <= lastDate) {
            byDate.getOrPut(d) { mutableListOf() }
            d = d.plusDays(1)
        }

        return byDate.entries
            .sortedBy { it.key }
            .map { (date, trophies) ->
                TrophyChartDay(
                    date = date,
                    totalTrophies = trophies.sum(),
                    maxTrophies = trophies.maxOrNull() ?: 0
                )
            }
    }
}
