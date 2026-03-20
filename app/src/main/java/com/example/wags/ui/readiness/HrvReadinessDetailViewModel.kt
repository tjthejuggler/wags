package com.example.wags.ui.readiness

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.repository.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HrvReadinessDetailUiState(
    val reading: DailyReadingEntity? = null,
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false
)

@HiltViewModel
class HrvReadinessDetailViewModel @Inject constructor(
    private val repository: ReadinessRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val readingId: Long = checkNotNull(savedStateHandle["readingId"])

    private val _uiState = MutableStateFlow(HrvReadinessDetailUiState())
    val uiState: StateFlow<HrvReadinessDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            val reading = repository.getById(readingId)
            _uiState.value = HrvReadinessDetailUiState(reading = reading, isLoading = false)
        }
    }

    fun deleteReading() {
        viewModelScope.launch {
            repository.deleteReading(readingId)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }
}
