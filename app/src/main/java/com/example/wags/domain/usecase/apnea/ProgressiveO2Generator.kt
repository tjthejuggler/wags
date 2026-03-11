package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableStep
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.TableLength
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a Progressive O₂ table where both hold and rest increase each round.
 * Formula: holdMs = (30 + (round-1) * 15) * 1000, restMs = holdMs
 */
@Singleton
class ProgressiveO2Generator @Inject constructor() {

    fun generate(length: TableLength): ApneaTable {
        val rounds = when (length) {
            TableLength.SHORT  -> 4
            TableLength.MEDIUM -> 8
            TableLength.LONG   -> 12
        }
        val steps = (1..rounds).map { round ->
            val holdMs = (30 + (round - 1) * 15) * 1000L
            ApneaTableStep(
                apneaDurationMs = holdMs,
                ventilationDurationMs = holdMs,
                roundNumber = round
            )
        }
        return ApneaTable(
            type = ApneaTableType.O2,
            steps = steps,
            personalBestMs = 0L
        )
    }
}
