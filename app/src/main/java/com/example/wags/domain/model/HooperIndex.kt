package com.example.wags.domain.model

data class HooperIndex(
    val sleep: Int,      // 1=Poor, 5=Excellent
    val fatigue: Int,    // 1=High fatigue, 5=No fatigue
    val soreness: Int,   // 1=Very sore, 5=No soreness
    val stress: Int      // 1=Very stressed, 5=No stress
) {
    val total: Float get() = (sleep + fatigue + soreness + stress).toFloat()
    val isLow: Boolean get() = total <= 10f   // ≤10 out of 20 = poor subjective state
    val isHigh: Boolean get() = total >= 16f  // ≥16 out of 20 = good subjective state
}
