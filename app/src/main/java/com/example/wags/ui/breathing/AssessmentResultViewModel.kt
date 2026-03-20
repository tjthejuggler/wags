package com.example.wags.ui.breathing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.RfAssessmentEntity
import com.example.wags.data.repository.RfAssessmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssessmentResultViewModel @Inject constructor(
    private val repository: RfAssessmentRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionTimestamp: Long = savedStateHandle.get<Long>("sessionTimestamp") ?: 0L

    data class UiState(
        val currentSession: RfAssessmentEntity? = null,
        val allSessions: List<RfAssessmentEntity> = emptyList(),
        val bestSession: RfAssessmentEntity? = null,
        val selectedTab: Int = 0,
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Emits Unit when the session has been deleted — the screen should pop back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch {
            val best = repository.getBestSession()
            repository.getAllSessions().collect { sessions ->
                val current = sessions.firstOrNull { it.timestamp == sessionTimestamp }
                _uiState.value = UiState(
                    currentSession = current,
                    allSessions    = sessions,
                    bestSession    = best,
                    selectedTab    = _uiState.value.selectedTab,
                    isLoading      = false
                )
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
    }

    fun deleteSession() {
        viewModelScope.launch {
            repository.deleteByTimestamp(sessionTimestamp)
            _deleted.emit(Unit)
        }
    }
}
