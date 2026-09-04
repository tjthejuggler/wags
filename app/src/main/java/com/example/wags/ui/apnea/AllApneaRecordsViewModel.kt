package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaTimeDimensionStore
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeDimension
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Sort order
// ─────────────────────────────────────────────────────────────────────────────

enum class RecordSortOrder(val label: String) {
    RECENT_DESC("Recent first"),
    RECENT_ASC("Oldest first"),
    LENGTH_DESC("Longest first"),
    LENGTH_ASC("Shortest first"),
}

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
            ApneaEventType("Till Contraction",     "WONKA_FIRST_CONTRACTION"),
            ApneaEventType("Contraction Count",       "WONKA_ENDURANCE"),
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
    /**
     * Selected option values per dimension (multi-select).
     * All options selected = unfiltered; empty set = nothing matches.
     */
    val filterLungVolume: Set<String> = SettingFilterOptions.LUNG_VOLUMES.toSet(),
    val filterPrepType: Set<String> = SettingFilterOptions.PREP_TYPES.toSet(),
    val filterTimeOfDay: Set<String> = SettingFilterOptions.timeOfDayOptions(byHour = false).toSet(),
    val filterPosture: Set<String> = SettingFilterOptions.POSTURES.toSet(),
    val filterAudio: Set<String> = SettingFilterOptions.AUDIOS.toSet(),

    // ── Event-type filter ─────────────────────────────────────────────────────
    /**
     * Which event types are currently selected (by tableTypeValue, including the PB sentinel).
     * Defaults to Free Hold only (PB sentinel excluded).
     */
    val selectedEventTypes: Set<String?> = setOf(null),

    // ── Sort order ────────────────────────────────────────────────────────────
    val sortOrder: RecordSortOrder = RecordSortOrder.RECENT_DESC,

    // ── Loaded records ────────────────────────────────────────────────────────
    val records: List<ApneaRecordEntity> = emptyList(),
    val isLoading: Boolean = false,

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

private const val FETCH_ALL = 100_000

