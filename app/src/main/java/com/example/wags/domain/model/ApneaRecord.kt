package com.example.wags.domain.model

enum class LungVolume { FULL, EMPTY, PARTIAL }
enum class ApneaTableType { O2, CO2, FREE }

data class ApneaRecord(
    val durationMs: Long,
    val lungVolume: LungVolume,
    val hyperventilationPrep: Boolean,
    val minHrBpm: Float,
    val maxHrBpm: Float,
    val tableType: ApneaTableType?
)
