package com.example.wags.domain.model

data class SessionMetrics(
    val durationMs: Long,
    val sessionType: SessionType,
    val avgHrBpm: Float,
    val hrSlopeBpmPerMin: Float,
    val startRmssdMs: Float,
    val endRmssdMs: Float,
    val lnRmssdSlope: Float
)

enum class SessionType { MEDITATION, NSDR, BREATHING }