@HiltViewModel
class AllApneaRecordsViewModel @Inject constructor(
    private val repository: ApneaRepository,
    private val timeDimensionStore: ApneaTimeDimensionStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Selected time-bucket dimension (Time of Day vs By the Hour) — drives filter chips. */
    val timeDimension: StateFlow<TimeDimension> = timeDimensionStore.dimension

    private val _uiState = MutableStateFlow(AllApneaRecordsUiState())
    val uiState: StateFlow<AllApneaRecordsUiState> = _uiState.asStateFlow()

    init {
        // Read initial filter params injected via navigation arguments
        val initLungVolume = savedStateHandle.get<String>("lungVolume") ?: ""
        val initPrepType   = savedStateHandle.get<String>("prepType")   ?: ""
        // In BY_HOUR mode a legacy nav-arg name resolves to the current hour
        // bucket so the initial filter matches "records like now".
        val initTimeOfDay  = TimeBuckets.normalizeSessionBucket(
            savedStateHandle.get<String>("timeOfDay") ?: "", timeDimensionStore.current
        )
        val initPosture    = savedStateHandle.get<String>("posture")    ?: ""
        val initAudio      = savedStateHandle.get<String>("audio")      ?: ""
        val initEventTypes = savedStateHandle.get<String>("eventTypes")

        val initialSelectedTypes: Set<String?> = when {
            // No arg → default to Free Hold only
            initEventTypes == null -> setOf(null)
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

        val byHour = timeDimensionStore.current == TimeDimension.BY_HOUR
        _uiState.update {
            it.copy(
                filterLungVolume   = initLungVolume.takeIf { v -> v.isNotEmpty() }?.let { setOf(it) }
                    ?: SettingFilterOptions.LUNG_VOLUMES.toSet(),
                filterPrepType     = initPrepType.takeIf { v -> v.isNotEmpty() }?.let { setOf(it) }
                    ?: SettingFilterOptions.PREP_TYPES.toSet(),
                filterTimeOfDay    = initTimeOfDay.takeIf { v -> v.isNotEmpty() }?.let { setOf(it) }
                    ?: SettingFilterOptions.timeOfDayOptions(byHour).toSet(),
                filterPosture      = initPosture.takeIf { v -> v.isNotEmpty() }?.let { setOf(it) }
                    ?: SettingFilterOptions.POSTURES.toSet(),
                filterAudio        = initAudio.takeIf { v -> v.isNotEmpty() }?.let { setOf(it) }
                    ?: SettingFilterOptions.AUDIOS.toSet(),
                selectedEventTypes = initialSelectedTypes
            )
        }

        loadAllRecords()
    }

    // ── Public filter actions ─────────────────────────────────────────────────

    /** Resets every settings-filter category to "all options selected" and the event type to Free Hold. */
    fun resetFiltersToAll() {
        val byHour = timeDimensionStore.current == TimeDimension.BY_HOUR
        _uiState.update {
            it.copy(
                filterLungVolume = SettingFilterOptions.LUNG_VOLUMES.toSet(),
                filterPrepType   = SettingFilterOptions.PREP_TYPES.toSet(),
                filterTimeOfDay  = SettingFilterOptions.timeOfDayOptions(byHour).toSet(),
                filterPosture    = SettingFilterOptions.POSTURES.toSet(),
                filterAudio      = SettingFilterOptions.AUDIOS.toSet(),
                selectedEventTypes = setOf<String?>(null) // Free Hold
            )
        }
        loadAllRecords()
    }

    fun setLungVolumeFilter(value: Set<String>) {
        _uiState.update { it.copy(filterLungVolume = value) }
        loadAllRecords()
    }

    fun setPrepTypeFilter(value: Set<String>) {
        _uiState.update { it.copy(filterPrepType = value) }
        loadAllRecords()
    }

    fun setTimeOfDayFilter(value: Set<String>) {
        _uiState.update { it.copy(filterTimeOfDay = value) }
        loadAllRecords()
    }

    fun setPostureFilter(value: Set<String>) {
        _uiState.update { it.copy(filterPosture = value) }
        loadAllRecords()
    }

    fun setAudioFilter(value: Set<String>) {
        _uiState.update { it.copy(filterAudio = value) }
        loadAllRecords()
    }

    /**
     * Replaces every filter at once (used when drilling into this screen from
     * elsewhere, e.g. tapping an average in the Settings comparison tab).
     *
     * @param eventTypes tableType values to select (null = free hold); the
     * "Free Hold Best" sentinel is never set by this path.
     */
    fun applyPresetFilters(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        eventTypes: Set<String?>
    ) {
        val byHour = timeDimensionStore.current == TimeDimension.BY_HOUR
        _uiState.update {
            it.copy(
                filterLungVolume = presetToSelection(lungVolume, SettingFilterOptions.LUNG_VOLUMES),
                filterPrepType = presetToSelection(prepType, SettingFilterOptions.PREP_TYPES),
                filterTimeOfDay = presetToSelection(timeOfDay, SettingFilterOptions.timeOfDayOptions(byHour)),
                filterPosture = presetToSelection(posture, SettingFilterOptions.POSTURES),
                filterAudio = presetToSelection(audio, SettingFilterOptions.AUDIOS),
                selectedEventTypes = eventTypes
            )
        }
        loadAllRecords()
    }

    fun setSortOrder(order: RecordSortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
        loadAllRecords()
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
        loadAllRecords()
    }

    fun selectAllEventTypes() {
        // "Select All" includes every event type, including the PB sentinel
        val all = ApneaEventType.ALL.map { it.tableTypeValue }.toSet()
        _uiState.update { it.copy(selectedEventTypes = all) }
        loadAllRecords()
    }

    fun clearAllEventTypes() {
        _uiState.update { it.copy(selectedEventTypes = emptySet()) }
        loadAllRecords()
    }

    // ── Load all records ──────────────────────────────────────────────────────

    private fun loadAllRecords() {
        _uiState.update {
            it.copy(isLoading = true, records = emptyList())
        }

        viewModelScope.launch {
            val s = _uiState.value
            val selected = s.selectedEventTypes

            // A settings category with nothing selected can never match — skip the queries.
            if (s.filterLungVolume.isEmpty() || s.filterPrepType.isEmpty() || s.filterTimeOfDay.isEmpty() ||
                s.filterPosture.isEmpty() || s.filterAudio.isEmpty()
            ) {
                _uiState.update { current ->
                    current.copy(
                        records = emptyList(),
                        isLoading = false,
                        isInitialLoad = false,
                        chartPoints = null,
                        chartYLabel = ""
                    )
                }
                return@launch
            }

            // Separate the PB sentinel from real types
            val pbSelected    = selected.contains(ApneaEventType.FREE_HOLD_PB_SENTINEL)
            val realSelected  = selected.filter { it != ApneaEventType.FREE_HOLD_PB_SENTINEL }.toSet()

            val fetched: List<ApneaRecordEntity> = when {
                // Nothing selected → empty
                selected.isEmpty() -> emptyList()

                // Only PB sentinel selected → fetch PB free holds
                pbSelected && realSelected.isEmpty() -> {
                    repository.getPagedPersonalBestFreeHolds(
                        lungVolume = sqlFilter(s.filterLungVolume),
                        prepType   = sqlFilter(s.filterPrepType),
                        timeOfDay  = sqlFilter(s.filterTimeOfDay),
                        posture    = sqlFilter(s.filterPosture),
                        audio      = sqlFilter(s.filterAudio),
                        pageSize   = FETCH_ALL,
                        offset     = 0
                    )
                }

                // PB sentinel + real types → fetch both and merge
                pbSelected -> {
                    val pbPage = repository.getPagedPersonalBestFreeHolds(
                        lungVolume = sqlFilter(s.filterLungVolume),
                        prepType   = sqlFilter(s.filterPrepType),
                        timeOfDay  = sqlFilter(s.filterTimeOfDay),
                        posture    = sqlFilter(s.filterPosture),
                        audio      = sqlFilter(s.filterAudio),
                        pageSize   = FETCH_ALL,
                        offset     = 0
                    )
                    val realPage = fetchRealTypes(s, realSelected, FETCH_ALL, 0)
                    (pbPage + realPage).distinctBy { it.recordId }
                }

                // Only real types (all selected = no type filter)
                realSelected.size == ApneaEventType.REAL_TABLE_TYPE_VALUES.size -> {
                    repository.getPagedRecords(
                        lungVolume = sqlFilter(s.filterLungVolume),
                        prepType   = sqlFilter(s.filterPrepType),
                        timeOfDay  = sqlFilter(s.filterTimeOfDay),
                        posture    = sqlFilter(s.filterPosture),
                        audio      = sqlFilter(s.filterAudio),
                        eventTypes = emptyList(),
                        pageSize   = FETCH_ALL,
                        offset     = 0
                    )
                }

                else -> fetchRealTypes(s, realSelected, FETCH_ALL, 0)
            }

            // Multi-select refinement — the SQL layer only narrows single-value selections.
            val byHourTod = s.filterTimeOfDay.any { TimeBuckets.isHourBucket(it) }
            val allRecords = fetched.filter { r ->
                r.lungVolume in s.filterLungVolume &&
                r.prepType in s.filterPrepType &&
                r.posture in s.filterPosture &&
                r.audio in s.filterAudio &&
                (if (byHourTod) TimeBuckets.fromTimestamp(r.timestamp) else r.timeOfDay) in s.filterTimeOfDay
            }

            // Apply sort order
            val sorted = when (s.sortOrder) {
                RecordSortOrder.RECENT_DESC -> allRecords.sortedByDescending { it.timestamp }
                RecordSortOrder.RECENT_ASC  -> allRecords.sortedBy { it.timestamp }
                RecordSortOrder.LENGTH_DESC -> allRecords.sortedByDescending { it.durationMs }
                RecordSortOrder.LENGTH_ASC  -> allRecords.sortedBy { it.durationMs }
            }

            // Build chart points when exactly one event type is selected
            val (chartPoints, chartYLabel) = buildChartData(s, selected, sorted, true, 0)

            _uiState.update { current ->
                current.copy(
                    records       = sorted,
                    isLoading     = false,
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
            lungVolume = sqlFilter(s.filterLungVolume),
            prepType   = sqlFilter(s.filterPrepType),
            timeOfDay  = sqlFilter(s.filterTimeOfDay),
            posture    = sqlFilter(s.filterPosture),
            audio      = sqlFilter(s.filterAudio),
            eventTypes = realSelected.toList(),
            pageSize   = pageSize,
            offset     = offset
        )
    }

    /**
     * SQL still speaks single-value equality ("" = relaxed): pass a lone
     * selection through, and relax everything else — multi selections are
     * refined in memory by the caller.
     */
    private fun sqlFilter(selected: Set<String>): String = selected.singleOrNull() ?: ""

    /** Single preset value ("" = all) → multi-select option set. */
    private fun presetToSelection(value: String, options: List<String>): Set<String> =
        value.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: options.toSet()

    /**
     * Builds chart data when exactly one event type is selected.
     * Returns (null, "") when the chart should not be shown.
     *
     * For a single-type selection the chart shows the primary metric over time:
     *   - Free Hold / Free Hold Best → hold duration (ms)
     *   - O₂ Table / CO₂ Table      → hold duration (ms) — longest hold in the session
     *   - Progressive O₂            → hold duration (ms)
     *   - Min Breath                → hold duration (ms)
     *   - Till Contraction        → firstContractionMs (time to first contraction)
     *   - Contraction Count          → hold duration (ms)
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
                "Total hold time"
            )
            "PROGRESSIVE_O2" -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Hold duration"
            )
            "MIN_BREATH" -> Pair(
                { r: ApneaRecordEntity -> r.durationMs.toFloat() },
                "Total hold time"
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
