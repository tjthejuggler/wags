package com.example.wags.domain.model

data class HrvMetrics(
    val rmssdMs: Double,
    val lnRmssd: Double,
    val sdnnMs: Double,
    val pnn50Percent: Double,
    val sampleCount: Int
)
