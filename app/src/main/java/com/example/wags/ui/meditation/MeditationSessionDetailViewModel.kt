package com.example.wags.ui.meditation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.db.entity.MeditationTelemetryEntity
import com.example.wags.data.repository.MeditationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeditationSessionDetailUiState(
    val session: MeditationSessionEntity? = null,
    val audio: MeditationAudioEntity? = null,
    val telemetry: List<MeditationTelemetryEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** True while the delete-confirmation dialog is visible. */
    val showDeleteConfirm: Boolean = false,
    /** Set to true after the record has been successfully deleted — screen should pop. */
    val deleted: Boolean = false
)

@HiltViewModel
class MeditationSessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MeditationRepository
) : ViewModel() {

    private val sessionId: Long = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(MeditationSessionDetailUiState())
    val uiState: StateFlow<MeditationSessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session   = repository.getSessionById(sessionId)
            val audio     = session?.audioId?.let { repository.getAudioById(it) }
            val telemetry = repository.getTelemetryForSession(sessionId)
            _uiState.value = MeditationSessionDetailUiState(
                session   = session,
                audio     = audio,
                telemetry = telemetry,
                isLoading = false
            )
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
            repository.deleteSessionById(sessionId)
            _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
        }
    }
}
