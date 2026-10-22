package com.example.wags.ui.readiness

import android.content.Context
import android.content.SharedPreferences
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
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.hrv.MissedBeatReconstructor
import com.example.wags.domain.model.ReadinessScore
import com.example.wags.domain.usecase.hrv.FrequencyDomainCalculator
import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import com.example.wags.domain.usecase.readiness.ReadinessScoreCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    SHORT(60L, "1 min"),
    MEDIUM(120L, "2 min"),
    LONG(240L, "4 min")
}

/**
 * Represents the different metrics that can be displayed in the countdown circle center.
 */
enum class CircleMetric {
    TIME,
    HR,
    RMSSD,
    SDNN,
    BEATS
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
    /** Non-null when the completed capture had low beat coverage; metrics are flagged. */
    val qualityWarning: String? = null,
    /** Non-null when an HR-related dialog should be shown (no device, or no data streaming). */
    val hrDialogMessage: String? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Recent RR intervals (ms) for the scrolling chart — last ~30 s worth. */
    val liveRrIntervals: List<Double> = emptyList(),
    /** Which metric to display in the center of the countdown circle. */
    val selectedCircleMetric: CircleMetric = CircleMetric.HR
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
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "ReadinessVM"
        private const val PREFS_NAME = "hrv_readiness_prefs"
        private const val KEY_SELECTED_METRIC = "selected_circle_metric"

        /** Minimum fraction of expected beats that must survive for an unflagged reading. */
        private const val MIN_COVERAGE_RATIO = 0.60
    }

    private val _uiState = MutableStateFlow(ReadinessUiState())

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Load saved metric preference
        val savedMetric = prefs.getString(KEY_SELECTED_METRIC, null)
        if (savedMetric != null) {
            try {
                val metric = CircleMetric.valueOf(savedMetric)
                _uiState.update { it.copy(selectedCircleMetric = metric) }
            } catch (e: IllegalArgumentException) {
                // Invalid saved value, use default (HR)
            }
        }
    }
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

        // Guard: no HR device connected
        if (!hrDataSource.isAnyHrDeviceConnected.value) {
            _uiState.update { it.copy(hrDialogMessage = "Please connect a heart rate monitor before starting the HRV Readiness test.") }
            return
        }

        // Guard: device connected but not streaming HR data
        if (hrDataSource.liveHr.value == null) {
            _uiState.update { it.copy(hrDialogMessage = "Heart rate monitor is connected but not receiving data. Make sure the strap is snug and moist, then try again.") }
            return
        }

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

            // FIX (2026-10-22): the old loop decremented `remaining` by 1 s on
            // every 500 ms poll, so every test ran at HALF its advertised
            // duration (the "2 min" test actually recorded 60 s — the real
            // cause of the ~57-sample captures). Countdown now uses wall-clock
            // elapsed time so the recording lasts exactly durationSeconds.
            val recordStartMs = android.os.SystemClock.elapsedRealtime()
            var remaining = durationSeconds
            while (remaining > 0 && isActive) {
                delay(500L) // Poll at 2 Hz for smoother chart updates
                val elapsedSec = (android.os.SystemClock.elapsedRealtime() - recordStartMs) / 1000L
                remaining = (durationSeconds - elapsedSec).coerceAtLeast(0L)
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
        // Reconstruct dropped beats before differencing — otherwise a single
        // missed beat (+1000 ms diff) rockets the live RMSSD exactly like the
        // stored-metrics flaw fixed in pipeline v2 (2026-10-22).
        val cleaned = MissedBeatReconstructor.reconstruct(rr).rr
        val diffs = (1 until cleaned.size).map { cleaned[it] - cleaned[it - 1] }
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

                // Quality gate (pipeline v2, 2026-10-22): a clean capture should
                // record roughly durationSeconds * hr / 60 beats. When coverage
                // is poor the sensor dropped beats / lost contact — flag the
                // reading so it is excluded from baselines, and warn the user.
                val durationSeconds = _uiState.value.sessionDurationSeconds
                val expectedBeats = hrDataSource.liveHr.value
                    ?.let { (durationSeconds * it / 60.0) } ?: 0.0
                val lowCoverage = expectedBeats > 0 &&
                        hrv.sampleCount < MIN_COVERAGE_RATIO * expectedBeats
                val qualityWarning = if (lowCoverage) {
                    "Low beat coverage: captured ${hrv.sampleCount} of ~${expectedBeats.toInt()} " +
                    "expected beats. The sensor may have dropped beats or lost contact — " +
                    "moisten the strap electrodes and ensure a snug fit, then re-test. " +
                    "This reading is flagged and excluded from baseline calculations."
                } else null

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
                            hrDeviceId = sessionHrDeviceLabel,
                            metricsVersion = ArtifactCorrectionUseCase.METRICS_VERSION,
                            qualityFlag = lowCoverage,
                            sessionDurationSeconds = durationSeconds.toInt()
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        sessionState = ReadinessSessionState.COMPLETE,
                        hrvMetrics = hrv,
                        readinessScore = score,
                        qualityWarning = qualityWarning
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

    fun dismissHrDialog() {
        _uiState.update { it.copy(hrDialogMessage = null) }
    }

    fun setSelectedCircleMetric(metric: CircleMetric) {
        _uiState.update { it.copy(selectedCircleMetric = metric) }
        prefs.edit().putString(KEY_SELECTED_METRIC, metric.name).apply()
    }

    fun reset() {
        sessionJob?.cancel()
        collectedRr.clear()
        _uiState.value = ReadinessUiState()
    }
}
