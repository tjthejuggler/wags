package com.example.wags.domain.model

data class Contraction(
    val contractionId: Long = 0,
    val sessionId: Long,
    val roundNumber: Int,
    val timestampMs: Long,
    val elapsedInRoundMs: Long,
    val phase: String  // "CRUISING" or "STRUGGLE"
)
