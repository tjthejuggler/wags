package com.example.wags.domain.model

/**
 * Controls the PB percentage used for hold/rest calculations (training intensity).
 * EASY = conservative percentages for beginners/recovery
 * MEDIUM = standard training percentages
 * HARD = aggressive percentages for advanced athletes
 */
enum class TableDifficulty {
    EASY,    // CO2: 40% PB hold, 30s min rest | O2: 70% PB max hold
    MEDIUM,  // CO2: 50% PB hold, 15s min rest | O2: 80% PB max hold
    HARD     // CO2: 55% PB hold, 10s min rest | O2: 85% PB max hold
}
