package com.example.wags.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.MeditationRepository
import com.example.wags.data.repository.MorningReadinessRepository
import com.example.wags.data.repository.RapidHrRepository
import com.example.wags.data.repository.ReadinessRepository
import com.example.wags.data.repository.ResonanceSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Latest-activity timestamp (epoch ms) per dashboard session card.
 * Null means that session type has never been done → badge renders as ∞.
 */
data class SessionLastUse(
    val morningReadinessMs: Long? = null,
    val hrvReadinessMs: Long? = null,
    val resonanceMs: Long? = null,
    val apneaMs: Long? = null,
    val meditationMs: Long? = null,
    val rapidHrMs: Long? = null
)

data class DashboardUiState(
    val latestReadings: List<DailyReadingEntity> = emptyList(),
    /** Non-null only when an HRV readiness reading was taken today. */
    val todayHrvReading: DailyReadingEntity? = null,
    /** Non-null only when a morning readiness reading was taken today. */
    val todayMorningReading: MorningReadinessEntity? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    val sessionLastUse: SessionLastUse = SessionLastUse()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val readinessRepository: ReadinessRepository,
    private val morningReadinessRepository: MorningReadinessRepository,
    private val resonanceSessionRepository: ResonanceSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val meditationRepository: MeditationRepository,
    private val rapidHrRepository: RapidHrRepository,
    private val hrDataSource: HrDataSource
) : ViewModel() {

    // Latest-activity timestamp per session type — feeds the days-since corner
    // badges on the main-screen session cards (same semantics as the apnea
    // section badges: HyperLockManager.daysSinceUsed, null → ∞).
    private val sessionLastUse = combine(
        morningReadinessRepository.observeLatestTimestamp(), // Morning Readiness
        readinessRepository.observeLatestTimestamp(),        // HRV Readiness
        resonanceSessionRepository.observeLatestEnd(),       // Resonance Breathing
        apneaRepository.observeLatestTimestamp(),            // Apnea Training
        meditationRepository.observeLatestEnd(),             // Meditation / NSDR
        rapidHrRepository.observeLatestEnd()                 // Rapid HR Change
    ) { latest: Array<Long?> ->
        SessionLastUse(
            morningReadinessMs = latest[0],
            hrvReadinessMs = latest[1],
            resonanceMs = latest[2],
            apneaMs = latest[3],
            meditationMs = latest[4],
            rapidHrMs = latest[5]
        )
    }

    private val todayReadings = combine(
        readinessRepository.getLatestReadings(14),
        readinessRepository.observeTodayReading(),
        morningReadinessRepository.observeTodayReading()
    ) { readings, todayHrv, todayMorning ->
        Triple(readings, todayHrv, todayMorning)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        todayReadings,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        sessionLastUse
    ) { (readings, todayHrv, todayMorning), liveHr, liveSpO2, lastUse ->
        DashboardUiState(
            latestReadings = readings,
            todayHrvReading = todayHrv,
            todayMorningReading = todayMorning,
            liveHr = liveHr,
            liveSpO2 = liveSpO2,
            sessionLastUse = lastUse
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )
}
