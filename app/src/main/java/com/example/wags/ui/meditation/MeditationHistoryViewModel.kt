package com.example.wags.ui.meditation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.repository.MeditationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** null = show all postures */
typealias PostureFilter = MeditationPosture?

// ── Chart point ────────────────────────────────────────────────────────────────

data class MeditationChartPoint(val index: Float, val value: Float, val sessionId: Long)

// ── Chart data bundle ──────────────────────────────────────────────────────────

data class MeditationChartData(
    val avgHr: List<MeditationChartPoint> = emptyList(),
    val startRmssd: List<MeditationChartPoint> = emptyList(),
    val endRmssd: List<MeditationChartPoint> = emptyList(),
    val lnRmssdSlope: List<MeditationChartPoint> = emptyList(),
    val durationMin: List<MeditationChartPoint> = emptyList()
)

// ── UI state ──────────────────────────────────────────────────────────────────

data class MeditationHistoryUiState(
    val allSessions: List<MeditationSessionEntity> = emptyList(),
    val audioMap: Map<Long, MeditationAudioEntity> = emptyMap(),
    val chartData: MeditationChartData = MeditationChartData(),
    val datesWithSessions: Set<LocalDate> = emptySet(),
    val selectedDate: LocalDate? = null,
    val selectedDaySessions: List<MeditationSessionEntity> = emptyList(),
    /** null = all postures; non-null = only sessions with that posture */
    val postureFilter: PostureFilter = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class MeditationHistoryViewModel @Inject constructor(
    private val repository: MeditationRepository
) : ViewModel() {

    private val _extra = MutableStateFlow(ExtraState())

    val uiState: StateFlow<MeditationHistoryUiState> = combine(
        repository.observeSessions(),
        repository.observeAudios(),
        _extra
    ) { sessions, audios, extra ->
        val audioMap = audios.associateBy { it.audioId }
        val zone = ZoneId.systemDefault()

        // Apply posture filter for chart data
        val filteredSessions = if (extra.postureFilter == null) sessions
            else sessions.filter { it.posture == extra.postureFilter.name }

        val datesWithSessions = sessions.map { s ->
            Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate()
        }.toSet()

        val selectedDaySessions = extra.selectedDate?.let { date ->
            sessions.filter { s ->
                Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate() == date
            }
        } ?: emptyList()

        MeditationHistoryUiState(
            allSessions         = sessions,
            audioMap            = audioMap,
            chartData           = buildChartData(filteredSessions),
            datesWithSessions   = datesWithSessions,
            selectedDate        = extra.selectedDate,
            selectedDaySessions = selectedDaySessions,
            postureFilter       = extra.postureFilter
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeditationHistoryUiState()
    )

    fun selectDate(date: LocalDate) {
        _extra.update { it.copy(selectedDate = date) }
    }

    fun clearSelection() {
        _extra.update { it.copy(selectedDate = null) }
    }

    fun setPostureFilter(posture: PostureFilter) {
        _extra.update { it.copy(postureFilter = posture) }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildChartData(sessions: List<MeditationSessionEntity>): MeditationChartData {
        val sorted = sessions.sortedBy { it.timestamp }
        val avgHr = mutableListOf<MeditationChartPoint>()
        val startRmssd = mutableListOf<MeditationChartPoint>()
        val endRmssd = mutableListOf<MeditationChartPoint>()
        val lnSlope = mutableListOf<MeditationChartPoint>()
        val durationMin = mutableListOf<MeditationChartPoint>()

        sorted.forEachIndexed { i, s ->
            val idx = i.toFloat()
            s.avgHrBpm?.let { avgHr.add(MeditationChartPoint(idx, it, s.sessionId)) }
            s.startRmssdMs?.let { startRmssd.add(MeditationChartPoint(idx, it, s.sessionId)) }
            s.endRmssdMs?.let { endRmssd.add(MeditationChartPoint(idx, it, s.sessionId)) }
            s.lnRmssdSlope?.let { lnSlope.add(MeditationChartPoint(idx, it, s.sessionId)) }
            durationMin.add(MeditationChartPoint(idx, s.durationMs / 60_000f, s.sessionId))
        }

        return MeditationChartData(
            avgHr        = avgHr,
            startRmssd   = startRmssd,
            endRmssd     = endRmssd,
            lnRmssdSlope = lnSlope,
            durationMin  = durationMin
        )
    }

    private data class ExtraState(
        val selectedDate: LocalDate? = null,
        val postureFilter: PostureFilter = null
    )
}
