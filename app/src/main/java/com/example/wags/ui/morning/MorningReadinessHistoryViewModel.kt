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

data class MorningReadinessHistoryUiState(
    val allReadings: List<MorningReadinessEntity> = emptyList(),
    /** Set of dates that have at least one reading — used to show calendar dots. */
    val datesWithReadings: Set<LocalDate> = emptySet(),
    /** The reading selected by tapping a calendar day, null if none selected. */
    val selectedReading: MorningReadinessEntity? = null,
    val selectedDate: LocalDate? = null
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
        MorningReadinessHistoryUiState(
            allReadings = readings,
            datesWithReadings = dates,
            selectedReading = selectedReading,
            selectedDate = selectedDate
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
}
