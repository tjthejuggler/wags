package com.example.wags.ui.morning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.db.entity.MorningReadinessTelemetryEntity
import com.example.wags.data.repository.MorningReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MorningReadinessDetailUiState(
    val reading: MorningReadinessEntity? = null,
    val telemetry: List<MorningReadinessTelemetryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    /** True while the delete-confirmation dialog is visible. */
    val showDeleteConfirm: Boolean = false,
    /** Set to true after the last record was deleted — screen should pop. */
    val deleted: Boolean = false,
    /** All reading IDs ordered oldest-first, for swipe navigation.
     *  Swipe right (lower index) = newer; swipe left (higher index) = older. */
    val allReadingIds: List<Long> = emptyList(),
    /** Index of the currently displayed reading in [allReadingIds]. */
    val currentIndex: Int = 0
)

@HiltViewModel
class MorningReadinessDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MorningReadinessRepository
) : ViewModel() {

    private val initialReadingId: Long = checkNotNull(savedStateHandle["readingId"])

    private val _uiState = MutableStateFlow(MorningReadinessDetailUiState())
    val uiState: StateFlow<MorningReadinessDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            // Oldest-first: swipe right = newer (lower index), swipe left = older (higher index)
            // Note: MorningReadinessEntity primary key is `id`
            val allReadings = repository.getAll().reversed()
            val allIds = allReadings.map { it.id }
            val startIndex = allIds.indexOf(initialReadingId).coerceAtLeast(0)

            val entity = repository.getById(initialReadingId)
            if (entity == null) {
                _uiState.value = MorningReadinessDetailUiState(
                    isLoading     = false,
                    notFound      = true,
                    allReadingIds = allIds,
                    currentIndex  = startIndex
                )
            } else {
                val telemetry = repository.getTelemetry(initialReadingId)
                _uiState.value = MorningReadinessDetailUiState(
                    reading       = entity,
                    telemetry     = telemetry,
                    isLoading     = false,
                    allReadingIds = allIds,
                    currentIndex  = startIndex
                )
            }
        }
    }

    /** Called when the user swipes to a different page index. */
    fun navigateToIndex(index: Int) {
        val ids = _uiState.value.allReadingIds
        if (index < 0 || index >= ids.size) return
        if (index == _uiState.value.currentIndex) return

        _uiState.update { it.copy(currentIndex = index) }
        viewModelScope.launch {
            val readingId = ids[index]
            val entity = repository.getById(readingId)
            if (entity == null) {
                _uiState.update { it.copy(notFound = true) }
            } else {
                val telemetry = repository.getTelemetry(readingId)
                _uiState.update { it.copy(reading = entity, telemetry = telemetry) }
            }
        }
    }

    fun requestDelete() {
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        val readingId = _uiState.value.reading?.id ?: return
        val currentIds = _uiState.value.allReadingIds
        val currentIdx = _uiState.value.currentIndex

        viewModelScope.launch {
            repository.deleteById(readingId)   // telemetry cascade-deleted by FK

            val newIds = currentIds.filter { it != readingId }
            if (newIds.isEmpty()) {
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
                return@launch
            }

            val newIndex = currentIdx.coerceAtMost(newIds.size - 1)
            val entity = repository.getById(newIds[newIndex])
            val telemetry = if (entity != null) repository.getTelemetry(newIds[newIndex]) else emptyList()
            _uiState.update { it.copy(
                reading       = entity,
                telemetry     = telemetry,
                isLoading     = false,
                showDeleteConfirm = false,
                allReadingIds = newIds,
                currentIndex  = newIndex
            ) }
        }
    }
}
