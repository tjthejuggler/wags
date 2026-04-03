package com.example.wags.ui.rapidhr

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.R
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.dao.RapidHrPreset
import com.example.wags.data.db.entity.RapidHrSessionEntity
import com.example.wags.data.db.entity.RapidHrTelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.RapidHrRepository
import com.example.wags.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ── Direction enum ─────────────────────────────────────────────────────────────

enum class RapidHrDirection(val label: String) {
    HIGH_TO_LOW("High → Low"),
    LOW_TO_HIGH("Low → High")
}

// ── Session phase ──────────────────────────────────────────────────────────────

enum class RapidHrPhase {
    IDLE,
    WAITING_FIRST,   // Waiting for HR to cross the first threshold
    TRANSITIONING,   // First threshold crossed — timing the transition
    COMPLETE         // Second threshold crossed — showing results
}

// ── UI state ──────────────────────────────────────────────────────────────────

data class RapidHrUiState(
    // Settings
    val direction: RapidHrDirection = RapidHrDirection.HIGH_TO_LOW,
    val highThresholdText: String = "120",
    val lowThresholdText: String = "60",
    // Presets for the selected direction
    val presets: List<RapidHrPreset> = emptyList(),
    // Session state
    val phase: RapidHrPhase = RapidHrPhase.IDLE,
    val elapsedMs: Long = 0L,
    val phase1Ms: Long = 0L,
    val transitionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val currentHr: Int? = null,
    // Live sensor
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    val hasHrMonitor: Boolean = false,
    // Results (populated on COMPLETE)
    val peakHrBpm: Int = 0,
    val troughHrBpm: Int = 0,
    val hrAtFirstCrossing: Int = 0,
    val hrAtSecondCrossing: Int = 0,
    val avgHrBpm: Float? = null,
    val isPersonalBest: Boolean = false,
    val savedSessionId: Long? = null
) {
    val highThreshold: Int get() = highThresholdText.toIntOrNull() ?: 120
    val lowThreshold: Int get() = lowThresholdText.toIntOrNull() ?: 60

    val firstThreshold: Int get() = when (direction) {
        RapidHrDirection.HIGH_TO_LOW -> highThreshold
        RapidHrDirection.LOW_TO_HIGH -> lowThreshold
    }

    val secondThreshold: Int get() = when (direction) {
        RapidHrDirection.HIGH_TO_LOW -> lowThreshold
        RapidHrDirection.LOW_TO_HIGH -> highThreshold
    }

    val phaseInstruction: String get() = when (phase) {
        RapidHrPhase.IDLE -> ""
        RapidHrPhase.WAITING_FIRST -> when (direction) {
            RapidHrDirection.HIGH_TO_LOW -> "Get HR above $highThreshold bpm"
            RapidHrDirection.LOW_TO_HIGH -> "Get HR below $lowThreshold bpm"
        }
        RapidHrPhase.TRANSITIONING -> when (direction) {
            RapidHrDirection.HIGH_TO_LOW -> "Now get HR below $lowThreshold bpm"
            RapidHrDirection.LOW_TO_HIGH -> "Now get HR above $highThreshold bpm"
        }
        RapidHrPhase.COMPLETE -> "Complete!"
    }

    /** Progress 0..1 toward the current target threshold. */
    fun progressToTarget(hr: Int): Float {
        val range = (highThreshold - lowThreshold).coerceAtLeast(1).toFloat()
        return when (phase) {
            RapidHrPhase.WAITING_FIRST -> when (direction) {
                RapidHrDirection.HIGH_TO_LOW -> ((hr - lowThreshold) / range).coerceIn(0f, 1f)
                RapidHrDirection.LOW_TO_HIGH -> ((highThreshold - hr) / range).coerceIn(0f, 1f)
            }
            RapidHrPhase.TRANSITIONING -> when (direction) {
                RapidHrDirection.HIGH_TO_LOW -> ((highThreshold - hr) / range).coerceIn(0f, 1f)
                RapidHrDirection.LOW_TO_HIGH -> ((hr - lowThreshold) / range).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }

    val canStart: Boolean get() = hasHrMonitor &&
        highThreshold > lowThreshold &&
        highThreshold in 40..220 &&
        lowThreshold in 30..200
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class RapidHrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RapidHrRepository,
    private val hrDataSource: HrDataSource,
    private val habitRepo: HabitIntegrationRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow(RapidHrUiState())

    val uiState: StateFlow<RapidHrUiState> = combine(
        _state,
        hrDataSource.liveHr,
        hrDataSource.isAnyHrDeviceConnected
    ) { state, liveHr, connected ->
        state.copy(liveHr = liveHr, hasHrMonitor = connected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RapidHrUiState())

    // ── Session tracking ───────────────────────────────────────────────────────

    private var sessionJob: Job? = null
    private var sessionStartMs: Long = 0L
    private var phase1StartMs: Long = 0L
    private var transitionStartMs: Long = 0L
    private val telemetrySamples = mutableListOf<RapidHrTelemetryEntity>()
    private val hrSamples = mutableListOf<Int>()
    private var hrAtFirstCrossing: Int = 0
    private var mediaPlayer: MediaPlayer? = null
    private var presetsJob: Job? = null

    init {
        loadPresets(RapidHrDirection.HIGH_TO_LOW)
    }

    private fun loadPresets(direction: RapidHrDirection) {
        presetsJob?.cancel()
        presetsJob = viewModelScope.launch {
            repository.getPresetsByDirection(direction.name)
                .collect { presets -> _state.update { it.copy(presets = presets) } }
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    fun setDirection(direction: RapidHrDirection) {
        _state.update { it.copy(direction = direction, presets = emptyList()) }
        loadPresets(direction)
    }

    fun setHighThreshold(text: String) {
        _state.update { it.copy(highThresholdText = text) }
    }

    fun setLowThreshold(text: String) {
        _state.update { it.copy(lowThresholdText = text) }
    }

    fun applyPreset(preset: RapidHrPreset) {
        _state.update {
            it.copy(
                highThresholdText = preset.highThreshold.toString(),
                lowThresholdText = preset.lowThreshold.toString()
            )
        }
    }

    // ── Session control ────────────────────────────────────────────────────────

    fun startSession() {
        val state = _state.value
        // canStart checks hasHrMonitor, but _state never has it set —
        // it's only merged in the public uiState combine.  Check the
        // source of truth directly.
        val connected = hrDataSource.isAnyHrDeviceConnected.value
        if (!connected) return
        if (state.highThreshold <= state.lowThreshold) return
        if (state.highThreshold !in 40..220 || state.lowThreshold !in 30..200) return

        sessionStartMs = System.currentTimeMillis()
        phase1StartMs = sessionStartMs
        telemetrySamples.clear()
        hrSamples.clear()
        hrAtFirstCrossing = 0

        _state.update {
            it.copy(
                phase = RapidHrPhase.WAITING_FIRST,
                elapsedMs = 0L,
                phase1Ms = 0L,
                transitionMs = 0L,
                totalDurationMs = 0L,
                peakHrBpm = 0,
                troughHrBpm = 999,
                hrAtFirstCrossing = 0,
                hrAtSecondCrossing = 0,
                avgHrBpm = null,
                isPersonalBest = false,
                savedSessionId = null
            )
        }

        sessionJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val currentState = _state.value
                val hr = hrDataSource.liveHr.value

                if (hr != null) {
                    hrSamples.add(hr)
                    val phase = currentState.phase
                    val newPeak = maxOf(currentState.peakHrBpm, hr)
                    val newTrough = minOf(currentState.troughHrBpm, hr)

                    telemetrySamples.add(
                        RapidHrTelemetryEntity(
                            sessionId = 0L,
                            offsetMs = now - sessionStartMs,
                            hrBpm = hr,
                            phase = phase.name
                        )
                    )

                    when (phase) {
                        RapidHrPhase.WAITING_FIRST -> {
                            val crossed = when (currentState.direction) {
                                RapidHrDirection.HIGH_TO_LOW -> hr >= currentState.highThreshold
                                RapidHrDirection.LOW_TO_HIGH -> hr <= currentState.lowThreshold
                            }
                            if (crossed) {
                                hrAtFirstCrossing = hr
                                transitionStartMs = now
                                playChime()
                                _state.update {
                                    it.copy(
                                        phase = RapidHrPhase.TRANSITIONING,
                                        phase1Ms = now - phase1StartMs,
                                        hrAtFirstCrossing = hr,
                                        peakHrBpm = newPeak,
                                        troughHrBpm = newTrough
                                    )
                                }
                            } else {
                                _state.update {
                                    it.copy(
                                        elapsedMs = now - sessionStartMs,
                                        currentHr = hr,
                                        peakHrBpm = newPeak,
                                        troughHrBpm = newTrough
                                    )
                                }
                            }
                        }
                        RapidHrPhase.TRANSITIONING -> {
                            val crossed = when (currentState.direction) {
                                RapidHrDirection.HIGH_TO_LOW -> hr <= currentState.lowThreshold
                                RapidHrDirection.LOW_TO_HIGH -> hr >= currentState.highThreshold
                            }
                            val transMs = now - transitionStartMs
                            if (crossed) {
                                playChime()
                                val totalMs = now - sessionStartMs
                                val avgHr = if (hrSamples.isNotEmpty()) hrSamples.average().toFloat() else null
                                _state.update {
                                    it.copy(
                                        phase = RapidHrPhase.COMPLETE,
                                        transitionMs = transMs,
                                        totalDurationMs = totalMs,
                                        hrAtSecondCrossing = hr,
                                        peakHrBpm = newPeak,
                                        troughHrBpm = if (newTrough == 999) 0 else newTrough,
                                        avgHrBpm = avgHr
                                    )
                                }
                                saveSession(currentState, hr, transMs, totalMs, newPeak, newTrough, avgHr)
                                return@launch
                            } else {
                                _state.update {
                                    it.copy(
                                        elapsedMs = now - sessionStartMs,
                                        transitionMs = transMs,
                                        currentHr = hr,
                                        peakHrBpm = newPeak,
                                        troughHrBpm = newTrough
                                    )
                                }
                            }
                        }
                        else -> {}
                    }
                }
                delay(500L)
            }
        }
    }

    fun cancelSession() {
        sessionJob?.cancel()
        sessionJob = null
        _state.update { it.copy(phase = RapidHrPhase.IDLE) }
    }

    fun resetToIdle() {
        _state.update { it.copy(phase = RapidHrPhase.IDLE, savedSessionId = null) }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun saveSession(
        state: RapidHrUiState,
        hrAtSecond: Int,
        transitionMs: Long,
        totalMs: Long,
        peakHr: Int,
        troughHr: Int,
        avgHr: Float?
    ) {
        viewModelScope.launch(ioDispatcher) {
            val isPb = repository.getBestTransitionTime(
                state.direction.name,
                state.highThreshold,
                state.lowThreshold
            )?.let { transitionMs < it } ?: true

            val entity = RapidHrSessionEntity(
                timestamp = sessionStartMs,
                direction = state.direction.name,
                highThreshold = state.highThreshold,
                lowThreshold = state.lowThreshold,
                phase1DurationMs = state.phase1Ms,
                transitionDurationMs = transitionMs,
                totalDurationMs = totalMs,
                peakHrBpm = peakHr,
                troughHrBpm = if (troughHr == 999) 0 else troughHr,
                hrAtFirstCrossing = hrAtFirstCrossing,
                hrAtSecondCrossing = hrAtSecond,
                avgHrBpm = avgHr,
                monitorId = hrDataSource.activeHrDeviceLabel(),
                isPersonalBest = isPb
            )

            val sessionId = repository.saveSession(entity)
            val telemetryWithId = telemetrySamples.map { it.copy(sessionId = sessionId) }
            repository.saveTelemetry(telemetryWithId)

            // Signal Tail habit integration
            try {
                habitRepo.sendHabitIncrement(Slot.RAPID_HR_CHANGE)
            } catch (_: Exception) { /* never crash */ }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _state.update { it.copy(isPersonalBest = isPb, savedSessionId = sessionId) }
            }
        }
    }

    // ── Sound ──────────────────────────────────────────────────────────────────

    private fun playChime() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, R.raw.chime_end)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        presetsJob?.cancel()
        mediaPlayer?.release()
    }
}
