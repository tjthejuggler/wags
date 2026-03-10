package com.example.wags.domain.model

data class RrInterval(
    val timestampMs: Long,
    val intervalMs: Double,
    val isArtifact: Boolean = false
)
