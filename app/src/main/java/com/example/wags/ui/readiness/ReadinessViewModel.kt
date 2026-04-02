package com.example.wags.ui.readiness

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
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

enum class ReadinessSessionState { IDLE, PREPARING, RECORDING, PROCESSING, COMPLETE, ERROR }

/** The three available measurement durations. */
enum class HrvDuration(val seconds: Long, val label: String) {
    SHORT(120L, "2 min"),
    MEDIUM(180L, "3 min"),
    LONG(300L, "5 min")
}

data class ReadinessUiState(
    val sessionState: ReadinessSessionState = ReadinessSessionState.IDLE,
    val remainingSeconds: Long = 0L,
    val sessionDurationSeconds: Long = 120L,
    val preparingSecondsRemaining: Int = 20,
    val selectedDuration: HrvDuration = HrvDuration.SHORT,
    val rrCount: Int = 0,
    val liveRmssd: Float? = null,
    val liveSdnn: Float? = null,
    val hrvMetrics: HrvMetrics? = null,
    val readinessScore: ReadinessScore? = null,
    val errorMessage: String? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Recent RR intervals (ms) for the scrolling chart — last ~30 s worth. */
    val liveRrIntervals: List<Double> = emptyList()
)

@HiltViewModel
class ReadinessViewModel @Inject constructor(
    private val deviceManager: UnifiedDeviceManager,
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
    // Total writes at the moment the session started — used to ignore pre-session RR intervals
    private var rrWritesAtStart: Long = 0L

    fun selectDuration(duration: HrvDuration) {
        if (_uiState.value.sessionState == ReadinessSessionState.IDLE) {
            _uiState.update { it.copy(selectedDuration = duration) }
        }
    }

    fun startSession(deviceId: String) {
        val state = _uiState.value
        if (state.sessionState != ReadinessSessionState.IDLE) return
        val durationSeconds = state.selectedDuration.seconds

        collectedRr.clear()
        sessionHrDeviceLabel = hrDataSource.activeHrDeviceLabel()
        Log.d("ReadinessVM", "startSession(deviceId=$deviceId) — 20s prep then ${durationSeconds}s recording")

        _uiState.update {
            it.copy(
                sessionState = ReadinessSessionState.PREPARING,
                preparingSecondsRemaining = 20,
                rrCount = 0,
                liveRmssd = null,
                errorMessage = null
            )
        }

        sessionJob = viewModelScope.launch {
            // ── 20-second preparation countdown ──────────────────────────────
            var prep = 20
            while (prep > 0 && isActive) {
                delay(1_000L)
                prep--
                _uiState.update { it.copy(preparingSecondsRemaining = prep) }
            }
            if (!isActive) return@launch

            // ── Start actual recording ────────────────────────────────────────
            deviceManager.startRrStream(deviceId)
            rrWritesAtStart = deviceManager.rrBuffer.totalWrites()
            Log.d("ReadinessVM", "recording started: rrWritesAtStart=$rrWritesAtStart")
            _uiState.update {
                it.copy(
                    sessionState = ReadinessSessionState.RECORDING,
                    remainingSeconds = durationSeconds,
                    sessionDurationSeconds = durationSeconds
                )
            }

            var remaining = durationSeconds
            while (remaining > 0 && isActive) {
                delay(500L) // Poll at 2 Hz for smoother chart updates
                remaining = (remaining - 1L).coerceAtLeast(0L)
                val totalWrites = deviceManager.rrBuffer.totalWrites()
                val newCount = (totalWrites - rrWritesAtStart).toInt()
                    .coerceAtMost(deviceManager.rrBuffer.capacity)
                val snapshot = if (newCount > 0) deviceManager.rrBuffer.readLast(newCount) else emptyList()
                Log.d("ReadinessVM", "poll: remaining=$remaining newCount=$newCount snapshot.size=${snapshot.size}")
                collectedRr.clear()
                collectedRr.addAll(snapshot)
                val liveRmssd = computeLiveRmssd(snapshot)
                val liveSdnn = computeLiveSdnn(snapshot)
                val chartRr = if (newCount > 0) deviceManager.rrBuffer.readLast(45.coerceAtMost(newCount)) else emptyList()
                _uiState.update {
                    it.copy(
                        remainingSeconds = remaining,
                        rrCount = snapshot.size,
                        liveRmssd = liveRmssd,
                        liveSdnn = liveSdnn,
                        liveRrIntervals = chartRr
                    )
                }
                if (remaining == 0L) break
            }
            if (isActive) processSession()
        }
    }

    private fun computeLiveRmssd(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size).toFloat()
    }

    private fun computeLiveSdnn(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val mean = rr.average()
        val variance = rr.sumOf { (it - mean) * (it - mean) } / rr.size
        return Math.sqrt(variance).toFloat()
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
        _uiState.update { it.copy(sessionState = ReadinessSessionState.IDLE, preparingSecondsRemaining = 20) }
    }

    fun reset() {
        sessionJob?.cancel()
        collectedRr.clear()
        _uiState.value = ReadinessUiState()
    }
}
