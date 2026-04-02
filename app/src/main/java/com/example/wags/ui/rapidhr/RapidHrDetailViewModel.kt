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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RapidHrDetailUiState(
    val session: RapidHrSessionEntity? = null,
    val telemetry: List<RapidHrTelemetryEntity> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class RapidHrDetailViewModel @Inject constructor(
    private val repository: RapidHrRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: Long = checkNotNull(savedStateHandle["sessionId"])

    private val _state = MutableStateFlow(RapidHrDetailUiState())
    val uiState: StateFlow<RapidHrDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val session = repository.getSessionById(sessionId)
            val telemetry = repository.getTelemetryForSession(sessionId)
            _state.value = RapidHrDetailUiState(
                session = session,
                telemetry = telemetry,
                isLoading = false
            )
        }
    }
}
