package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.AudioSetting
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Sentinel value meaning "don't filter on this setting". */
const val FILTER_ALL = "ALL"

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

    // ── Settings tuple flow (triggers re-subscription when any setting changes) ──
    private val settingsFlow = combine(_lungVolume, _prepType, _timeOfDay, _posture, _audio) {
        lv, pt, tod, pos, aud -> Quintuple(lv, pt, tod, pos, aud)
    }

    // ── Filtered stats (react to settings changes) ────────────────────────────
    private val filteredStatsFlow = settingsFlow.flatMapLatest { (lv, pt, tod, pos, aud) ->
        apneaRepository.getStats(lv, pt, tod, pos, aud)
    }

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
        ) { allStats, allRecords, selectedDate -> Triple(allStats, allRecords, selectedDate) }
    ) { (settings, filteredStats, showAll), (allStats, allRecords, selectedDate) ->
        val (lv, pt, tod, pos, aud) = settings
        val zone = ZoneId.systemDefault()

        // Build date → records map for ALL records (calendar is not filtered by settings)
        val byDate: Map<LocalDate, List<ApneaRecordEntity>> = allRecords
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        val selectedDayRecords = if (selectedDate != null) {
            byDate[selectedDate] ?: emptyList()
        } else emptyList()

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
            allDatesWithRecords = byDate.keys,
            allRecordsByDate    = byDate,
            selectedDate        = selectedDate,
            selectedDayRecords  = selectedDayRecords,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ApneaHistoryUiState()
    )

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
}

/** Simple 5-tuple to avoid Pair-of-Pairs nesting. */
private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component1() = first
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component2() = second
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component3() = third
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component4() = fourth
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component5() = fifth
