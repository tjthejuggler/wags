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
    /** Set to true after the last session was deleted — screen should pop. */
    val deleted: Boolean = false,
    /** All session IDs ordered oldest-first, for swipe navigation.
     *  Swipe right (lower index) = newer; swipe left (higher index) = older. */
    val allSessionIds: List<Long> = emptyList(),
    /** Index of the currently displayed session in [allSessionIds]. */
    val currentIndex: Int = 0
)

@HiltViewModel
class MeditationSessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MeditationRepository
) : ViewModel() {

    private val initialSessionId: Long = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(MeditationSessionDetailUiState())
    val uiState: StateFlow<MeditationSessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Oldest-first: swipe right = newer (lower index), swipe left = older (higher index)
            val allSessions = repository.getAllSessions().reversed()
            val allIds = allSessions.map { it.sessionId }
            val startIndex = allIds.indexOf(initialSessionId).coerceAtLeast(0)

            val session   = repository.getSessionById(initialSessionId)
            val audio     = session?.audioId?.let { repository.getAudioById(it) }
            val telemetry = repository.getTelemetryForSession(initialSessionId)

            _uiState.value = MeditationSessionDetailUiState(
                session       = session,
                audio         = audio,
                telemetry     = telemetry,
                isLoading     = false,
                allSessionIds = allIds,
                currentIndex  = startIndex
            )
        }
    }

    /** Called when the user swipes to a different page index. */
    fun navigateToIndex(index: Int) {
        val ids = _uiState.value.allSessionIds
        if (index < 0 || index >= ids.size) return
        if (index == _uiState.value.currentIndex) return

        _uiState.update { it.copy(isLoading = true, currentIndex = index) }
        viewModelScope.launch {
            val sessionId = ids[index]
            val session   = repository.getSessionById(sessionId)
            val audio     = session?.audioId?.let { repository.getAudioById(it) }
            val telemetry = repository.getTelemetryForSession(sessionId)
            _uiState.update { it.copy(
                session   = session,
                audio     = audio,
                telemetry = telemetry,
                isLoading = false
            ) }
        }
    }

    fun requestDelete() {
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        val sessionId = _uiState.value.session?.sessionId ?: return
        val currentIds = _uiState.value.allSessionIds
        val currentIdx = _uiState.value.currentIndex

        viewModelScope.launch {
            repository.deleteSessionById(sessionId)

            val newIds = currentIds.filter { it != sessionId }
            if (newIds.isEmpty()) {
                // No sessions left — pop back to history
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
                return@launch
            }

            // Navigate to adjacent: prefer same index (now points to next older),
            // or clamp to last if we deleted the last item
            val newIndex = currentIdx.coerceAtMost(newIds.size - 1)
            val nextId = newIds[newIndex]
            val session   = repository.getSessionById(nextId)
            val audio     = session?.audioId?.let { repository.getAudioById(it) }
            val telemetry = repository.getTelemetryForSession(nextId)
            _uiState.update { it.copy(
                session       = session,
                audio         = audio,
                telemetry     = telemetry,
                isLoading     = false,
                showDeleteConfirm = false,
                allSessionIds = newIds,
                currentIndex  = newIndex
            ) }
        }
    }
}
