package com.example.wags.ui.morning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.repository.MorningReadinessRepository
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

/** A single (x=day-index, y=value) point for a line chart. */
data class ChartPoint(val dayIndex: Float, val value: Float, val label: String)

/** All chart series derived from the morning readiness history. */
data class MorningReadinessChartData(
    /** Overall readiness score (0–100) over time. */
    val readinessScore: List<ChartPoint> = emptyList(),
    /** Supine RMSSD (ms) over time. */
    val supineRmssd: List<ChartPoint> = emptyList(),
    /** Supine ln(RMSSD)×20 HRV score over time. */
    val supineHrvScore: List<ChartPoint> = emptyList(),
    /** Supine SDNN (ms) over time. */
    val supineSdnn: List<ChartPoint> = emptyList(),
    /** Resting heart rate (bpm) over time. */
    val restingHr: List<ChartPoint> = emptyList(),
    /** Standing RMSSD (ms) over time. */
    val standingRmssd: List<ChartPoint> = emptyList(),
    /** Standing ln(RMSSD)×20 HRV score over time. */
    val standingHrvScore: List<ChartPoint> = emptyList(),
    /** Standing SDNN (ms) over time. */
    val standingSdnn: List<ChartPoint> = emptyList(),
    /** Peak stand HR (bpm) over time. */
    val peakStandHr: List<ChartPoint> = emptyList(),
    /** 30:15 ratio over time (orthostatic). */
    val thirtyFifteenRatio: List<ChartPoint> = emptyList(),
    /** OHRR at 60 s (%) over time. */
    val ohrr60s: List<ChartPoint> = emptyList(),
    /** Respiratory rate (brpm) over time. */
    val respiratoryRate: List<ChartPoint> = emptyList(),
    /** Hooper total wellness index over time. */
    val hooperTotal: List<ChartPoint> = emptyList(),
    /** Hooper sub-scores over time (sleep, fatigue, soreness, stress). */
    val hooperSleep: List<ChartPoint> = emptyList(),
    val hooperFatigue: List<ChartPoint> = emptyList(),
    val hooperSoreness: List<ChartPoint> = emptyList(),
    val hooperStress: List<ChartPoint> = emptyList(),
)

data class MorningReadinessHistoryUiState(
    val allReadings: List<MorningReadinessEntity> = emptyList(),
    /** Set of dates that have at least one reading — used to show calendar dots. */
    val datesWithReadings: Set<LocalDate> = emptySet(),
    /** The reading selected by tapping a calendar day, null if none selected. */
    val selectedReading: MorningReadinessEntity? = null,
    val selectedDate: LocalDate? = null,
    /** Pre-computed chart series (chronological order, oldest → newest). */
    val chartData: MorningReadinessChartData = MorningReadinessChartData()
)

