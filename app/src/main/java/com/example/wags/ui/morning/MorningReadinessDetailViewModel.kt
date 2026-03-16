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
    /** Set to true after the record has been successfully deleted — screen should pop. */
    val deleted: Boolean = false
)

@HiltViewModel
class MorningReadinessDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MorningReadinessRepository
) : ViewModel() {

    private val readingId: Long = checkNotNull(savedStateHandle["readingId"])

    private val _uiState = MutableStateFlow(MorningReadinessDetailUiState())
    val uiState: StateFlow<MorningReadinessDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            val entity = repository.getById(readingId)
            if (entity == null) {
                _uiState.value = MorningReadinessDetailUiState(isLoading = false, notFound = true)
            } else {
                val telemetry = repository.getTelemetry(readingId)
                _uiState.value = MorningReadinessDetailUiState(
                    reading   = entity,
                    telemetry = telemetry,
                    isLoading = false
                )
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
        viewModelScope.launch {
            repository.deleteById(readingId)   // telemetry cascade-deleted by FK
            _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
        }
    }
}
