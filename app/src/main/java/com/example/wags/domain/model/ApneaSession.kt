package com.example.wags.domain.model

data class ApneaSession(
    val sessionId: Long = 0,
    val timestamp: Long,
    val tableType: String,
    val tableVariant: String,
    val pbAtSessionMs: Long,
    val totalSessionDurationMs: Long,
    val contractionTimestamps: List<Long>,
    val maxHrBpm: Int?,
    val lowestSpO2: Int?,
    val roundsCompleted: Int,
    val totalRounds: Int
)
