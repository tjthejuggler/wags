package com.example.wags.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.db.entity.DailyReadingEntity
import com.example.wags.data.repository.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val latestReadings: List<DailyReadingEntity> = emptyList(),
    val lastReadinessScore: Int? = null,
    val lastLnRmssd: Float? = null,
    val liveHr: Int? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val readinessRepository: ReadinessRepository,
    private val bleManager: PolarBleManager
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        readinessRepository.getLatestReadings(14),
        bleManager.liveHr
    ) { readings, liveHr ->
        DashboardUiState(
            latestReadings = readings,
            lastReadinessScore = readings.firstOrNull()?.readinessScore,
            lastLnRmssd = readings.firstOrNull()?.lnRmssd,
            liveHr = liveHr
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )
}
