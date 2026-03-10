package com.example.wags.ui.apnea

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.usecase.apnea.ApneaState
import com.example.wags.domain.usecase.apnea.ApneaStateMachine
import com.example.wags.domain.usecase.apnea.ApneaTableGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApneaUiState(
    val apneaState: ApneaState = ApneaState.IDLE,
    val currentRound: Int = 0,
    val totalRounds: Int = 0,
    val remainingSeconds: Long = 0L,
    val currentTable: ApneaTable? = null,
    val personalBestMs: Long = 0L,
    val recentRecords: List<ApneaRecordEntity> = emptyList(),
    val freeHoldActive: Boolean = false,
    val freeHoldDurationMs: Long = 0L,
    val selectedLungVolume: String = "FULL",
    val hyperventilationPrep: Boolean = false
)

@HiltViewModel
class ApneaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: PolarBleManager,
    private val apneaRepository: ApneaRepository,
    private val tableGenerator: ApneaTableGenerator,
    private val stateMachine: ApneaStateMachine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApneaUiState())
    val uiState: StateFlow<ApneaUiState> = _uiState.asStateFlow()

    private var soundPool: SoundPool? = null
    private var freeHoldStartTime = 0L

    init {
        initSoundPool()
        viewModelScope.launch {
            apneaRepository.getLatestRecords(20).collect { records ->
                _uiState.update { it.copy(recentRecords = records) }
            }
        }
        viewModelScope.launch {
            stateMachine.state.collect { state ->
                _uiState.update { it.copy(apneaState = state) }
            }
        }
        viewModelScope.launch {
            stateMachine.currentRound.collect { round ->
                _uiState.update { it.copy(currentRound = round) }
            }
        }
        viewModelScope.launch {
            stateMachine.remainingSeconds.collect { secs ->
                _uiState.update { it.copy(remainingSeconds = secs) }
            }
        }
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
    }

    fun setPersonalBest(pbMs: Long) {
        _uiState.update { it.copy(personalBestMs = pbMs) }
    }

    fun loadTable(type: ApneaTableType) {
        val pb = _uiState.value.personalBestMs
        if (pb <= 0) return
        if (type == ApneaTableType.FREE) return
        val table = when (type) {
            ApneaTableType.O2 -> tableGenerator.generateO2Table(pb)
            ApneaTableType.CO2 -> tableGenerator.generateCo2Table(pb)
            ApneaTableType.FREE -> return
        }
        stateMachine.load(table)
        _uiState.update {
            it.copy(
                currentTable = table,
                totalRounds = table.steps.size
            )
        }
    }

    fun startTableSession(deviceId: String) {
        bleManager.startRrStream(deviceId)
        stateMachine.setCallbacks(
            onWarning = { secs -> triggerWarning(secs) },
            onStateChange = { /* state collected via flow in init */ }
        )
        stateMachine.start(viewModelScope)
    }

    fun stopTableSession() = stateMachine.stop()

    private fun triggerWarning(remainingSeconds: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val duration = if (remainingSeconds <= 3L) 50L else 100L
        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ── Free Hold ────────────────────────────────────────────────────────────

    fun startFreeHold(deviceId: String) {
        bleManager.startRrStream(deviceId)
        freeHoldStartTime = System.currentTimeMillis()
        _uiState.update { it.copy(freeHoldActive = true, freeHoldDurationMs = 0L) }
    }

    fun stopFreeHold() {
        val duration = System.currentTimeMillis() - freeHoldStartTime
        _uiState.update { it.copy(freeHoldActive = false, freeHoldDurationMs = duration) }
        saveFreeHoldRecord(duration)
    }

    private fun saveFreeHoldRecord(durationMs: Long) {
        viewModelScope.launch {
            val rrSnapshot = bleManager.rrBuffer.readLast(512)
            val hrValues = rrSnapshot.map { 60_000.0 / it }
            val minHr = hrValues.minOrNull()?.toFloat() ?: 0f
            val maxHr = hrValues.maxOrNull()?.toFloat() ?: 0f
            val state = _uiState.value
            apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp = System.currentTimeMillis(),
                    durationMs = durationMs,
                    lungVolume = state.selectedLungVolume,
                    hyperventilationPrep = state.hyperventilationPrep,
                    minHrBpm = minHr,
                    maxHrBpm = maxHr,
                    tableType = null
                )
            )
        }
    }

    fun setLungVolume(volume: String) = _uiState.update { it.copy(selectedLungVolume = volume) }

    fun setHyperventilationPrep(value: Boolean) =
        _uiState.update { it.copy(hyperventilationPrep = value) }

    override fun onCleared() {
        super.onCleared()
        soundPool?.release()
        soundPool = null
        stateMachine.stop()
    }
}
