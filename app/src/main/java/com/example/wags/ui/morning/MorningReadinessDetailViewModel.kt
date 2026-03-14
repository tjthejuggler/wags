package com.example.wags.ui.morning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.repository.MorningReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MorningReadinessDetailUiState(
    val reading: MorningReadinessEntity? = null,
    val isLoading: Boolean = true,
    val notFound: Boolean = false
)

@HiltViewModel
class MorningReadinessDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MorningReadinessRepository
) : ViewModel() {

    private val readingId: Long = checkNotNull(savedStateHandle["readingId"])

    private val _uiState = MutableStateFlow(MorningReadinessDetailUiState())
    val uiState: StateFlow<MorningReadinessDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            val entity = repository.getById(readingId)
            _uiState.value = if (entity != null) {
                MorningReadinessDetailUiState(reading = entity, isLoading = false)
            } else {
                MorningReadinessDetailUiState(isLoading = false, notFound = true)
            }
        }
    }
}
