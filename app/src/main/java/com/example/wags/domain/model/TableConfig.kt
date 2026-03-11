package com.example.wags.domain.model

/**
 * Combined configuration from TableLength + TableDifficulty.
 * Length controls rounds; Difficulty controls PB percentages.
 */
data class TableConfig(
    val length: TableLength,
    val difficulty: TableDifficulty,
    val rounds: Int,
    // CO2 parameters (set by difficulty)
    val co2HoldPercent: Float,
    val co2RestMinSec: Int,
    // O2 parameters (set by difficulty)
    val o2MaxHoldPercent: Float,
    val o2FirstHoldPercent: Float,  // always 0.40f
    val o2RestSec: Int              // 120–180s
)
