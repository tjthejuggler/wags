package com.example.wags.domain.model

data class OrthostasisMetrics(
    val peakStandHr: Int,                  // Absolute highest HR during stand
    val shortestRrBeat15: Double?,         // Shortest RR interval (beats 6-24 window)
    val longestRrBeat30: Double?,          // Longest RR interval (beats 21-39 window)
    val thirtyFifteenRatio: Float?,        // longestRrBeat30 / shortestRrBeat15
    val hrAt20s: Int?,                     // HR exactly at 20s post-peak
    val hrAt60s: Int?,                     // HR exactly at 60s post-peak
    val ohrrAt20sPercent: Float?,          // % drop: (peakHR - hrAt20s) / peakHR * 100
    val ohrrAt60sPercent: Float?           // % drop: (peakHR - hrAt60s) / peakHR * 100
)
