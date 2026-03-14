package com.example.wags.ui.meditation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.repository.MeditationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeditationSessionDetailUiState(
    val session: MeditationSessionEntity? = null,
    val audio: MeditationAudioEntity? = null,
    val isLoading: Boolean = true
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
            val session = repository.getSessionById(sessionId)
            val audio = session?.audioId?.let { repository.getAudioById(it) }
            _uiState.value = MeditationSessionDetailUiState(
                session = session,
                audio = audio,
                isLoading = false
            )
        }
    }
}
