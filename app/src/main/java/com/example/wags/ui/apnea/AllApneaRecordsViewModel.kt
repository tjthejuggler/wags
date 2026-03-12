package com.example.wags.ui.apnea

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Event-type descriptor
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents a selectable event type in the All Records filter.
 * [tableTypeValue] is the value stored in [ApneaRecordEntity.tableType]:
 *   null  → Free Hold (tableType IS NULL)
 *   "O2"  → O₂ Table
 *   etc.
 */
data class ApneaEventType(
    val label: String,
    val tableTypeValue: String?   // null = free hold
) {
    companion object {
        val ALL: List<ApneaEventType> = listOf(
            ApneaEventType("Free Hold",          null),
            ApneaEventType("O₂ Table",           "O2"),
            ApneaEventType("CO₂ Table",          "CO2"),
            ApneaEventType("Progressive O₂",     "PROGRESSIVE_O2"),
            ApneaEventType("Min Breath",         "MIN_BREATH"),
            ApneaEventType("Wonka: Contraction", "WONKA_FIRST_CONTRACTION"),
            ApneaEventType("Wonka: Endurance",   "WONKA_ENDURANCE"),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

data class AllApneaRecordsUiState(
    // ── Settings filters ──────────────────────────────────────────────────────
    /** "" means "all values" for that dimension */
    val filterLungVolume: String = "",
    val filterPrepType: String = "",
    val filterTimeOfDay: String = "",

    // ── Event-type filter ─────────────────────────────────────────────────────
    /** Which event types are currently selected. Empty set = all selected (select-all). */
    val selectedEventTypes: Set<String?> = ApneaEventType.ALL.map { it.tableTypeValue }.toSet(),

    // ── Loaded records ────────────────────────────────────────────────────────
    val records: List<ApneaRecordEntity> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,

    // ── Misc ──────────────────────────────────────────────────────────────────
    val isInitialLoad: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

private const val PAGE_SIZE = 30

@HiltViewModel
class AllApneaRecordsViewModel @Inject constructor(
    private val repository: ApneaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllApneaRecordsUiState())
    val uiState: StateFlow<AllApneaRecordsUiState> = _uiState.asStateFlow()

    init {
        // Load first page with default (all) filters
        loadNextPage(reset = true)
    }

    // ── Public filter actions ─────────────────────────────────────────────────

    fun setLungVolumeFilter(value: String) {
        _uiState.update { it.copy(filterLungVolume = value) }
        loadNextPage(reset = true)
    }

    fun setPrepTypeFilter(value: String) {
        _uiState.update { it.copy(filterPrepType = value) }
        loadNextPage(reset = true)
    }

    fun setTimeOfDayFilter(value: String) {
        _uiState.update { it.copy(filterTimeOfDay = value) }
        loadNextPage(reset = true)
    }

    fun toggleEventType(tableTypeValue: String?) {
        val current = _uiState.value.selectedEventTypes.toMutableSet()
        if (current.contains(tableTypeValue)) {
            current.remove(tableTypeValue)
        } else {
            current.add(tableTypeValue)
        }
        _uiState.update { it.copy(selectedEventTypes = current) }
        loadNextPage(reset = true)
    }

    fun selectAllEventTypes() {
        _uiState.update {
            it.copy(selectedEventTypes = ApneaEventType.ALL.map { t -> t.tableTypeValue }.toSet())
        }
        loadNextPage(reset = true)
    }

    fun clearAllEventTypes() {
        _uiState.update { it.copy(selectedEventTypes = emptySet()) }
        loadNextPage(reset = true)
    }

    // ── Infinite scroll ───────────────────────────────────────────────────────

    fun loadNextPage(reset: Boolean = false) {
        val state = _uiState.value
        if (!reset && (state.isLoadingMore || !state.hasMore)) return

        val offset = if (reset) 0 else state.records.size

        _uiState.update {
            it.copy(
                isLoadingMore = true,
                records = if (reset) emptyList() else it.records,
                hasMore = if (reset) true else it.hasMore
            )
        }

        viewModelScope.launch {
            val s = _uiState.value
            val eventTypesList: List<String?> = if (s.selectedEventTypes.size == ApneaEventType.ALL.size) {
                // All selected → no type filter
                emptyList()
            } else {
                s.selectedEventTypes.toList()
            }

            val page = repository.getPagedRecords(
                lungVolume = s.filterLungVolume,
                prepType   = s.filterPrepType,
                timeOfDay  = s.filterTimeOfDay,
                eventTypes = eventTypesList,
                pageSize   = PAGE_SIZE,
                offset     = offset
            )

            _uiState.update { current ->
                val merged = if (reset) page else current.records + page
                current.copy(
                    records       = merged,
                    isLoadingMore = false,
                    hasMore       = page.size == PAGE_SIZE,
                    isInitialLoad = false
                )
            }
        }
    }
}
