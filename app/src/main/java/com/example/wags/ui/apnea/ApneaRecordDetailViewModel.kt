package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
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
    val notFound: Boolean = false,
    /** True while the edit bottom-sheet is open. */
    val showEditSheet: Boolean = false,
    /** Editable copies of the three settings fields — initialised from the record when sheet opens. */
    val editLungVolume: String = "FULL",
    val editPrepType: PrepType = PrepType.NO_PREP,
    val editTimeOfDay: TimeOfDay = TimeOfDay.DAY
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

    // ── Edit sheet ────────────────────────────────────────────────────────────

    /** Opens the edit sheet, pre-populating fields from the current record. */
    fun openEditSheet() {
        val record = _uiState.value.record ?: return
        val prepType = runCatching { PrepType.valueOf(record.prepType) }.getOrDefault(PrepType.NO_PREP)
        val timeOfDay = runCatching { TimeOfDay.valueOf(record.timeOfDay) }.getOrDefault(TimeOfDay.DAY)
        _uiState.update {
            it.copy(
                showEditSheet  = true,
                editLungVolume = record.lungVolume,
                editPrepType   = prepType,
                editTimeOfDay  = timeOfDay
            )
        }
    }

    fun closeEditSheet() {
        _uiState.update { it.copy(showEditSheet = false) }
    }

    fun setEditLungVolume(volume: String) {
        _uiState.update { it.copy(editLungVolume = volume) }
    }

    fun setEditPrepType(type: PrepType) {
        _uiState.update { it.copy(editPrepType = type) }
    }

    fun setEditTimeOfDay(tod: TimeOfDay) {
        _uiState.update { it.copy(editTimeOfDay = tod) }
    }

    /** Persists the edited settings to the DB and refreshes the displayed record. */
    fun saveEdits() {
        val record = _uiState.value.record ?: return
        val state  = _uiState.value
        viewModelScope.launch {
            val updated = record.copy(
                lungVolume = state.editLungVolume,
                prepType   = state.editPrepType.name,
                timeOfDay  = state.editTimeOfDay.name
            )
            apneaRepository.updateRecord(updated)
            _uiState.update { it.copy(record = updated, showEditSheet = false) }
        }
    }
}
