package com.example.wags.ui.breathing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.data.repository.ResonanceSessionRepository
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

@HiltViewModel
class ResonanceSessionDetailViewModel @Inject constructor(
    private val repository: ResonanceSessionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialSessionId: Long = savedStateHandle.get<Long>("sessionId") ?: 0L

    data class UiState(
        val session: ResonanceSessionEntity? = null,
        val isLoading: Boolean = true,
        /** All session IDs ordered oldest-first, for swipe navigation.
         *  Swipe right (lower index) = newer; swipe left (higher index) = older. */
        val allSessionIds: List<Long> = emptyList(),
        /** Index of the currently displayed session in [allSessionIds]. */
        val currentIndex: Int = 0
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Emits Unit when the last session has been deleted — the screen should pop back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch {
            // Oldest-first: swipe right = newer (lower index), swipe left = older (higher index)
            val allSessions = repository.getAll().reversed()
            val allIds = allSessions.map { it.sessionId }
            val startIndex = allIds.indexOf(initialSessionId).coerceAtLeast(0)
            val session = repository.getById(initialSessionId)
            _uiState.value = UiState(
                session       = session,
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
            val session = repository.getById(ids[index])
            _uiState.update { it.copy(session = session, isLoading = false) }
        }
    }

    fun deleteSession() {
        val sessionId = _uiState.value.session?.sessionId ?: return
        val currentIds = _uiState.value.allSessionIds
        val currentIdx = _uiState.value.currentIndex

        viewModelScope.launch {
            repository.deleteById(sessionId)

            val newIds = currentIds.filter { it != sessionId }
            if (newIds.isEmpty()) {
                _deleted.emit(Unit)
                return@launch
            }

            val newIndex = currentIdx.coerceAtMost(newIds.size - 1)
            val session = repository.getById(newIds[newIndex])
            _uiState.update { it.copy(
                session       = session,
                isLoading     = false,
                allSessionIds = newIds,
                currentIndex  = newIndex
            ) }
        }
    }
}
