package com.example.wags.ui.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ReadinessRepository
import com.example.wags.di.IoDispatcher
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.model.HrvMetrics
import com.example.wags.domain.model.ReadinessScore
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.hrv.FrequencyDomainCalculator
import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import com.example.wags.domain.usecase.readiness.ReadinessScoreCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ReadinessSessionState { IDLE, RECORDING, PROCESSING, COMPLETE, ERROR }

data class ReadinessUiState(
    val sessionState: ReadinessSessionState = ReadinessSessionState.IDLE,
    val remainingSeconds: Long = 0L,
    val sessionDurationSeconds: Long = 120L,
    val rrCount: Int = 0,
    val liveRmssd: Float? = null,
    val hrvMetrics: HrvMetrics? = null,
    val readinessScore: ReadinessScore? = null,
    val errorMessage: String? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null
)

@HiltViewModel
class ReadinessViewModel @Inject constructor(
    private val bleManager: PolarBleManager,
    private val hrDataSource: HrDataSource,
    private val readinessRepository: ReadinessRepository,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val timeDomainCalculator: TimeDomainHrvCalculator,
    private val freqDomainCalculator: FrequencyDomainCalculator,
    private val readinessScoreCalculator: ReadinessScoreCalculator,
    private val habitRepo: HabitIntegrationRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadinessUiState())
    val uiState: StateFlow<ReadinessUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReadinessUiState()
    )

    private var sessionJob: Job? = null
    private val collectedRr = mutableListOf<Double>()
    // Captured at session-start so the device label is recorded even if it disconnects before save
    private var sessionHrDeviceLabel: String? = null
    // Buffer size at the moment the session started — used to ignore pre-session RR intervals
    private var rrBufferSizeAtStart: Int = 0

    fun startSession(deviceId: String, durationSeconds: Long = 120L) {
        if (_uiState.value.sessionState == ReadinessSessionState.RECORDING) return
        collectedRr.clear()
        sessionHrDeviceLabel = hrDataSource.activeHrDeviceLabel()
        bleManager.startRrStream(deviceId)
        // Snapshot the current buffer size so we only count intervals from this point forward
        rrBufferSizeAtStart = bleManager.rrBuffer.size()
        _uiState.update {
            it.copy(
                sessionState = ReadinessSessionState.RECORDING,
                remainingSeconds = durationSeconds,
                sessionDurationSeconds = durationSeconds,
                rrCount = 0,
                liveRmssd = null,
                errorMessage = null
            )
        }

        sessionJob = viewModelScope.launch {
            var remaining = durationSeconds
            while (remaining > 0 && isActive) {
                delay(1_000L)
                remaining--
                // Only take intervals added since the session started
                val totalInBuffer = bleManager.rrBuffer.size()
                val newCount = totalInBuffer - rrBufferSizeAtStart
                val snapshot = if (newCount > 0) bleManager.rrBuffer.readLast(newCount) else emptyList()
                collectedRr.clear()
                collectedRr.addAll(snapshot)
                val liveRmssd = computeLiveRmssd(snapshot)
                _uiState.update {
                    it.copy(
                        remainingSeconds = remaining,
                        rrCount = snapshot.size,
                        liveRmssd = liveRmssd
                    )
                }
            }
            if (isActive) processSession()
        }
    }

    private fun computeLiveRmssd(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size).toFloat()
    }

    private fun processSession() {
        viewModelScope.launch {
            _uiState.update { it.copy(sessionState = ReadinessSessionState.PROCESSING) }
            try {
                val (hrv, freq) = withContext(mathDispatcher) {
                    val corrected = artifactCorrection.execute(collectedRr)
                    val hrv = timeDomainCalculator.calculate(corrected.correctedNn, corrected.artifactMask)
                    val freq = freqDomainCalculator.calculate(corrected.correctedNn)
                    Pair(hrv, freq)
                }

                val baseline = withContext(ioDispatcher) {
                    readinessRepository.getLast14ForBaseline()
                }
                val score = readinessScoreCalculator.calculate(hrv.lnRmssd.toFloat(), baseline)

                withContext(ioDispatcher) {
                    readinessRepository.saveReading(
                        DailyReadingEntity(
                            timestamp = System.currentTimeMillis(),
                            restingHrBpm = if (collectedRr.isNotEmpty())
                                (60_000.0 / collectedRr.average()).toFloat() else 0f,
                            rawRmssdMs = hrv.rmssdMs.toFloat(),
                            lnRmssd = hrv.lnRmssd.toFloat(),
                            hfPowerMs2 = freq.hfPowerMs2.toFloat(),
                            sdnnMs = hrv.sdnnMs.toFloat(),
                            readinessScore = score.score,
                            hrDeviceId = sessionHrDeviceLabel
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        sessionState = ReadinessSessionState.COMPLETE,
                        hrvMetrics = hrv,
                        readinessScore = score
                    )
                }
                // Signal the Habit app that an HRV Readiness session completed
                habitRepo.sendHabitIncrement(Slot.HRV_READINESS)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        sessionState = ReadinessSessionState.ERROR,
                        errorMessage = e.message ?: "Processing failed"
                    )
                }
            }
        }
    }

    fun cancelSession() {
        sessionJob?.cancel()
        _uiState.update { it.copy(sessionState = ReadinessSessionState.IDLE) }
    }

    fun reset() {
        sessionJob?.cancel()
        collectedRr.clear()
        _uiState.value = ReadinessUiState()
    }
}
