package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

/** A single data point for the PB chart. */
data class PbChartPoint(
    val recordId: Long,
    val timestampMs: Long,
    val durationMs: Long
)

data class PbChartUiState(
    val label: String = "",
    val allPoints: List<PbChartPoint> = emptyList(),
    val pbOnlyPoints: List<PbChartPoint> = emptyList(),
    val showPbOnly: Boolean = false,
    val isLoading: Boolean = true
)

@HiltViewModel
class PbChartViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository
) : ViewModel() {

    private val lungVolume: String = savedStateHandle["lungVolume"] ?: ""
    private val prepType: String = savedStateHandle["prepType"] ?: ""
    private val timeOfDay: String = savedStateHandle["timeOfDay"] ?: ""
    private val posture: String = savedStateHandle["posture"] ?: ""
    private val audio: String = savedStateHandle["audio"] ?: ""
    private val labelArg: String = runCatching {
        URLDecoder.decode(savedStateHandle["label"] ?: "All settings", "UTF-8")
    }.getOrDefault("All settings")

    private val _uiState = MutableStateFlow(PbChartUiState(label = labelArg))
    val uiState: StateFlow<PbChartUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val records = apneaRepository.getAllFreeHoldsForChart(
                lungVolume, prepType, timeOfDay, posture, audio
            )
            val allPoints = records.map { PbChartPoint(it.recordId, it.timestamp, it.durationMs) }
            val pbOnlyPoints = computePbOnly(records)
            _uiState.update {
                it.copy(
                    allPoints = allPoints,
                    pbOnlyPoints = pbOnlyPoints,
                    isLoading = false
                )
            }
        }
    }

    fun togglePbOnly() {
        _uiState.update { it.copy(showPbOnly = !it.showPbOnly) }
    }

    /**
     * Computes the subset of records that were a new personal best at the time
     * they happened (for the given setting filters). Records are already sorted
     * by timestamp ascending from the DAO.
     */
    private fun computePbOnly(records: List<ApneaRecordEntity>): List<PbChartPoint> {
        val result = mutableListOf<PbChartPoint>()
        var runningMax = Long.MIN_VALUE
        for (r in records) {
            if (r.durationMs > runningMax) {
                runningMax = r.durationMs
                result.add(PbChartPoint(r.recordId, r.timestamp, r.durationMs))
            }
        }
        return result
    }
}
