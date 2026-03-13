package com.example.wags.ui.apnea

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.model.WonkaConfig
import com.example.wags.domain.usecase.apnea.AdvancedApneaPhase
import com.example.wags.domain.usecase.apnea.AdvancedApneaState
import com.example.wags.domain.usecase.apnea.AdvancedApneaStateMachine
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.ProgressiveO2Generator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AdvancedApneaViewModel @Inject constructor(
    private val progressiveO2Generator: ProgressiveO2Generator,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val stateMachine: AdvancedApneaStateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val hrDataSource: HrDataSource,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    val state: StateFlow<AdvancedApneaState> = stateMachine.state

    private var modality: TrainingModality = TrainingModality.PROGRESSIVE_O2
    private var length: TableLength = TableLength.MEDIUM
    private var wonkaConfig: WonkaConfig = WonkaConfig()
    private var sessionStartMs: Long = 0L

    init {
        viewModelScope.launch {
            stateMachine.state.collect { advancedState ->
                if (advancedState.phase == AdvancedApneaPhase.COMPLETE) {
                    saveCompletedSession(advancedState)
                }
            }
        }
    }

    fun startSession(
        modality: TrainingModality,
        length: TableLength,
        wonkaConfig: WonkaConfig = WonkaConfig()
    ) {
        this.modality = modality
        this.length = length
        this.wonkaConfig = wonkaConfig
        sessionStartMs = System.currentTimeMillis()
        val pbMs = prefs.getLong("pb_ms", 0L)
        stateMachine.start(modality, length, pbMs, wonkaConfig, viewModelScope)
    }

    fun signalBreathTaken() {
        stateMachine.signalBreathTaken()
        audioHapticEngine.announceHoldBegin()
    }

    fun signalFirstContraction() {
        stateMachine.signalFirstContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    fun stopSession() = stateMachine.stop()

    private fun saveCompletedSession(advancedState: AdvancedApneaState) {
        viewModelScope.launch {
            val pbMs = prefs.getLong("pb_ms", 0L)
            val totalDurationMs = System.currentTimeMillis() - sessionStartMs
            val entity = ApneaSessionEntity(
                timestamp = System.currentTimeMillis(),
                tableType = modality.name,
                tableVariant = length.name,
                tableParamsJson = "{}",
                pbAtSessionMs = pbMs,
                totalSessionDurationMs = totalDurationMs,
                contractionTimestampsJson = "[]",
                maxHrBpm = null,
                lowestSpO2 = null,
                roundsCompleted = advancedState.currentRound,
                totalRounds = advancedState.totalRounds,
                hrDeviceId = hrDataSource.activeHrDeviceLabel()
            )
            sessionRepository.saveSession(entity)
        }
    }

    override fun onCleared() {
        stateMachine.stop()
        super.onCleared()
    }
}
