package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.repository.RfAssessmentRepository
import com.example.wags.domain.usecase.breathing.RfProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssessmentPickerViewModel @Inject constructor(
    private val repository: RfAssessmentRepository,
    private val hrDataSource: HrDataSource
) : ViewModel() {

    data class UiState(
        val selectedProtocol: RfProtocol = RfProtocol.EXPRESS,
        val targetedEnabled: Boolean = false,
        val isLoading: Boolean = true,
        val isHrDeviceConnected: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = combine(
        _uiState,
        hrDataSource.isAnyHrDeviceConnected
    ) { state, hrConnected ->
        state.copy(isHrDeviceConnected = hrConnected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState()
    )

    init {
        viewModelScope.launch {
            val hasSession = repository.hasAnySession()
            _uiState.update { it.copy(targetedEnabled = hasSession, isLoading = false) }
        }
    }

    fun selectProtocol(protocol: RfProtocol) {
        // Guard: don't allow selecting TARGETED if not enabled
        if (protocol == RfProtocol.TARGETED && !_uiState.value.targetedEnabled) return
        _uiState.update { it.copy(selectedProtocol = protocol) }
    }
}
