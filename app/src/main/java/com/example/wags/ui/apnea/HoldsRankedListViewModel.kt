package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Metric by which holds can be ranked in the Stats tab drill-down list.
 *
 * Mirrors the "extremes" rows on the Stats tab (overall / start / end HR & SpO₂).
 * Values follow the same physiological bounds as the stats SQL queries:
 * HR 20–250 bpm, SpO₂ 1–100 % (0 = no-signal artefact).
 */
enum class RankedHoldMetric(
    val key: String,
    val title: String,
    val unit: String,
    /** true = lowest first, false = highest first. */
    val ascending: Boolean,
    /** true = value comes from the first/last telemetry sample, not the record row. */
    val fromTelemetry: Boolean
) {
    MAX_HR("MAX_HR", "Highest HR", "bpm", ascending = false, fromTelemetry = false),
    MIN_HR("MIN_HR", "Lowest HR", "bpm", ascending = true, fromTelemetry = false),
    LOWEST_SPO2("LOWEST_SPO2", "Lowest SpO₂", "%", ascending = true, fromTelemetry = false),
    MAX_START_HR("MAX_START_HR", "Highest HR at start", "bpm", ascending = false, fromTelemetry = true),
    MIN_START_HR("MIN_START_HR", "Lowest HR at start", "bpm", ascending = true, fromTelemetry = true),
    MAX_START_SPO2("MAX_START_SPO2", "Highest SpO₂ at start", "%", ascending = false, fromTelemetry = true),
    MIN_START_SPO2("MIN_START_SPO2", "Lowest SpO₂ at start", "%", ascending = true, fromTelemetry = true),
    MAX_END_HR("MAX_END_HR", "Highest HR at end", "bpm", ascending = false, fromTelemetry = true),
    MIN_END_HR("MIN_END_HR", "Lowest HR at end", "bpm", ascending = true, fromTelemetry = true),
    MAX_END_SPO2("MAX_END_SPO2", "Highest SpO₂ at end", "%", ascending = false, fromTelemetry = true),
    MIN_END_SPO2("MIN_END_SPO2", "Lowest SpO₂ at end", "%", ascending = true, fromTelemetry = true);

    companion object {
        fun fromKey(key: String?): RankedHoldMetric? = entries.find { it.key == key }
    }
}

/** A single hold card in the ranked list. */
data class RankedHoldItem(
    val rank: Int,
    val recordId: Long,
    val metricValue: Float,
    val timestamp: Long,
    val durationMs: Long,
    val drillLabel: String,
    val minHrBpm: Float?,
    val maxHrBpm: Float?,
    val lowestSpO2: Int?
)

data class HoldsRankedListUiState(
    val title: String = "",
    val unit: String = "",
    val ascending: Boolean = true,
    val isLoading: Boolean = true,
    val items: List<RankedHoldItem> = emptyList()
)

/**
 * Drill-down from a Stats tab extremes label: shows every hold that has a value
 * for the clicked metric, sorted best-first. Respects the filter settings that
 * were active on the Stats tab (or ranks all records when "All settings" was on).
 */
