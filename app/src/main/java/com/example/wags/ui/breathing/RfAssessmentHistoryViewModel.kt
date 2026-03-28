package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.data.repository.ResonanceSessionRepository
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

data class SessionHistoryChartData(
    val coherenceRatio: List<RfChartPoint> = emptyList(),
    val rmssd: List<RfChartPoint> = emptyList(),
    val sdnn: List<RfChartPoint> = emptyList(),
    val totalPoints: List<RfChartPoint> = emptyList(),
    val duration: List<RfChartPoint> = emptyList(),
)

data class RfAssessmentHistoryUiState(
    val allAssessments: List<RfAssessmentEntity> = emptyList(),
    val allSessions: List<ResonanceSessionEntity> = emptyList(),
    val chartData: RfHistoryChartData = RfHistoryChartData(),
    val sessionChartData: SessionHistoryChartData = SessionHistoryChartData(),
    /** Set of dates that have at least one assessment — used to show calendar dots. */
    val datesWithAssessments: Set<LocalDate> = emptySet(),
    /** Set of dates that have at least one session — used to show calendar dots. */
    val datesWithSessions: Set<LocalDate> = emptySet(),
    /** Map from date → all assessments on that date (sorted newest first). */
    val assessmentsByDate: Map<LocalDate, List<RfAssessmentEntity>> = emptyMap(),
    /** Map from date → all sessions on that date (sorted newest first). */
    val sessionsByDate: Map<LocalDate, List<ResonanceSessionEntity>> = emptyMap(),
    /** Currently selected calendar date (null = nothing selected). */
    val selectedDate: LocalDate? = null,
    /** Assessments for the selected date. */
    val selectedDayAssessments: List<RfAssessmentEntity> = emptyList(),
    /** Sessions for the selected date. */
    val selectedDaySessions: List<ResonanceSessionEntity> = emptyList(),
)

@HiltViewModel
class RfAssessmentHistoryViewModel @Inject constructor(
    repository: RfAssessmentRepository,
    private val sessionRepository: ResonanceSessionRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<RfAssessmentHistoryUiState> = combine(
        repository.observeAll(),
        sessionRepository.observeAll(),
        _selectedDate
    ) { assessments, sessions, selectedDate ->
        val zone = ZoneId.systemDefault()

        // Build date → assessments map
        val assessmentsByDate: Map<LocalDate, List<RfAssessmentEntity>> = assessments
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        // Build date → sessions map
        val sessionsByDate: Map<LocalDate, List<ResonanceSessionEntity>> = sessions
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

        val assessmentDates = assessmentsByDate.keys
        val sessionDates = sessionsByDate.keys

        val selectedDayAssessments = if (selectedDate != null) {
            assessmentsByDate[selectedDate] ?: emptyList()
        } else emptyList()

        val selectedDaySessions = if (selectedDate != null) {
            sessionsByDate[selectedDate] ?: emptyList()
        } else emptyList()

        // Chronological order (oldest → newest) for charts
        val chronologicalAssessments = assessments.reversed()
        val chronologicalSessions = sessions.reversed()

        RfAssessmentHistoryUiState(
            allAssessments = assessments,
            allSessions = sessions,
            chartData = buildChartData(chronologicalAssessments, zone),
            sessionChartData = buildSessionChartData(chronologicalSessions, zone),
            datesWithAssessments = assessmentDates,
            datesWithSessions = sessionDates,
            assessmentsByDate = assessmentsByDate,
            sessionsByDate = sessionsByDate,
            selectedDate = selectedDate,
            selectedDayAssessments = selectedDayAssessments,
            selectedDaySessions = selectedDaySessions,
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

    // ── Chart builders ─────────────────────────────────────────────────────────

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

    private fun buildSessionChartData(
        chronological: List<ResonanceSessionEntity>,
        zone: ZoneId
    ): SessionHistoryChartData {
        if (chronological.isEmpty()) return SessionHistoryChartData()

        val coherenceRatio = mutableListOf<RfChartPoint>()
        val rmssd = mutableListOf<RfChartPoint>()
        val sdnn = mutableListOf<RfChartPoint>()
        val totalPoints = mutableListOf<RfChartPoint>()
        val duration = mutableListOf<RfChartPoint>()

        chronological.forEachIndexed { idx, s ->
            val x = idx.toFloat()
            val label = Instant.ofEpochMilli(s.timestamp)
                .atZone(zone).toLocalDate().toString()

            coherenceRatio.add(RfChartPoint(x, s.meanCoherenceRatio, label))
            rmssd.add(RfChartPoint(x, s.meanRmssdMs, label))
            sdnn.add(RfChartPoint(x, s.meanSdnnMs, label))
            totalPoints.add(RfChartPoint(x, s.totalPoints, label))
            duration.add(RfChartPoint(x, s.durationSeconds.toFloat() / 60f, label))
        }

        return SessionHistoryChartData(
            coherenceRatio = coherenceRatio,
            rmssd = rmssd,
            sdnn = sdnn,
            totalPoints = totalPoints,
            duration = duration,
        )
    }
}
