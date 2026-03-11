package com.example.wags.domain.model

data class OximeterReading(
    val timestampMs: Long = System.currentTimeMillis(),
    val spO2: Int,           // percentage 0-100
    val heartRateBpm: Int,   // BPM
    val isValid: Boolean = true
)
