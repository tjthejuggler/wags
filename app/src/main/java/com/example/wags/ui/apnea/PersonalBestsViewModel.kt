package com.example.wags.ui.apnea

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.PersonalBestEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PersonalBestsUiState(
    val entries: List<PersonalBestEntry> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class PersonalBestsViewModel @Inject constructor(
    private val apneaRepository: ApneaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonalBestsUiState())
    val uiState: StateFlow<PersonalBestsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val entries = apneaRepository.getAllPersonalBests()
            _uiState.update { it.copy(entries = entries, isLoading = false) }
        }
    }
}