@HiltViewModel
class HoldsRankedListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository
) : ViewModel() {

    private val metric = RankedHoldMetric.fromKey(savedStateHandle.get<String>("metricKey"))
        ?: RankedHoldMetric.LOWEST_SPO2
    private val lungVolume = savedStateHandle.get<String>("lungVolume") ?: FILTER_ALL
    private val prepType = savedStateHandle.get<String>("prepType") ?: FILTER_ALL
    private val timeOfDay = savedStateHandle.get<String>("timeOfDay") ?: FILTER_ALL
    private val posture = savedStateHandle.get<String>("posture") ?: FILTER_ALL
    private val audio = savedStateHandle.get<String>("audio") ?: FILTER_ALL
    private val showAll = savedStateHandle.get<Boolean>("showAll") ?: false

    private val _uiState = MutableStateFlow(
        HoldsRankedListUiState(title = metric.title, unit = metric.unit, ascending = metric.ascending)
    )
    val uiState: StateFlow<HoldsRankedListUiState> = _uiState

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val records = apneaRepository.getAllRecordsOnce()

        val filtered = if (showAll) records else records.filter { r ->
            (lungVolume == FILTER_ALL || r.lungVolume == lungVolume) &&
            (prepType == FILTER_ALL || r.prepType == prepType) &&
            (timeOfDay == FILTER_ALL || r.timeOfDay == timeOfDay) &&
            (posture == FILTER_ALL || r.posture == posture) &&
            (audio == FILTER_ALL || r.audio == audio)
        }

        // Start/end metrics need the first/last telemetry sample per record.
        val firstByRecord: Map<Long, FreeHoldTelemetryEntity> =
            if (metric.fromTelemetry) apneaRepository.getFirstTelemetrySamplesOnce() else emptyMap()
        val lastByRecord: Map<Long, FreeHoldTelemetryEntity> =
            if (metric.fromTelemetry) apneaRepository.getLastTelemetrySamplesOnce() else emptyMap()

        data class Entry(val record: ApneaRecordEntity, val value: Float)

        val entries = filtered.mapNotNull { r ->
            val v = when (metric) {
                RankedHoldMetric.MAX_HR ->
                    r.maxHrBpm.takeIf { it in 20f..250f }
                RankedHoldMetric.MIN_HR ->
                    r.minHrBpm.takeIf { it in 20f..250f }
                RankedHoldMetric.LOWEST_SPO2 ->
                    r.lowestSpO2?.takeIf { it in 1..100 }?.toFloat()
                RankedHoldMetric.MAX_START_HR ->
                    firstByRecord[r.recordId]?.heartRateBpm?.takeIf { it in 20..250 }?.toFloat()
                RankedHoldMetric.MIN_START_HR ->
                    firstByRecord[r.recordId]?.heartRateBpm?.takeIf { it in 20..250 }?.toFloat()
                RankedHoldMetric.MAX_START_SPO2 ->
                    firstByRecord[r.recordId]?.spO2?.takeIf { it in 1..100 }?.toFloat()
                RankedHoldMetric.MIN_START_SPO2 ->
                    firstByRecord[r.recordId]?.spO2?.takeIf { it in 1..100 }?.toFloat()
                RankedHoldMetric.MAX_END_HR ->
                    lastByRecord[r.recordId]?.heartRateBpm?.takeIf { it in 20..250 }?.toFloat()
                RankedHoldMetric.MIN_END_HR ->
                    lastByRecord[r.recordId]?.heartRateBpm?.takeIf { it in 20..250 }?.toFloat()
                RankedHoldMetric.MAX_END_SPO2 ->
                    lastByRecord[r.recordId]?.spO2?.takeIf { it in 1..100 }?.toFloat()
                RankedHoldMetric.MIN_END_SPO2 ->
                    lastByRecord[r.recordId]?.spO2?.takeIf { it in 1..100 }?.toFloat()
            } ?: return@mapNotNull null
            Entry(r, v)
        }

        val sorted = if (metric.ascending) entries.sortedBy { it.value }
                     else entries.sortedByDescending { it.value }

        val items = sorted.mapIndexed { idx, e ->
            RankedHoldItem(
                rank = idx + 1,
                recordId = e.record.recordId,
                metricValue = e.value,
                timestamp = e.record.timestamp,
                durationMs = e.record.durationMs,
                drillLabel = drillLabel(e.record.tableType),
                minHrBpm = e.record.minHrBpm.takeIf { it > 0f },
                maxHrBpm = e.record.maxHrBpm.takeIf { it > 0f },
                lowestSpO2 = e.record.lowestSpO2
            )
        }

        _uiState.value = _uiState.value.copy(isLoading = false, items = items)
    }
}

/** Display label for a record's drill type (matches the record detail screen). */
private fun drillLabel(tableType: String?): String = when (tableType) {
    null                      -> "Free Hold"
    "O2"                      -> "O₂ Table"
    "CO2"                     -> "CO₂ Table"
    "PROGRESSIVE_O2"          -> "Progressive O₂"
    "MIN_BREATH"              -> "Min Breath"
    "WONKA_FIRST_CONTRACTION" -> "Till Contraction"
    "WONKA_ENDURANCE"         -> "Contraction Count"
    else                      -> tableType
}
