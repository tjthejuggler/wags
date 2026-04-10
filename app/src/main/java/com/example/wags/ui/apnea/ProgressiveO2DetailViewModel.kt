package com.example.wags.ui.apnea

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.TelemetryEntity
import com.example.wags.data.repository.ApneaSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

// ── Data classes ────────────────────────────────────────────────────────────

data class RoundDisplayData(
    val roundNumber: Int,
    val targetSec: Int,
    val actualSec: Int,
    val completed: Boolean
)

data class ProgressiveO2DetailUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val session: ApneaSessionEntity? = null,
    val record: ApneaRecordEntity? = null,
    val telemetry: List<TelemetryEntity> = emptyList(),
    val roundResults: List<RoundDisplayData> = emptyList(),
    val breathPeriodSec: Int = 0,
    val totalHoldTimeSec: Int = 0,
    val maxHoldReachedSec: Int = 0,
    val roundsCompleted: Int = 0,
    val totalRoundsAttempted: Int = 0,
    val sessionDurationSec: Int = 0,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    val avgHr: Int? = null,
    val lowestSpO2: Int? = null
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class ProgressiveO2DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: ApneaSessionRepository
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _uiState = MutableStateFlow(ProgressiveO2DetailUiState())
    val uiState: StateFlow<ProgressiveO2DetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = sessionRepository.getSessionById(sessionId)
            if (session == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }

            // Load telemetry
            val telemetry = sessionRepository.getTelemetryForSession(sessionId)

            // Parse tableParamsJson
            val (breathPeriodSec, rounds) = parseTableParams(session.tableParamsJson)

            // The ApneaRecordEntity doesn't have a lookup-by-timestamp method,
            // so we leave it null. The detail screen works fine with just the session.
            val record: ApneaRecordEntity? = null

            // Compute round stats
            val completedRounds = rounds.count { it.completed }
            val totalRoundsAttempted = rounds.size
            val totalHoldTimeSec = rounds.sumOf { it.actualSec }
            val maxHoldReachedSec = rounds
                .filter { it.completed }
                .maxOfOrNull { it.targetSec } ?: 0
            val sessionDurationSec = (session.totalSessionDurationMs / 1000).toInt()

            // Compute HR/SpO2 stats from telemetry
            val validHr = telemetry.mapNotNull { it.heartRateBpm }
                .filter { it in 20..250 }
            val validSpO2 = telemetry.mapNotNull { it.spO2 }
                .filter { it > 0 }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    session = session,
                    record = record,
                    telemetry = telemetry,
                    roundResults = rounds,
                    breathPeriodSec = breathPeriodSec,
                    totalHoldTimeSec = totalHoldTimeSec,
                    maxHoldReachedSec = maxHoldReachedSec,
                    roundsCompleted = completedRounds,
                    totalRoundsAttempted = totalRoundsAttempted,
                    sessionDurationSec = sessionDurationSec,
                    minHr = validHr.minOrNull(),
                    maxHr = validHr.maxOrNull(),
                    avgHr = if (validHr.isNotEmpty()) validHr.average().toInt() else null,
                    lowestSpO2 = validSpO2.minOrNull()
                )
            }
        }
    }

    // ── JSON parsing ────────────────────────────────────────────────────────

    private fun parseTableParams(jsonStr: String): Pair<Int, List<RoundDisplayData>> {
        return try {
            val json = JSONObject(jsonStr)
            val breathPeriodSec = json.optInt("breathPeriodSec", 0)
            val roundsArray = json.optJSONArray("rounds")
            val rounds = mutableListOf<RoundDisplayData>()
            if (roundsArray != null) {
                for (i in 0 until roundsArray.length()) {
                    val r = roundsArray.getJSONObject(i)
                    rounds.add(
                        RoundDisplayData(
                            roundNumber = r.optInt("round", i + 1),
                            targetSec = (r.optLong("targetMs", 0L) / 1000).toInt(),
                            actualSec = (r.optLong("actualMs", 0L) / 1000).toInt(),
                            completed = r.optBoolean("completed", false)
                        )
                    )
                }
            }
            breathPeriodSec to rounds
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tableParamsJson", e)
            0 to emptyList()
        }
    }

    companion object {
        private const val TAG = "ProgO2DetailVM"
    }
}
