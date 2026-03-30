package com.example.wags.ui.breathing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.domain.usecase.breathing.RateRecommendation
import com.example.wags.domain.usecase.breathing.ResonanceRateRecommender
import dagger.hilt.android.lifecycle.HiltViewModel
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

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadRecommendation()
    }

    private fun loadRecommendation() {
        viewModelScope.launch {
            _isLoading.value = true
            _recommendation.value = recommender.recommend()
            _isLoading.value = false
        }
    }
}
