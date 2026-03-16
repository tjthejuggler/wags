package com.example.wags.domain.model

data class MorningReadinessResult(
    val timestamp: Long,
    val supineHrvMetrics: HrvMetrics,
    val standingHrvMetrics: HrvMetrics,
    val supineRhr: Int,                    // Resting HR in bpm (supine)
    val peakStandHr: Int,                  // Peak HR during orthostatic stand
    val thirtyFifteenRatio: Float?,        // Longest RR (beat ~30) / Shortest RR (beat ~15); null if insufficient data
    val ohrrAt20s: Float?,                 // % HR drop at 20s post-peak; null if not captured
    val ohrrAt60s: Float?,                 // % HR drop at 60s post-peak; null if not captured
    val respiratoryRateBpm: Float?,        // Breaths per minute; null if not calculated
    val slowBreathingFlagged: Boolean,     // true if respRate < 9 bpm (< 0.15 Hz)
    val hooperIndex: Float?,               // Sum of 4 Hooper questions (1.0-5.0 scale each); null if skipped
    val hooperSleep: Float?,               // 1.0-5.0 continuous
    val hooperFatigue: Float?,             // 1.0-5.0 continuous
    val hooperSoreness: Float?,            // 1.0-5.0 continuous
    val hooperStress: Float?,              // 1.0-5.0 continuous
    val artifactPercentSupine: Float,      // % of beats flagged as artifacts in supine window
    val artifactPercentStanding: Float,    // % of beats flagged as artifacts in standing window
    val readinessScore: Int,               // Final 0-100 score
    val readinessColor: ReadinessColor,    // RED, YELLOW, GREEN
    val hvBaseScore: Int,                  // HRV_Base before multipliers
    val orthoMultiplier: Float,            // Orthostatic multiplier (0.70, 0.90, or 1.0)
    val cvPenaltyApplied: Boolean,         // Whether CV penalty was applied
    val rhrLimiterApplied: Boolean,        // Whether RHR hard limiter capped the score
    val skinTempLimiterApplied: Boolean    // Whether skin temp limiter capped the score
)

enum class ReadinessColor { RED, YELLOW, GREEN }
