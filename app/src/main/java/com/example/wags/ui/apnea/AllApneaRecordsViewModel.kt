package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
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
 *
 * The special sentinel [FREE_HOLD_PB_SENTINEL] ("FREE_HOLD_PB") is used to
 * represent "Free Hold Personal Bests" — it is not a real tableType value but
 * is handled specially in the ViewModel.
 */
data class ApneaEventType(
    val label: String,
    val tableTypeValue: String?   // null = free hold; FREE_HOLD_PB_SENTINEL = PB free holds
) {
    companion object {
        /** Sentinel value used in [tableTypeValue] to identify the PB-free-hold filter. */
        const val FREE_HOLD_PB_SENTINEL = "FREE_HOLD_PB"

        val ALL: List<ApneaEventType> = listOf(
            ApneaEventType("Free Hold",              null),
            ApneaEventType("Free Hold Best",         FREE_HOLD_PB_SENTINEL),
            ApneaEventType("O₂ Table",               "O2"),
            ApneaEventType("CO₂ Table",              "CO2"),
            ApneaEventType("Progressive O₂",         "PROGRESSIVE_O2"),
            ApneaEventType("Min Breath",             "MIN_BREATH"),
            ApneaEventType("Wonka: Contraction",     "WONKA_FIRST_CONTRACTION"),
            ApneaEventType("Wonka: Endurance",       "WONKA_ENDURANCE"),
        )

        /** All tableTypeValues that are "real" DB types (excludes the PB sentinel). */
        val REAL_TABLE_TYPE_VALUES: Set<String?> =
            ALL.map { it.tableTypeValue }
               .filter { it != FREE_HOLD_PB_SENTINEL }
               .toSet()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chart data point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single point on the progress chart.
 * [x] is the record's timestamp (ms since epoch), [y] is the metric value.
 */
data class ChartPoint(val x: Long, val y: Float, val recordId: Long)

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

data class AllApneaRecordsUiState(
    // ── Settings filters ──────────────────────────────────────────────────────
    /** "" means "all values" for that dimension */
    val filterLungVolume: String = "",
    val filterPrepType: String = "",
    val filterTimeOfDay: String = "",
    val filterPosture: String = "",
    val filterAudio: String = "",

    // ── Event-type filter ─────────────────────────────────────────────────────
    /**
     * Which event types are currently selected (by tableTypeValue, including the PB sentinel).
     * Starts with all real types selected (PB sentinel excluded by default).
     */
    val selectedEventTypes: Set<String?> = ApneaEventType.REAL_TABLE_TYPE_VALUES,

    // ── Loaded records ────────────────────────────────────────────────────────
    val records: List<ApneaRecordEntity> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,

    // ── Chart ─────────────────────────────────────────────────────────────────
    /**
     * Non-null when exactly one event type is selected AND that type has a
     * meaningful single-metric Y axis. Points are sorted oldest→newest for
     * left-to-right display.
     */
    val chartPoints: List<ChartPoint>? = null,
    /** Human-readable label for the Y axis (e.g. "Hold duration"). */
    val chartYLabel: String = "",

    // ── Misc ──────────────────────────────────────────────────────────────────
    val isInitialLoad: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

private const val PAGE_SIZE = 30

@HiltViewModel
class AllApneaRecordsViewModel @Inject constructor(
    private val repository: ApneaRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllApneaRecordsUiState())
    val uiState: StateFlow<AllApneaRecordsUiState> = _uiState.asStateFlow()

    init {
        // Read initial filter params injected via navigation arguments
        val initLungVolume = savedStateHandle.get<String>("lungVolume") ?: ""
        val initPrepType   = savedStateHandle.get<String>("prepType")   ?: ""
        val initTimeOfDay  = savedStateHandle.get<String>("timeOfDay")  ?: ""
        val initPosture    = savedStateHandle.get<String>("posture")    ?: ""
        val initAudio      = savedStateHandle.get<String>("audio")      ?: ""
        val initEventTypes = savedStateHandle.get<String>("eventTypes") ?: "ALL"

        val initialSelectedTypes: Set<String?> = when {
            initEventTypes == "ALL" -> ApneaEventType.REAL_TABLE_TYPE_VALUES
            else -> {
                // Comma-separated list; "FREE_HOLD" maps to null (free hold tableType)
                initEventTypes.split(",").map { token ->
                    when (token.trim()) {
                        "FREE_HOLD" -> null
                        else        -> token.trim().ifEmpty { null }
                    }
                }.toSet()
            }
        }

        _uiState.update {
            it.copy(
                filterLungVolume   = initLungVolume,
                filterPrepType     = initPrepType,
                filterTimeOfDay    = initTimeOfDay,
                filterPosture      = initPosture,
                filterAudio        = initAudio,
                selectedEventTypes = initialSelectedTypes
            )
        }

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

    fun setPostureFilter(value: String) {
        _uiState.update { it.copy(filterPosture = value) }
        loadNextPage(reset = true)
    }

    fun setAudioFilter(value: String) {
        _uiState.update { it.copy(filterAudio = value) }
        loadNextPage(reset = true)
    }

    /** Removes a single record from the in-memory list without a full reload. */
    fun removeRecord(recordId: Long) {
        _uiState.update { it.copy(records = it.records.filter { r -> r.recordId != recordId }) }
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
        // "Select All" includes every event type, including the PB sentinel
        val all = ApneaEventType.ALL.map { it.tableTypeValue }.toSet()
        _uiState.update { it.copy(selectedEventTypes = all) }
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
                records       = if (reset) emptyList() else it.records,
                hasMore       = if (reset) true else it.hasMore
            )
        }

        viewModelScope.launch {
            val s = _uiState.value
            val selected = s.selectedEventTypes

            // Separate the PB sentinel from real types
            val pbSelected    = selected.contains(ApneaEventType.FREE_HOLD_PB_SENTINEL)
            val realSelected  = selected.filter { it != ApneaEventType.FREE_HOLD_PB_SENTINEL }.toSet()

            val page: List<ApneaRecordEntity> = when {
                // Nothing selected → empty
                selected.isEmpty() -> emptyList()

                // Only PB sentinel selected → fetch PB free holds
                pbSelected && realSelected.isEmpty() -> {
                    repository.getPagedPersonalBestFreeHolds(
                        lungVolume = s.filterLungVolume,
                        prepType   = s.filterPrepType,
                        timeOfDay  = s.filterTimeOfDay,
                        posture    = s.filterPosture,
                        audio      = s.filterAudio,
                        pageSize   = PAGE_SIZE,
                        offset     = offset
                    )
                }

                // PB sentinel + real types → fetch both and merge
                pbSelected -> {
                    val pbPage = repository.getPagedPersonalBestFreeHolds(
                        lungVolume = s.filterLungVolume,
                        prepType   = s.filterPrepType,
                        timeOfDay  = s.filterTimeOfDay,
                        posture    = s.filterPosture,
                        audio      = s.filterAudio,
                        pageSize   = PAGE_SIZE,
                        offset     = offset
                    )
                    val realPage = fetchRealTypes(s, realSelected, PAGE_SIZE, offset)
                    (pbPage + realPage).sortedByDescending { it.timestamp }.take(PAGE_SIZE)
                }

                // Only real types (all selected = no type filter)
                realSelected.size == ApneaEventType.REAL_TABLE_TYPE_VALUES.size -> {
                    repository.getPagedRecords(
                        lungVolume = s.filterLungVolume,
                        prepType   = s.filterPrepType,
                        timeOfDay  = s.filterTimeOfDay,
                        posture    = s.filterPosture,
                        audio      = s.filterAudio,
                        eventTypes = emptyList(),
                        pageSize   = PAGE_SIZE,
                        offset     = offset
                    )
                }

                else -> fetchRealTypes(s, realSelected, PAGE_SIZE, offset)
            }

            val merged = if (reset) page else state.records + page

            // Build chart points when exactly one event type is selected
            val (chartPoints, chartYLabel) = buildChartData(s, selected, merged, reset, offset)

            _uiState.update { current ->
                current.copy(
                    records       = merged,
                    isLoadingMore = false,
                    hasMore       = page.size == PAGE_SIZE,
                    isInitialLoad = false,
                    chartPoints   = chartPoints,
                    chartYLabel   = chartYLabel
                )
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun fetchRealTypes(
        s: AllApneaRecordsUiState,
        realSelected: Set<String?>,
        pageSize: Int,
        offset: Int
    ): List<ApneaRecordEntity> {
        return repository.getPagedRecords(
            lungVolume = s.filterLungVolume,
            prepType   = s.filterPrepType,
            timeOfDay  = s.filterTimeOfDay,
            posture    = s.filterPosture,
            audio      = s.filterAudio,
            eventTypes = realSelected.toList(),
            pageSize   = pageSize,
            offset     = offset
        )
    }

    /**
     * Builds chart data when exactly one event type is selected.
     * Returns (null, "") when the chart should not be shown.
     *
     * For a single-type selection the chart shows the primary metric over time:
     *   - Free Hold / Free Hold Best → hold duration (ms)
     *   - O₂ Table / CO₂ Table      → hold duration (ms) — longest hold in the session
     *   - Progressive O₂            → hold duration (ms)
     *   - Min Breath                → hold duration (ms)
     *   - Wonka: Contraction        → firstContractionMs (time to first contraction)
     *   - Wonka: Endurance          → hold duration (ms)
     */
    private fun buildChartData(
        s: AllApneaRecordsUiState,
        selected: Set<String?>,
        allRecords: List<ApneaRecordEntity>,
        reset: Boolean,
        offset: Int
    ): Pair<List<ChartPoint>?, String> {
        // Only show chart when exactly one event type is selected
        if (selected.size != 1) return Pair(null, "")

        val singleType = selected.first()

        // Determine Y metric extractor and label
        val metricPair: Pair<(ApneaRecordEntity) -> Float?, String> = when (singleType) {
            null,
            ApneaEventType.FREE_HOLD_PB_SENTINEL -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
            "O2", "CO2" -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
            "PROGRESSIVE_O2" -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
            "MIN_BREATH" -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
            "WONKA_FIRST_CONTRACTION" -> Pair(
                { r: ApneaRecordEntity -> r.firstContractionMs?.toFloat() },
                "Time to contraction"
            )
            "WONKA_ENDURANCE" -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
            else -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
        }

        val yExtractor = metricPair.first
        val yLabel     = metricPair.second

        // Build points sorted oldest→newest for left-to-right chart display
        val points = allRecords
            .mapNotNull { record ->
                val y = yExtractor(record) ?: return@mapNotNull null
                ChartPoint(x = record.timestamp, y = y, recordId = record.recordId)
            }
            .sortedBy { it.x }

        return if (points.isEmpty()) Pair(null, "") else Pair(points, yLabel)
    }
}
