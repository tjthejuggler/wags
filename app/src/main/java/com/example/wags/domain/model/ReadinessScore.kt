package com.example.wags.domain.model

data class ReadinessScore(
    val score: Int,
    val zScore: Float,
    val interpretation: ReadinessInterpretation
)

enum class ReadinessInterpretation {
    OPTIMAL, ELEVATED, REDUCED, LOW, OVERREACHING
}
