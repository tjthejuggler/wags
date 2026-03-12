package com.example.wags.ui.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.repository.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** A single (x=index, y=value) point for a line chart, with a date label. */
data class HrvChartPoint(val index: Float, val value: Float, val dateLabel: String)

data class HrvHistoryChartData(
    /** Readiness score (0–100) over time. */
    val readinessScore: List<HrvChartPoint> = emptyList(),
    /** RMSSD (ms) over time. */
    val rmssd: List<HrvChartPoint> = emptyList(),
    /** ln(RMSSD) over time. */
    val lnRmssd: List<HrvChartPoint> = emptyList(),
    /** SDNN (ms) over time. */
    val sdnn: List<HrvChartPoint> = emptyList(),
    /** Resting HR (bpm) over time. */
    val restingHr: List<HrvChartPoint> = emptyList(),
)

data class HrvReadinessHistoryUiState(
    val allReadings: List<DailyReadingEntity> = emptyList(),
    val chartData: HrvHistoryChartData = HrvHistoryChartData()
)

@HiltViewModel
class HrvReadinessHistoryViewModel @Inject constructor(
    repository: ReadinessRepository
) : ViewModel() {

    val uiState: StateFlow<HrvReadinessHistoryUiState> = repository.observeAll()
        .map { readings ->
            // Chronological order (oldest → newest) for charts
            val chronological = readings.reversed()
            HrvReadinessHistoryUiState(
                allReadings = readings,
                chartData = buildChartData(chronological)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HrvReadinessHistoryUiState()
        )

    private fun buildChartData(chronological: List<DailyReadingEntity>): HrvHistoryChartData {
        if (chronological.isEmpty()) return HrvHistoryChartData()

        val zone = ZoneId.systemDefault()

        val readinessScore = mutableListOf<HrvChartPoint>()
        val rmssd          = mutableListOf<HrvChartPoint>()
        val lnRmssd        = mutableListOf<HrvChartPoint>()
        val sdnn           = mutableListOf<HrvChartPoint>()
        val restingHr      = mutableListOf<HrvChartPoint>()

        chronological.forEachIndexed { idx, e ->
            val x = idx.toFloat()
            val label = Instant.ofEpochMilli(e.timestamp)
                .atZone(zone).toLocalDate().toString()

            readinessScore.add(HrvChartPoint(x, e.readinessScore.toFloat(), label))
            rmssd.add(HrvChartPoint(x, e.rawRmssdMs, label))
            lnRmssd.add(HrvChartPoint(x, e.lnRmssd, label))
            sdnn.add(HrvChartPoint(x, e.sdnnMs, label))
            restingHr.add(HrvChartPoint(x, e.restingHrBpm, label))
        }

        return HrvHistoryChartData(
            readinessScore = readinessScore,
            rmssd          = rmssd,
            lnRmssd        = lnRmssd,
            sdnn           = sdnn,
            restingHr      = restingHr,
        )
    }
}
