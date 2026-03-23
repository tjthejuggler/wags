package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.RecordPbBadge
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
    val pbBadges: List<RecordPbBadge> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    /** True while the edit bottom-sheet is open. */
    val showEditSheet: Boolean = false,
    /** Editable copies of the settings fields — initialised from the record when sheet opens. */
    val editLungVolume: String = "FULL",
    val editPrepType: PrepType = PrepType.NO_PREP,
    val editTimeOfDay: TimeOfDay = TimeOfDay.DAY,
    val editPosture: Posture = Posture.LAYING,
    /** Editable device label — null means "None recorded". */
    val editHrDeviceId: String? = null,
    /** All device labels ever used, for the edit dropdown. */
    val deviceLabelOptions: List<String> = emptyList()
)

@HiltViewModel
class ApneaRecordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository,
    private val devicePrefs: DevicePreferencesRepository
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
                val badges = apneaRepository.getRecordPbBadges(recordId)
                _uiState.update {
                    it.copy(
                        record = record,
                        telemetry = telemetry,
                        pbBadges = badges,
                        isLoading = false
                    )
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
        val posture = runCatching { Posture.valueOf(record.posture) }.getOrDefault(Posture.LAYING)
        // Build the device label options: all labels from history, plus the
        // record's current label if it's not already in the list.
        val historyLabels = devicePrefs.deviceLabelHistory.toMutableList()
        record.hrDeviceId?.let { current ->
            if (current !in historyLabels) historyLabels.add(current)
        }
        _uiState.update {
            it.copy(
                showEditSheet      = true,
                editLungVolume     = record.lungVolume,
                editPrepType       = prepType,
                editTimeOfDay      = timeOfDay,
                editPosture        = posture,
                editHrDeviceId     = record.hrDeviceId,
                deviceLabelOptions = historyLabels
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

    fun setEditPosture(posture: Posture) {
        _uiState.update { it.copy(editPosture = posture) }
    }

    fun setEditHrDeviceId(deviceId: String?) {
        _uiState.update { it.copy(editHrDeviceId = deviceId) }
    }

    /** Persists the edited settings to the DB and refreshes the displayed record + PB badges. */
    fun saveEdits() {
        val record = _uiState.value.record ?: return
        val state  = _uiState.value
        viewModelScope.launch {
            val updated = record.copy(
                lungVolume = state.editLungVolume,
                prepType   = state.editPrepType.name,
                timeOfDay  = state.editTimeOfDay.name,
                posture    = state.editPosture.name,
                hrDeviceId = state.editHrDeviceId
            )
            apneaRepository.updateRecord(updated)
            val badges = apneaRepository.getRecordPbBadges(recordId)
            _uiState.update { it.copy(record = updated, pbBadges = badges, showEditSheet = false) }
        }
    }
}
