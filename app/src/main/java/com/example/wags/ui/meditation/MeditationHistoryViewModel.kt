package com.example.wags.ui.meditation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.repository.MeditationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** null = show all postures */
typealias PostureFilter = MeditationPosture?

// ── Chart point ────────────────────────────────────────────────────────────────

data class MeditationChartPoint(val index: Float, val value: Float, val sessionId: Long, val label: String)

/** Time period filter for the graphs tab. */
enum class MeditationChartTimePeriod(val label: String, val days: Int?) {
    WEEK("7d", 7),
    MONTH("1m", 30),
    THREE_MONTHS("3m", 90),
    YEAR("1y", 365),
    ALL("All", null)
}

// ── Chart data bundle ──────────────────────────────────────────────────────────

data class MeditationChartData(
    val avgHr: List<MeditationChartPoint> = emptyList(),
    val startRmssd: List<MeditationChartPoint> = emptyList(),
    val endRmssd: List<MeditationChartPoint> = emptyList(),
    val lnRmssdSlope: List<MeditationChartPoint> = emptyList(),
    val durationMin: List<MeditationChartPoint> = emptyList()
)

// ── Tab selection ──────────────────────────────────────────────────────────────

enum class MeditationHistoryTabSelection { GRAPHS, CALENDAR }

// ── UI state ──────────────────────────────────────────────────────────────────

data class MeditationHistoryUiState(
    val allSessions: List<MeditationSessionEntity> = emptyList(),
    val audioMap: Map<Long, MeditationAudioEntity> = emptyMap(),
    val chartData: MeditationChartData = MeditationChartData(),
    val datesWithSessions: Set<LocalDate> = emptySet(),
    val selectedDate: LocalDate? = null,
    val selectedDaySessions: List<MeditationSessionEntity> = emptyList(),
    /** null = all postures; non-null = only sessions with that posture */
    val postureFilter: PostureFilter = null,
    val selectedTab: MeditationHistoryTabSelection = MeditationHistoryTabSelection.GRAPHS,
    /** Currently selected time period filter for graphs. */
    val timePeriod: MeditationChartTimePeriod = MeditationChartTimePeriod.ALL,
    /** Step offset from the present (0 = current period, -1 = one step back, etc.). */
    val periodOffset: Int = 0,
    /** Whether stepping further back is possible (no data before the window). */
    val canStepBack: Boolean = true,
    /** Whether stepping forward is possible (not already at the present). */
    val canStepForward: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

private const val KEY_SELECTED_TAB = "selected_tab"

@HiltViewModel
class MeditationHistoryViewModel @Inject constructor(
    private val repository: MeditationRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _extra = MutableStateFlow(
        ExtraState(
            selectedTab = savedStateHandle.get<String>(KEY_SELECTED_TAB)
                ?.let { runCatching { MeditationHistoryTabSelection.valueOf(it) }.getOrNull() }
                ?: MeditationHistoryTabSelection.GRAPHS
        )
    )
    private val _timePeriod = MutableStateFlow(MeditationChartTimePeriod.ALL)
    private val _periodOffset = MutableStateFlow(0)

    fun selectTab(tab: MeditationHistoryTabSelection) {
        savedStateHandle[KEY_SELECTED_TAB] = tab.name
        _extra.update { it.copy(selectedTab = tab) }
    }

    val uiState: StateFlow<MeditationHistoryUiState> = combine(
        repository.observeSessions(),
        repository.observeAudios(),
        _extra,
        _timePeriod,
        _periodOffset
    ) { sessions, audios, extra, timePeriod, periodOffset ->
        val audioMap = audios.associateBy { it.audioId }
        val zone = ZoneId.systemDefault()

        // Apply posture filter for chart data
        val filteredSessions = if (extra.postureFilter == null) sessions
            else sessions.filter { it.posture == extra.postureFilter.name }

        val datesWithSessions = sessions.map { s ->
            Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate()
        }.toSet()

        val selectedDaySessions = extra.selectedDate?.let { date ->
            sessions.filter { s ->
                Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate() == date
            }
        } ?: emptyList()

        // Build chart data from chronological order (oldest first), filtered by time period
        val chronological = filteredSessions.sortedBy { it.timestamp }
        val (periodFiltered, canBack, canFwd) = filterByPeriod(chronological, timePeriod, periodOffset, zone)
        val chartData = buildChartData(periodFiltered, zone)

        MeditationHistoryUiState(
            allSessions         = sessions,
            audioMap            = audioMap,
            chartData           = chartData,
            datesWithSessions   = datesWithSessions,
            selectedDate        = extra.selectedDate,
            selectedDaySessions = selectedDaySessions,
            postureFilter       = extra.postureFilter,
            selectedTab         = extra.selectedTab,
            timePeriod          = timePeriod,
            periodOffset        = periodOffset,
            canStepBack         = canBack,
            canStepForward      = canFwd,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeditationHistoryUiState()
    )

    fun selectDate(date: LocalDate) {
        _extra.update { it.copy(selectedDate = date) }
    }

    fun clearSelection() {
        _extra.update { it.copy(selectedDate = null) }
    }

    fun setPostureFilter(posture: PostureFilter) {
        _extra.update { it.copy(postureFilter = posture) }
    }

    /** Called when user selects a time period filter. Resets offset to 0. */
    fun setTimePeriod(period: MeditationChartTimePeriod) {
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

    // ── Filtering ─────────────────────────────────────────────────────────────

    private data class FilterResult(
        val sessions: List<MeditationSessionEntity>,
        val canStepBack: Boolean,
        val canStepForward: Boolean
    )

    private fun filterByPeriod(
        chronological: List<MeditationSessionEntity>,
        period: MeditationChartTimePeriod,
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

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<MeditationSessionEntity>,
        zone: ZoneId
    ): MeditationChartData {
        val avgHr = mutableListOf<MeditationChartPoint>()
        val startRmssd = mutableListOf<MeditationChartPoint>()
        val endRmssd = mutableListOf<MeditationChartPoint>()
        val lnSlope = mutableListOf<MeditationChartPoint>()
        val durationMin = mutableListOf<MeditationChartPoint>()

        chronological.forEachIndexed { i, s ->
            val idx = i.toFloat()
            val label = Instant.ofEpochMilli(s.timestamp)
                .atZone(zone).toLocalDate().toString()

            s.avgHrBpm?.let { avgHr.add(MeditationChartPoint(idx, it, s.sessionId, label)) }
            s.startRmssdMs?.let { startRmssd.add(MeditationChartPoint(idx, it, s.sessionId, label)) }
            s.endRmssdMs?.let { endRmssd.add(MeditationChartPoint(idx, it, s.sessionId, label)) }
            s.lnRmssdSlope?.let { lnSlope.add(MeditationChartPoint(idx, it, s.sessionId, label)) }
            durationMin.add(MeditationChartPoint(idx, s.durationMs / 60_000f, s.sessionId, label))
        }

        return MeditationChartData(
            avgHr        = avgHr,
            startRmssd   = startRmssd,
            endRmssd     = endRmssd,
            lnRmssdSlope = lnSlope,
            durationMin  = durationMin
        )
    }

    private data class ExtraState(
        val selectedDate: LocalDate? = null,
        val postureFilter: PostureFilter = null,
        val selectedTab: MeditationHistoryTabSelection = MeditationHistoryTabSelection.GRAPHS
    )
}
