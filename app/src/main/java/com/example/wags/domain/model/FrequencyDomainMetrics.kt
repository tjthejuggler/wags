package com.example.wags.domain.model

data class FrequencyDomainMetrics(
    val vlfPowerMs2: Double,
    val lfPowerMs2: Double,
    val hfPowerMs2: Double,
    val lfHfRatio: Double,
    val lfNormalizedUnits: Double,
    val hfNormalizedUnits: Double
)
