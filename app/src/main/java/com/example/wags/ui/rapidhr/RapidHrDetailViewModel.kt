package com.example.wags.ui.rapidhr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.RapidHrSessionEntity
import com.example.wags.data.db.entity.RapidHrTelemetryEntity
import com.example.wags.data.repository.RapidHrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RapidHrDetailUiState(
    val session: RapidHrSessionEntity? = null,
    val telemetry: List<RapidHrTelemetryEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** All session IDs ordered oldest-first, for swipe navigation.
     *  Swipe right (lower index) = newer; swipe left (higher index) = older. */
    val allSessionIds: List<Long> = emptyList(),
    /** Index of the currently displayed session in [allSessionIds]. */
    val currentIndex: Int = 0
)

@HiltViewModel
class RapidHrDetailViewModel @Inject constructor(
    private val repository: RapidHrRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialSessionId: Long = checkNotNull(savedStateHandle["sessionId"])

    private val _state = MutableStateFlow(RapidHrDetailUiState())
    val uiState: StateFlow<RapidHrDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Oldest-first: swipe right = newer (lower index), swipe left = older (higher index)
            val allSessions = repository.getAllSessions().reversed()
            val allIds = allSessions.map { it.id }
            val startIndex = allIds.indexOf(initialSessionId).coerceAtLeast(0)
            val session = repository.getSessionById(initialSessionId)
            val telemetry = repository.getTelemetryForSession(initialSessionId)
            _state.value = RapidHrDetailUiState(
                session       = session,
                telemetry     = telemetry,
                isLoading     = false,
                allSessionIds = allIds,
                currentIndex  = startIndex
            )
        }
    }

    /** Called when the user swipes to a different page index. */
    fun navigateToIndex(index: Int) {
        val ids = _state.value.allSessionIds
        if (index < 0 || index >= ids.size) return
        if (index == _state.value.currentIndex) return

        _state.update { it.copy(isLoading = true, currentIndex = index) }
        viewModelScope.launch {
            val sessionId = ids[index]
            val session = repository.getSessionById(sessionId)
            val telemetry = repository.getTelemetryForSession(sessionId)
            _state.update { it.copy(session = session, telemetry = telemetry, isLoading = false) }
        }
    }
}
