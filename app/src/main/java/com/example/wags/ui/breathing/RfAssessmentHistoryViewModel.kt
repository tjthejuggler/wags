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

/** Time period filter for the RF Assessment graphs tab. */
enum class RfChartTimePeriod(val label: String, val days: Int?) {
    WEEK("7d", 7),
    MONTH("1m", 30),
    THREE_MONTHS("3m", 90),
    YEAR("1y", 365),
    ALL("All", null)
}

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
    /** Currently selected time period — controls zoom level (how many points visible on screen). */
    val timePeriod: RfChartTimePeriod = RfChartTimePeriod.ALL,
)

@HiltViewModel
class RfAssessmentHistoryViewModel @Inject constructor(
    repository: RfAssessmentRepository,
    private val sessionRepository: ResonanceSessionRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val _timePeriod = MutableStateFlow(RfChartTimePeriod.ALL)

    val uiState: StateFlow<RfAssessmentHistoryUiState> = combine(
        repository.observeAll(),
        sessionRepository.observeAll(),
        _selectedDate,
        _timePeriod
    ) { assessments, sessions, selectedDate, timePeriod ->
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

        // Build chart data from ALL data (time period is now just a zoom control)
        val chronologicalAssessments = assessments.reversed()
        val chronologicalSessions = sessions.reversed()

        RfAssessmentHistoryUiState(
            allAssessments = assessments,
            allSessions = sessions,
            chartData = buildChartData(chronologicalAssessments, zone, timePeriod),
            sessionChartData = buildSessionChartData(chronologicalSessions, zone, timePeriod),
            datesWithAssessments = assessmentDates,
            datesWithSessions = sessionDates,
            assessmentsByDate = assessmentsByDate,
            sessionsByDate = sessionsByDate,
            selectedDate = selectedDate,
            selectedDayAssessments = selectedDayAssessments,
            selectedDaySessions = selectedDaySessions,
            timePeriod = timePeriod,
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

    /** Called when user selects a time period filter. */
    fun setTimePeriod(period: RfChartTimePeriod) {
        _timePeriod.value = period
    }

    // ── Chart builders ─────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<RfAssessmentEntity>,
        zone: ZoneId,
        timePeriod: RfChartTimePeriod
    ): RfHistoryChartData {
        if (chronological.isEmpty()) return RfHistoryChartData()

        // For YEAR and ALL time periods, aggregate by month
        val shouldAggregateByMonth = timePeriod == RfChartTimePeriod.YEAR || timePeriod == RfChartTimePeriod.ALL

        val optimalBpm = mutableListOf<RfChartPoint>()
        val coherenceRatio = mutableListOf<RfChartPoint>()
        val lfPower = mutableListOf<RfChartPoint>()
        val rmssd = mutableListOf<RfChartPoint>()
        val sdnn = mutableListOf<RfChartPoint>()
        val compositeScore = mutableListOf<RfChartPoint>()

        if (shouldAggregateByMonth) {
            // Group by year-month and calculate averages
            val byMonth: Map<String, List<RfAssessmentEntity>> = chronological.groupBy { entity ->
                val date = Instant.ofEpochMilli(entity.timestamp).atZone(zone).toLocalDate()
                "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
            }

            // Sort by month key (chronological order)
            val sortedMonths = byMonth.keys.sorted()
            
            sortedMonths.forEachIndexed { idx, monthKey ->
                val monthData = byMonth[monthKey]!!
                
                // Calculate averages for each metric
                val avgOptimalBpm = monthData.map { it.optimalBpm }.average().toFloat()
                val avgCoherenceRatio = monthData.map { it.maxCoherenceRatio.coerceIn(0f, 100f) }.average().toFloat()
                val avgLfPower = monthData.map { it.maxLfPowerMs2 }.average().toFloat()
                val avgRmssd = monthData.map { it.meanRmssdMs }.average().toFloat()
                val avgSdnn = monthData.map { it.meanSdnnMs }.average().toFloat()
                val avgCompositeScore = monthData.map { it.compositeScore }.average().toFloat()

                val x = idx.toFloat()
                val label = monthKey // Use "YYYY-MM" as label

                optimalBpm.add(RfChartPoint(x, avgOptimalBpm, label))
                coherenceRatio.add(RfChartPoint(x, avgCoherenceRatio, label))
                lfPower.add(RfChartPoint(x, avgLfPower, label))
                rmssd.add(RfChartPoint(x, avgRmssd, label))
                sdnn.add(RfChartPoint(x, avgSdnn, label))
                compositeScore.add(RfChartPoint(x, avgCompositeScore, label))
            }
        } else {
            // Use daily data for shorter time periods
            chronological.forEachIndexed { idx, e ->
                val x = idx.toFloat()
                val label = Instant.ofEpochMilli(e.timestamp)
                    .atZone(zone).toLocalDate().toString()

                optimalBpm.add(RfChartPoint(x, e.optimalBpm, label))
                // Clamp historical data to 100.0 to fix existing "billions" bug
                coherenceRatio.add(RfChartPoint(x, e.maxCoherenceRatio.coerceIn(0f, 100f), label))
                lfPower.add(RfChartPoint(x, e.maxLfPowerMs2, label))
                rmssd.add(RfChartPoint(x, e.meanRmssdMs, label))
                sdnn.add(RfChartPoint(x, e.meanSdnnMs, label))
                compositeScore.add(RfChartPoint(x, e.compositeScore, label))
            }
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
        zone: ZoneId,
        timePeriod: RfChartTimePeriod
    ): SessionHistoryChartData {
        if (chronological.isEmpty()) return SessionHistoryChartData()

        // For YEAR and ALL time periods, aggregate by month
        val shouldAggregateByMonth = timePeriod == RfChartTimePeriod.YEAR || timePeriod == RfChartTimePeriod.ALL

        val coherenceRatio = mutableListOf<RfChartPoint>()
        val rmssd = mutableListOf<RfChartPoint>()
        val sdnn = mutableListOf<RfChartPoint>()
        val totalPoints = mutableListOf<RfChartPoint>()
        val duration = mutableListOf<RfChartPoint>()

        if (shouldAggregateByMonth) {
            // Group by year-month and calculate averages
            val byMonth: Map<String, List<ResonanceSessionEntity>> = chronological.groupBy { entity ->
                val date = Instant.ofEpochMilli(entity.timestamp).atZone(zone).toLocalDate()
                "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
            }

            // Sort by month key (chronological order)
            val sortedMonths = byMonth.keys.sorted()
            
            sortedMonths.forEachIndexed { idx, monthKey ->
                val monthData = byMonth[monthKey]!!
                
                // Calculate averages for each metric
                val avgCoherenceRatio = monthData.map { it.meanCoherenceRatio.coerceIn(0f, 100f) }.average().toFloat()
                val avgRmssd = monthData.map { it.meanRmssdMs }.average().toFloat()
                val avgSdnn = monthData.map { it.meanSdnnMs }.average().toFloat()
                val avgTotalPoints = monthData.map { it.totalPoints }.average().toFloat()
                val avgDuration = monthData.map { it.durationSeconds.toFloat() / 60f }.average().toFloat()

                val x = idx.toFloat()
                val label = monthKey // Use "YYYY-MM" as label

                coherenceRatio.add(RfChartPoint(x, avgCoherenceRatio, label))
                rmssd.add(RfChartPoint(x, avgRmssd, label))
                sdnn.add(RfChartPoint(x, avgSdnn, label))
                totalPoints.add(RfChartPoint(x, avgTotalPoints, label))
                duration.add(RfChartPoint(x, avgDuration, label))
            }
        } else {
            // Use daily data for shorter time periods
            chronological.forEachIndexed { idx, s ->
                val x = idx.toFloat()
                val label = Instant.ofEpochMilli(s.timestamp)
                    .atZone(zone).toLocalDate().toString()

                // Clamp historical data to 100.0 to fix existing "billions" bug
                coherenceRatio.add(RfChartPoint(x, s.meanCoherenceRatio.coerceIn(0f, 100f), label))
                rmssd.add(RfChartPoint(x, s.meanRmssdMs, label))
                sdnn.add(RfChartPoint(x, s.meanSdnnMs, label))
                totalPoints.add(RfChartPoint(x, s.totalPoints, label))
                duration.add(RfChartPoint(x, s.durationSeconds.toFloat() / 60f, label))
            }
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
