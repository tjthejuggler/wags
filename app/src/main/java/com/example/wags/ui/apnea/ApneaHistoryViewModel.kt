package com.example.wags.ui.apnea

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApneaHistoryUiState(
    val lungVolume: String = "FULL",
    val prepType: PrepType = PrepType.NO_PREP,
    val timeOfDay: TimeOfDay = TimeOfDay.DAY,
    val posture: Posture = Posture.LAYING,
    val audio: AudioSetting = AudioSetting.SILENCE,
    val freeHoldRecords: List<ApneaRecordEntity> = emptyList(),
    val tableSessions: List<ApneaSessionEntity> = emptyList(),
    val bestFreeHoldMs: Long = 0L,
    val isLoading: Boolean = true
)

@HiltViewModel
class ApneaHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApneaHistoryUiState())
    val uiState: StateFlow<ApneaHistoryUiState> = _uiState.asStateFlow()

    init {
        // Read the settings passed as nav args
        val lungVolume = savedStateHandle.get<String>("lungVolume") ?: "FULL"
        val prepTypeName = savedStateHandle.get<String>("prepType") ?: PrepType.NO_PREP.name
        val prepType = runCatching { PrepType.valueOf(prepTypeName) }.getOrDefault(PrepType.NO_PREP)
        val timeOfDayName = savedStateHandle.get<String>("timeOfDay") ?: TimeOfDay.DAY.name
        val timeOfDay = runCatching { TimeOfDay.valueOf(timeOfDayName) }.getOrDefault(TimeOfDay.DAY)
        val postureName = savedStateHandle.get<String>("posture") ?: Posture.LAYING.name
        val posture = runCatching { Posture.valueOf(postureName) }.getOrDefault(Posture.LAYING)
        val audioName = savedStateHandle.get<String>("audio") ?: AudioSetting.SILENCE.name
        val audio = runCatching { AudioSetting.valueOf(audioName) }.getOrDefault(AudioSetting.SILENCE)

        _uiState.update {
            it.copy(
                lungVolume = lungVolume,
                prepType   = prepType,
                timeOfDay  = timeOfDay,
                posture    = posture,
                audio      = audio
            )
        }

        // Free-hold records filtered by all five settings
        viewModelScope.launch {
            apneaRepository.getBySettings(lungVolume, prepType.name, timeOfDay.name, posture.name, audio.name).collectLatest { records ->
                _uiState.update { it.copy(freeHoldRecords = records, isLoading = false) }
            }
        }

        // Best free-hold for these settings
        viewModelScope.launch {
            apneaRepository.getBestFreeHold(lungVolume, prepType.name, timeOfDay.name, posture.name, audio.name).collectLatest { best ->
                _uiState.update { it.copy(bestFreeHoldMs = best ?: 0L) }
            }
        }

        // Table sessions — load all and filter client-side by prepType stored in tableVariant
        // (sessions don't store prepType yet; we show all sessions for now and note the filter)
        viewModelScope.launch {
            val sessions = sessionRepository.getRecentSessions(200)
            _uiState.update { it.copy(tableSessions = sessions) }
        }
    }
}
