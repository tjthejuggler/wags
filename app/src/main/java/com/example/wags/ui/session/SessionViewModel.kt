package com.example.wags.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.OximeterBleManager
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.SessionLogEntity
import com.example.wags.data.repository.SessionRepository
import com.example.wags.di.IoDispatcher
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import com.example.wags.domain.model.SessionType
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.session.HrSonificationEngine
import com.example.wags.domain.usecase.session.NsdrAnalyticsCalculator
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
    val lnRmssdSlope: Float? = null,
    /** ID of the monitor used for the active/completed session, null if none. */
    val monitorId: String? = null,
    /** ID of the currently connected BLE device (pre-session), null if none. */
    val connectedDeviceId: String? = null,
    val hasHrMonitor: Boolean = false,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val hrDataSource: HrDataSource,
    private val sessionRepository: SessionRepository,
    private val analyticsCalculator: NsdrAnalyticsCalculator,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val sonificationEngine: HrSonificationEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionUiState()
    )

    private var sessionJob: Job? = null
    private var sessionStartMs = 0L

    // Accumulated HR samples (1 Hz) for analytics
    private val hrTimeSeries = mutableListOf<Float>()

    init {
        // Observe all HR-capable connection states to keep hasHrMonitor + connectedDeviceId in sync
        viewModelScope.launch {
            hrDataSource.isAnyHrDeviceConnected.collect { anyConnected ->
                val h10Id = (bleManager.h10State.value as? BleConnectionState.Connected)?.deviceId
                val verityId = (bleManager.verityState.value as? BleConnectionState.Connected)?.deviceId
                val oxyAddr = (oximeterBleManager.connectionState.value as? OximeterConnectionState.Connected)?.deviceAddress
                // Polar takes priority for RR-based analytics; oximeter is HR-only
                val activeId = h10Id ?: verityId ?: oxyAddr
                _uiState.update {
                    it.copy(
                        hasHrMonitor = anyConnected,
                        connectedDeviceId = activeId
                    )
                }
            }
        }
    }

    fun setSessionType(type: SessionType) {
        if (_uiState.value.sessionState == SessionState.IDLE) {
            _uiState.update { it.copy(sessionType = type) }
        }
    }

    fun setSonificationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sonificationEnabled = enabled) }
    }

    /**
     * Start a session. If a monitor is connected its deviceId is passed; otherwise null
     * to run a timer-only session with no HR data.
     */
    fun startSession(deviceId: String?) {
        if (_uiState.value.sessionState == SessionState.ACTIVE) return

        val activeMonitorId = deviceId?.takeIf { it.isNotBlank() }
        if (activeMonitorId != null) {
            bleManager.startRrStream(activeMonitorId)
        }

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
                lnRmssdSlope = null,
                monitorId = activeMonitorId
            )
        }

        if (_uiState.value.sonificationEnabled && activeMonitorId != null) {
            sonificationEngine.start(viewModelScope)
        }

        sessionJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1_000L

                if (activeMonitorId != null) {
                    // Try Polar RR buffer first (gives RMSSD); fall back to oximeter HR
                    val rrSnapshot = bleManager.rrBuffer.readLast(64)
                    val polarHr = if (rrSnapshot.isNotEmpty())
                        (60_000.0 / rrSnapshot.last()).toFloat() else null
                    val currentHr = polarHr ?: hrDataSource.liveHr.value?.toFloat()
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
                } else {
                    // Timer-only: just tick elapsed time
                    _uiState.update { it.copy(elapsedSeconds = elapsed) }
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
            val monitorId = _uiState.value.monitorId
            val sessionType = _uiState.value.sessionType

            try {
                if (monitorId != null && hrTimeSeries.isNotEmpty()) {
                    // Full HR analytics path
                    val analytics = withContext(mathDispatcher) {
                        val rrSnapshot = bleManager.rrBuffer.readLast(1024)
                        val corrected = artifactCorrection.execute(rrSnapshot)
                        analyticsCalculator.calculate(
                            hrTimeSeries = hrTimeSeries.toList(),
                            nnIntervals = corrected.correctedNn
                        )
                    }

                    withContext(ioDispatcher) {
                        sessionRepository.saveSession(
                            SessionLogEntity(
                                timestamp = sessionStartMs,
                                durationMs = durationMs,
                                sessionType = sessionType.name,
                                monitorId = monitorId,
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
                } else {
                    // No-monitor path: save session with duration only
                    withContext(ioDispatcher) {
                        sessionRepository.saveSession(
                            SessionLogEntity(
                                timestamp = sessionStartMs,
                                durationMs = durationMs,
                                sessionType = sessionType.name,
                                monitorId = null
                            )
                        )
                    }
                    _uiState.update { it.copy(sessionState = SessionState.COMPLETE) }
                }
            } catch (e: Exception) {
                // On any error still save a minimal record so the session isn't lost
                withContext(ioDispatcher) {
                    runCatching {
                        sessionRepository.saveSession(
                            SessionLogEntity(
                                timestamp = sessionStartMs,
                                durationMs = durationMs,
                                sessionType = sessionType.name,
                                monitorId = monitorId
                            )
                        )
                    }
                }
                _uiState.update { it.copy(sessionState = SessionState.COMPLETE) }
            }
        }
    }

    fun reset() {
        sessionJob?.cancel()
        sonificationEngine.stop()
        hrTimeSeries.clear()
        // Preserve connection state across reset
        val h10Id = (bleManager.h10State.value as? BleConnectionState.Connected)?.deviceId
        val verityId = (bleManager.verityState.value as? BleConnectionState.Connected)?.deviceId
        val oxyAddr = (oximeterBleManager.connectionState.value as? OximeterConnectionState.Connected)?.deviceAddress
        val activeId = h10Id ?: verityId ?: oxyAddr
        _uiState.value = SessionUiState(
            hasHrMonitor = activeId != null,
            connectedDeviceId = activeId
        )
    }

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        sonificationEngine.stop()
    }
}
