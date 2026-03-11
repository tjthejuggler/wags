package com.example.wags.domain.model

data class WonkaConfig(
    val enduranceDeltaSec: Int = 45,  // X seconds after first contraction
    val restSec: Int = 120
)