@HiltViewModel
class MorningReadinessHistoryViewModel @Inject constructor(
    repository: MorningReadinessRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<MorningReadinessHistoryUiState> = combine(
        repository.observeAll(),
        _selectedDate
    ) { readings, selectedDate ->
        val zone = ZoneId.systemDefault()
        val dates = readings
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .toSet()
        val selectedReading = if (selectedDate != null) {
            readings.firstOrNull { entity ->
                Instant.ofEpochMilli(entity.timestamp).atZone(zone).toLocalDate() == selectedDate
            }
        } else null

        // Build chart data from chronological order (oldest first)
        val chronological = readings.reversed()
        val chartData = buildChartData(chronological, zone)

        MorningReadinessHistoryUiState(
            allReadings = readings,
            datesWithReadings = dates,
            selectedReading = selectedReading,
            selectedDate = selectedDate,
            chartData = chartData
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MorningReadinessHistoryUiState()
    )

    /** Called when user taps a day on the calendar. */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /** Called when user dismisses the detail card. */
    fun clearSelection() {
        _selectedDate.value = null
    }

    // ── Chart builder ─────────────────────────────────────────────────────────

    private fun buildChartData(
        chronological: List<MorningReadinessEntity>,
        zone: ZoneId
    ): MorningReadinessChartData {
        if (chronological.isEmpty()) return MorningReadinessChartData()

        val readinessScore   = mutableListOf<ChartPoint>()
        val supineRmssd      = mutableListOf<ChartPoint>()
        val supineHrvScore   = mutableListOf<ChartPoint>()
        val supineSdnn       = mutableListOf<ChartPoint>()
        val restingHr        = mutableListOf<ChartPoint>()
        val standingRmssd    = mutableListOf<ChartPoint>()
        val standingHrvScore = mutableListOf<ChartPoint>()
        val standingSdnn     = mutableListOf<ChartPoint>()
        val peakStandHr      = mutableListOf<ChartPoint>()
        val thirtyFifteen    = mutableListOf<ChartPoint>()
        val ohrr60s          = mutableListOf<ChartPoint>()
        val respRate         = mutableListOf<ChartPoint>()
        val hooperTotal      = mutableListOf<ChartPoint>()
        val hooperSleep      = mutableListOf<ChartPoint>()
        val hooperFatigue    = mutableListOf<ChartPoint>()
        val hooperSoreness   = mutableListOf<ChartPoint>()
        val hooperStress     = mutableListOf<ChartPoint>()

        chronological.forEachIndexed { idx, e ->
            val x = idx.toFloat()
            val label = Instant.ofEpochMilli(e.timestamp)
                .atZone(zone).toLocalDate().toString()

            readinessScore.add(ChartPoint(x, e.readinessScore.toFloat(), label))
            supineRmssd.add(ChartPoint(x, e.supineRmssdMs.toFloat(), label))
            supineHrvScore.add(ChartPoint(x, (e.supineLnRmssd * 20).toFloat(), label))
            supineSdnn.add(ChartPoint(x, e.supineSdnnMs.toFloat(), label))
            restingHr.add(ChartPoint(x, e.supineRhr.toFloat(), label))
            // Standing metrics are null when the user skipped the standing phase —
            // omit those sessions from standing charts so they don't pollute trends.
            e.standingRmssdMs?.let { standingRmssd.add(ChartPoint(x, it.toFloat(), label)) }
            e.standingLnRmssd?.let { standingHrvScore.add(ChartPoint(x, (it * 20).toFloat(), label)) }
            e.standingSdnnMs?.let { standingSdnn.add(ChartPoint(x, it.toFloat(), label)) }
            e.peakStandHr?.let { peakStandHr.add(ChartPoint(x, it.toFloat(), label)) }
            e.thirtyFifteenRatio?.let { thirtyFifteen.add(ChartPoint(x, it, label)) }
            e.ohrrAt60sPercent?.let { ohrr60s.add(ChartPoint(x, it, label)) }
            e.respiratoryRateBpm?.let { respRate.add(ChartPoint(x, it, label)) }
            e.hooperTotal?.let { hooperTotal.add(ChartPoint(x, it, label)) }
            e.hooperSleep?.let { hooperSleep.add(ChartPoint(x, it.toFloat(), label)) }
            e.hooperFatigue?.let { hooperFatigue.add(ChartPoint(x, it.toFloat(), label)) }
            e.hooperSoreness?.let { hooperSoreness.add(ChartPoint(x, it.toFloat(), label)) }
            e.hooperStress?.let { hooperStress.add(ChartPoint(x, it.toFloat(), label)) }
        }

        return MorningReadinessChartData(
            readinessScore   = readinessScore,
            supineRmssd      = supineRmssd,
            supineHrvScore   = supineHrvScore,
            supineSdnn       = supineSdnn,
            restingHr        = restingHr,
            standingRmssd    = standingRmssd,
            standingHrvScore = standingHrvScore,
            standingSdnn     = standingSdnn,
            peakStandHr      = peakStandHr,
            thirtyFifteenRatio = thirtyFifteen,
            ohrr60s          = ohrr60s,
            respiratoryRate  = respRate,
            hooperTotal      = hooperTotal,
            hooperSleep      = hooperSleep,
            hooperFatigue    = hooperFatigue,
            hooperSoreness   = hooperSoreness,
            hooperStress     = hooperStress,
        )
    }
}
