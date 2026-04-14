package com.example.wags.ui.readiness

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.repository.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HrvReadinessDetailUiState(
    val reading: DailyReadingEntity? = null,
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
    /** All reading IDs ordered oldest-first, for swipe navigation.
     *  Swipe right (lower index) = newer; swipe left (higher index) = older. */
    val allReadingIds: List<Long> = emptyList(),
    /** Index of the currently displayed reading in [allReadingIds]. */
    val currentIndex: Int = 0
)

@HiltViewModel
class HrvReadinessDetailViewModel @Inject constructor(
    private val repository: ReadinessRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialReadingId: Long = checkNotNull(savedStateHandle["readingId"])

    private val _uiState = MutableStateFlow(HrvReadinessDetailUiState())
    val uiState: StateFlow<HrvReadinessDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Oldest-first: swipe right = newer (lower index), swipe left = older (higher index)
            val allReadings = repository.getAll().reversed()
            val allIds = allReadings.map { it.readingId }
            val startIndex = allIds.indexOf(initialReadingId).coerceAtLeast(0)
            val reading = repository.getById(initialReadingId)
            _uiState.value = HrvReadinessDetailUiState(
                reading      = reading,
                isLoading    = false,
                allReadingIds = allIds,
                currentIndex  = startIndex
            )
        }
    }

    /** Called when the user swipes to a different page index. */
    fun navigateToIndex(index: Int) {
        val ids = _uiState.value.allReadingIds
        if (index < 0 || index >= ids.size) return
        if (index == _uiState.value.currentIndex) return

        _uiState.update { it.copy(isLoading = true, currentIndex = index) }
        viewModelScope.launch {
            val reading = repository.getById(ids[index])
            _uiState.update { it.copy(reading = reading, isLoading = false) }
        }
    }

    fun deleteReading() {
        val readingId = _uiState.value.reading?.readingId ?: return
        val currentIds = _uiState.value.allReadingIds
        val currentIdx = _uiState.value.currentIndex

        viewModelScope.launch {
            repository.deleteReading(readingId)

            val newIds = currentIds.filter { it != readingId }
            if (newIds.isEmpty()) {
                _uiState.update { it.copy(isDeleted = true) }
                return@launch
            }

            val newIndex = currentIdx.coerceAtMost(newIds.size - 1)
            val reading = repository.getById(newIds[newIndex])
            _uiState.update { it.copy(
                reading      = reading,
                isLoading    = false,
                allReadingIds = newIds,
                currentIndex  = newIndex
            ) }
        }
    }
}
