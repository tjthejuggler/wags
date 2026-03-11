package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.repository.RfAssessmentRepository
import com.example.wags.domain.usecase.breathing.RfProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssessmentPickerViewModel @Inject constructor(
    private val repository: RfAssessmentRepository
) : ViewModel() {

    data class UiState(
        val selectedProtocol: RfProtocol = RfProtocol.EXPRESS,
        val targetedEnabled: Boolean = false,
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
