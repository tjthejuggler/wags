package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.data.repository.RfAssessmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** A single (x=index, y=value) point for a line chart, with a date label. */
data class RfChartPoint(val index: Float, val value: Float, val dateLabel: String)

data class RfHistoryChartData(
    /** Optimal BPM over time. */
    val optimalBpm: List<RfChartPoint> = emptyList(),
    /** Coherence ratio over time. */
    val coherenceRatio: List<RfChartPoint> = emptyList(),
    /** Absolute LF power (ms²/Hz) over time. */
    val lfPower: List<RfChartPoint> = emptyList(),
    /** RMSSD (ms) over time. */
    val rmssd: List<RfChartPoint> = emptyList(),
    /** SDNN (ms) over time. */
    val sdnn: List<RfChartPoint> = emptyList(),
    /** Composite score over time. */
    val compositeScore: List<RfChartPoint> = emptyList(),
)

data class RfAssessmentHistoryUiState(
    val allAssessments: List<RfAssessmentEntity> = emptyList(),
    val chartData: RfHistoryChartData = RfHistoryChartData(),
    /** Set of dates that have at least one assessment — used to show calendar dots. */
    val datesWithAssessments: Set<LocalDate> = emptySet(),
    /** Map from date → all assessments on that date (sorted newest first). */
    val assessmentsByDate: Map<LocalDate, List<RfAssessmentEntity>> = emptyMap(),
    /** Currently selected calendar date (null = nothing selected). */
    val selectedDate: LocalDate? = null,
    /** Assessments for the selected date. */
    val selectedDayAssessments: List<RfAssessmentEntity> = emptyList(),
)

@HiltViewModel
class RfAssessmentHistoryViewModel @Inject constructor(
    repository: RfAssessmentRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<RfAssessmentHistoryUiState> = combine(
        repository.observeAll(),
        _selectedDate
    ) { assessments, selectedDate ->
        val zone = ZoneId.systemDefault()

        // Build date → assessments map
        val byDate: Map<LocalDate, List<RfAssessmentEntity>> = assessments
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        val dates = byDate.keys

        val selectedDayAssessments = if (selectedDate != null) {
            byDate[selectedDate] ?: emptyList()
        } else emptyList()

        // Chronological order (oldest → newest) for charts
        val chronological = assessments.reversed()

        RfAssessmentHistoryUiState(
            allAssessments = assessments,
            chartData = buildChartData(chronological, zone),
            datesWithAssessments = dates,
            assessmentsByDate = byDate,
            selectedDate = selectedDate,
            selectedDayAssessments = selectedDayAssessments,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RfAssessmentHistoryUiState()
    )

    /** Called when user taps a day on the calendar. */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /** Called when user dismisses the multi-session list or navigates away. */
    fun clearSelection() {
        _selectedDate.value = null
    }

    // ── Chart builder ──────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<RfAssessmentEntity>,
        zone: ZoneId
    ): RfHistoryChartData {
        if (chronological.isEmpty()) return RfHistoryChartData()

        val optimalBpm = mutableListOf<RfChartPoint>()
        val coherenceRatio = mutableListOf<RfChartPoint>()
        val lfPower = mutableListOf<RfChartPoint>()
        val rmssd = mutableListOf<RfChartPoint>()
        val sdnn = mutableListOf<RfChartPoint>()
        val compositeScore = mutableListOf<RfChartPoint>()

        chronological.forEachIndexed { idx, e ->
            val x = idx.toFloat()
            val label = Instant.ofEpochMilli(e.timestamp)
                .atZone(zone).toLocalDate().toString()

            optimalBpm.add(RfChartPoint(x, e.optimalBpm, label))
            coherenceRatio.add(RfChartPoint(x, e.maxCoherenceRatio, label))
            lfPower.add(RfChartPoint(x, e.maxLfPowerMs2, label))
            rmssd.add(RfChartPoint(x, e.meanRmssdMs, label))
            sdnn.add(RfChartPoint(x, e.meanSdnnMs, label))
            compositeScore.add(RfChartPoint(x, e.compositeScore, label))
        }

        return RfHistoryChartData(
            optimalBpm = optimalBpm,
            coherenceRatio = coherenceRatio,
            lfPower = lfPower,
            rmssd = rmssd,
            sdnn = sdnn,
            compositeScore = compositeScore,
        )
    }
}
