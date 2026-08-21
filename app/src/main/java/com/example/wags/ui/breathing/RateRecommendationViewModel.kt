package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.domain.usecase.breathing.RateHistoryResult
import com.example.wags.domain.usecase.breathing.RateRecommendation
import com.example.wags.domain.usecase.breathing.ResonanceRateRecommender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RateRecommendationViewModel @Inject constructor(
    private val recommender: ResonanceRateRecommender
) : ViewModel() {

    private val _recommendation = MutableStateFlow<RateRecommendation?>(null)
    val recommendation: StateFlow<RateRecommendation?> = _recommendation

    private val _history = MutableStateFlow<RateHistoryResult?>(null)
    val history: StateFlow<RateHistoryResult?> = _history

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadRecommendation()
    }

    private fun loadRecommendation() {
        viewModelScope.launch {
            _isLoading.value = true
            val recommendationDeferred = async { recommender.recommend() }
            val historyDeferred = async { recommender.replayHistory() }
            _recommendation.value = recommendationDeferred.await()
            _history.value = historyDeferred.await()
            _isLoading.value = false
        }
    }
}
