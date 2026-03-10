package com.example.wags.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.SessionLogEntity
import com.example.wags.data.repository.SessionRepository
import com.example.wags.di.IoDispatcher
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.model.SessionType
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.session.HrSonificationEngine
import com.example.wags.domain.usecase.session.NsdrAnalyticsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SessionState { IDLE, ACTIVE, PROCESSING, COMPLETE }

data class SessionUiState(
    val sessionState: SessionState = SessionState.IDLE,
    val sessionType: SessionType = SessionType.MEDITATION,
    val elapsedSeconds: Long = 0L,
    val currentHrBpm: Float? = null,
    val currentRmssd: Float? = null,
    val sonificationEnabled: Boolean = false,
    val avgHrBpm: Float? = null,
    val hrSlopeBpmPerMin: Float? = null,
    val startRmssdMs: Float? = null,
    val endRmssdMs: Float? = null,
    val lnRmssdSlope: Float? = null
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val bleManager: PolarBleManager,
    private val sessionRepository: SessionRepository,
    private val analyticsCalculator: NsdrAnalyticsCalculator,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val sonificationEngine: HrSonificationEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var sessionJob: Job? = null
    private var sessionStartMs = 0L

    // Accumulated HR samples (1 Hz) for analytics
    private val hrTimeSeries = mutableListOf<Float>()

    fun setSessionType(type: SessionType) {
        if (_uiState.value.sessionState == SessionState.IDLE) {
            _uiState.update { it.copy(sessionType = type) }
        }
    }

    fun setSonificationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sonificationEnabled = enabled) }
    }

    fun startSession(deviceId: String) {
        if (_uiState.value.sessionState == SessionState.ACTIVE) return
        bleManager.startRrStream(deviceId)
        hrTimeSeries.clear()
        sessionStartMs = System.currentTimeMillis()

        _uiState.update {
            it.copy(
                sessionState = SessionState.ACTIVE,
                elapsedSeconds = 0L,
                currentHrBpm = null,
                currentRmssd = null,
                avgHrBpm = null,
                hrSlopeBpmPerMin = null,
                startRmssdMs = null,
                endRmssdMs = null,
                lnRmssdSlope = null
            )
        }

        if (_uiState.value.sonificationEnabled) {
            sonificationEngine.start(viewModelScope)
        }

        sessionJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1_000L
                val rrSnapshot = bleManager.rrBuffer.readLast(64)
                val currentHr = if (rrSnapshot.isNotEmpty())
                    (60_000.0 / rrSnapshot.last()).toFloat() else null
                val liveRmssd = computeLiveRmssd(rrSnapshot)

                if (currentHr != null) {
                    hrTimeSeries.add(currentHr)
                    if (_uiState.value.sonificationEnabled) {
                        sonificationEngine.updateHr(currentHr)
                    }
                }

                _uiState.update {
                    it.copy(
                        elapsedSeconds = elapsed,
                        currentHrBpm = currentHr,
                        currentRmssd = liveRmssd
                    )
                }
            }
        }
    }

    fun stopSession() {
        sessionJob?.cancel()
        sonificationEngine.stop()
        val durationMs = System.currentTimeMillis() - sessionStartMs
        processSession(durationMs)
    }

    private fun computeLiveRmssd(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size).toFloat()
    }

    private fun processSession(durationMs: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(sessionState = SessionState.PROCESSING) }
            try {
                val analytics = withContext(mathDispatcher) {
                    val rrSnapshot = bleManager.rrBuffer.readLast(1024)
                    val corrected = artifactCorrection.execute(rrSnapshot)
                    analyticsCalculator.calculate(
                        hrTimeSeries = hrTimeSeries.toList(),
                        nnIntervals = corrected.correctedNn
                    )
                }

                val sessionType = _uiState.value.sessionType
                withContext(ioDispatcher) {
                    sessionRepository.saveSession(
                        SessionLogEntity(
                            timestamp = sessionStartMs,
                            durationMs = durationMs,
                            sessionType = sessionType.name,
                            avgHrBpm = analytics.avgHrBpm,
                            hrSlopeBpmPerMin = analytics.hrSlopeBpmPerMin,
                            startRmssdMs = analytics.startRmssdMs,
                            endRmssdMs = analytics.endRmssdMs,
                            lnRmssdSlope = analytics.lnRmssdSlope
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        sessionState = SessionState.COMPLETE,
                        avgHrBpm = analytics.avgHrBpm,
                        hrSlopeBpmPerMin = analytics.hrSlopeBpmPerMin,
                        startRmssdMs = analytics.startRmssdMs,
                        endRmssdMs = analytics.endRmssdMs,
                        lnRmssdSlope = analytics.lnRmssdSlope
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(sessionState = SessionState.COMPLETE) }
            }
        }
    }

    fun reset() {
        sessionJob?.cancel()
        sonificationEngine.stop()
        hrTimeSeries.clear()
        _uiState.value = SessionUiState()
    }

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        sonificationEngine.stop()
    }
}
