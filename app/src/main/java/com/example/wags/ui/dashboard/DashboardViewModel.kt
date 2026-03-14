package com.example.wags.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.db.entity.MorningReadinessEntity
import com.example.wags.data.repository.MorningReadinessRepository
import com.example.wags.data.repository.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val latestReadings: List<DailyReadingEntity> = emptyList(),
    /** Non-null only when an HRV readiness reading was taken today. */
    val todayHrvReading: DailyReadingEntity? = null,
    /** Non-null only when a morning readiness reading was taken today. */
    val todayMorningReading: MorningReadinessEntity? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val readinessRepository: ReadinessRepository,
    private val morningReadinessRepository: MorningReadinessRepository,
    private val hrDataSource: HrDataSource
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        readinessRepository.getLatestReadings(14),
        readinessRepository.observeTodayReading(),
        morningReadinessRepository.observeTodayReading(),
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { readings, todayHrv, todayMorning, liveHr, liveSpO2 ->
        DashboardUiState(
            latestReadings = readings,
            todayHrvReading = todayHrv,
            todayMorningReading = todayMorning,
            liveHr = liveHr,
            liveSpO2 = liveSpO2
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )
}
