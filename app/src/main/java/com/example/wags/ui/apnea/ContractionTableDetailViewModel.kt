package com.example.wags.ui.apnea

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.TelemetryEntity
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.domain.usecase.apnea.ContractionTableMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

// ── Data classes ────────────────────────────────────────────────────────────

data class ContractionRoundDisplayData(
    val roundNumber: Int,
    val restBeforeSec: Int,
    /** Easy-phase duration (sec); null when the hold ended before any contraction. */
    val cruiseSec: Int?,
    val struggleSec: Int,
    val totalHoldSec: Int,
    val contractions: Int,
    val completed: Boolean,
    val endedEarly: Boolean
) {
    /** Cruise ratio for this round (cruise / total hold); null when no contraction. */
    val cruiseRatio: Float?
        get() = if (cruiseSec != null && totalHoldSec > 0) cruiseSec.toFloat() / totalHoldSec.toFloat() else null
}

data class ContractionTableDetailUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val session: ApneaSessionEntity? = null,
    val telemetry: List<TelemetryEntity> = emptyList(),
    val roundResults: List<ContractionRoundDisplayData> = emptyList(),
    val mode: ContractionTableMode = ContractionTableMode.TILL_CONTRACTION,
    val roundsConfigured: Int = 0,
    val restStartSec: Int = 0,
    val restEndSec: Int = 0,
    val contractionTarget: Int = 0,
    val bestCruiseSec: Int? = null,
    val longestHoldSec: Int = 0,
    val totalHoldSec: Int = 0,
    val roundsCompleted: Int = 0,
    val totalRoundsAttempted: Int = 0,
    val sessionDurationSec: Int = 0,
    /** Average cruise ratio across rounds with a logged contraction. */
    val avgCruiseRatio: Float? = null,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    val avgHr: Int? = null,
    val lowestSpO2: Int? = null
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class ContractionTableDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: ApneaSessionRepository
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _uiState = MutableStateFlow(ContractionTableDetailUiState())
    val uiState: StateFlow<ContractionTableDetailUiState> = _uiState.asStateFlow()

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
            val parsed = parseTableParams(session.tableParamsJson)
            val rounds = parsed.rounds

            // Compute stats
            val completedRounds = rounds.count { it.completed }
            val totalRoundsAttempted = rounds.size
            val bestCruiseSec = rounds.mapNotNull { it.cruiseSec }.maxOrNull()
            val longestHoldSec = rounds.maxOfOrNull { it.totalHoldSec } ?: 0
            val totalHoldSec = rounds.sumOf { it.totalHoldSec }
            val sessionDurationSec = (session.totalSessionDurationMs / 1000).toInt()
            val ratios = rounds.mapNotNull { it.cruiseRatio }
            val avgCruiseRatio = if (ratios.isNotEmpty()) ratios.average().toFloat() else null

            // Compute HR/SpO2 stats from telemetry
            val validHr = telemetry.mapNotNull { it.heartRateBpm }
                .filter { it in 20..250 }
            val validSpO2 = telemetry.mapNotNull { it.spO2 }
                .filter { it > 0 }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    session = session,
                    telemetry = telemetry,
                    roundResults = rounds,
                    mode = parsed.mode,
                    roundsConfigured = parsed.roundsConfigured,
                    restStartSec = parsed.restStartSec,
                    restEndSec = parsed.restEndSec,
                    contractionTarget = parsed.contractionTarget,
                    bestCruiseSec = bestCruiseSec,
                    longestHoldSec = longestHoldSec,
                    totalHoldSec = totalHoldSec,
                    roundsCompleted = completedRounds,
                    totalRoundsAttempted = totalRoundsAttempted,
                    sessionDurationSec = sessionDurationSec,
                    avgCruiseRatio = avgCruiseRatio,
                    minHr = validHr.minOrNull(),
                    maxHr = validHr.maxOrNull(),
                    avgHr = if (validHr.isNotEmpty()) validHr.average().toInt() else null,
                    lowestSpO2 = validSpO2.minOrNull()
                )
            }
        }
    }

    // ── JSON parsing ────────────────────────────────────────────────────────

    private data class ParsedParams(
        val mode: ContractionTableMode,
        val roundsConfigured: Int,
        val restStartSec: Int,
        val restEndSec: Int,
        val contractionTarget: Int,
        val rounds: List<ContractionRoundDisplayData>
    )

    private fun parseTableParams(jsonStr: String): ParsedParams {
        return try {
            val json = JSONObject(jsonStr)
            val mode = try {
                ContractionTableMode.valueOf(json.optString("mode", ContractionTableMode.TILL_CONTRACTION.name))
            } catch (_: Exception) { ContractionTableMode.TILL_CONTRACTION }
            val roundsConfigured = json.optInt("rounds", 0)
            val restStartSec = json.optInt("restStartSec", 0)
            val restEndSec = json.optInt("restEndSec", 0)
            val contractionTarget = json.optInt("contractionTarget", 0)
            val roundsArray: JSONArray = json.optJSONArray("roundResults") ?: JSONArray()
            val rounds = mutableListOf<ContractionRoundDisplayData>()
            for (i in 0 until roundsArray.length()) {
                val r = roundsArray.getJSONObject(i)
                rounds.add(
                    ContractionRoundDisplayData(
                        roundNumber = r.optInt("round", i + 1),
                        restBeforeSec = (r.optLong("restBeforeMs", 0L) / 1000).toInt(),
                        cruiseSec = if (r.isNull("cruiseMs")) null else (r.optLong("cruiseMs", 0L) / 1000).toInt(),
                        struggleSec = (r.optLong("struggleMs", 0L) / 1000).toInt(),
                        totalHoldSec = (r.optLong("totalHoldMs", 0L) / 1000).toInt(),
                        contractions = r.optInt("contractions", 0),
                        completed = r.optBoolean("completed", false),
                        endedEarly = r.optBoolean("endedEarly", false)
                    )
                )
            }
            ParsedParams(mode, roundsConfigured, restStartSec, restEndSec, contractionTarget, rounds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tableParamsJson", e)
            ParsedParams(ContractionTableMode.TILL_CONTRACTION, 0, 0, 0, 0, emptyList())
        }
    }

    companion object {
        private const val TAG = "ContractionDetailVM"
    }
}
