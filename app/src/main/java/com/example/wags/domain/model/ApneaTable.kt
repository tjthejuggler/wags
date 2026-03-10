package com.example.wags.domain.model

data class ApneaTableStep(
    val apneaDurationMs: Long,
    val ventilationDurationMs: Long,
    val roundNumber: Int
)

data class ApneaTable(
    val type: ApneaTableType,
    val steps: List<ApneaTableStep>,
    val personalBestMs: Long
)
