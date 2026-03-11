package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApneaRecordDetailUiState(
    val record: ApneaRecordEntity? = null,
    val telemetry: List<FreeHoldTelemetryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false
)

@HiltViewModel
class ApneaRecordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository
) : ViewModel() {

    private val recordId: Long = savedStateHandle.get<Long>("recordId") ?: -1L

    private val _uiState = MutableStateFlow(ApneaRecordDetailUiState())
    val uiState: StateFlow<ApneaRecordDetailUiState> = _uiState.asStateFlow()

    /** Emits Unit when the record has been deleted — the screen should pop back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch {
            val record = apneaRepository.getById(recordId)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true) }
            } else {
                val telemetry = apneaRepository.getTelemetryForRecord(recordId)
                _uiState.update {
                    it.copy(record = record, telemetry = telemetry, isLoading = false)
                }
            }
        }
    }

    fun deleteRecord() {
        viewModelScope.launch {
            apneaRepository.deleteRecord(recordId)
            _deleted.emit(Unit)
        }
    }
}
