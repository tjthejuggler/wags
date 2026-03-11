package com.example.wags.ui.apnea

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.dao.ContractionDao
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionAnalyticsViewModel @Inject constructor(
    private val sessionRepository: ApneaSessionRepository,
    private val contractionDao: ContractionDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    data class ContractionDeltaPoint(
        val roundNumber: Int,
        val cruisingMs: Long,   // time to first contraction
        val struggleMs: Long    // time from first contraction to end
    )

    data class HypoxicScatterPoint(
        val pbMs: Long,
        val lateContractionCount: Int,  // contractions in last 30s of hold
        val sessionTimestamp: Long
    )

    private val _contractionDeltas = MutableStateFlow<List<ContractionDeltaPoint>>(emptyList())
    val contractionDeltas: StateFlow<List<ContractionDeltaPoint>> = _contractionDeltas.asStateFlow()

    private val _hypoxicScatter = MutableStateFlow<List<HypoxicScatterPoint>>(emptyList())
    val hypoxicScatter: StateFlow<List<HypoxicScatterPoint>> = _hypoxicScatter.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadSessionData(sessionId: Long) {
        viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            try {
                val contractions = contractionDao.getForSession(sessionId)
                val session = sessionRepository.getRecentSessions(50)
                    .firstOrNull { it.sessionId == sessionId }

                val deltaPoints = contractions
                    .groupBy { it.roundNumber }
                    .map { (round, roundContractions) ->
                        val firstContraction = roundContractions.minByOrNull { it.elapsedInRoundMs }
                        val lastContraction = roundContractions.maxByOrNull { it.elapsedInRoundMs }
                        val cruising = firstContraction?.elapsedInRoundMs ?: 0L
                        val totalHold = session?.let {
                            it.totalSessionDurationMs / it.totalRounds.coerceAtLeast(1)
                        } ?: (cruising + (lastContraction?.elapsedInRoundMs ?: cruising))
                        ContractionDeltaPoint(
                            roundNumber = round,
                            cruisingMs = cruising,
                            struggleMs = maxOf(0L, totalHold - cruising)
                        )
                    }
                    .sortedBy { it.roundNumber }

                _contractionDeltas.value = deltaPoints
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadHistoricalData() {
        viewModelScope.launch(ioDispatcher) {
            _isLoading.value = true
            try {
                val sessions = sessionRepository.getRecentSessions(50)
                val scatterPoints = sessions.mapNotNull { session ->
                    val contractions = contractionDao.getForSession(session.sessionId)
                    if (contractions.isEmpty()) return@mapNotNull null

                    val estimatedHoldMs = session.totalSessionDurationMs /
                            session.totalRounds.coerceAtLeast(1)
                    val lateThresholdMs = estimatedHoldMs - 30_000L
                    val lateContractions = contractions.count {
                        it.elapsedInRoundMs >= lateThresholdMs
                    }

                    HypoxicScatterPoint(
                        pbMs = session.pbAtSessionMs,
                        lateContractionCount = lateContractions,
                        sessionTimestamp = session.timestamp
                    )
                }
                _hypoxicScatter.value = scatterPoints
            } finally {
                _isLoading.value = false
            }
        }
    }
}
