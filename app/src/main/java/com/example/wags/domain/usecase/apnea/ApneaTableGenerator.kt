package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableStep
import com.example.wags.domain.model.ApneaTableType
import javax.inject.Inject

/**
 * Generates O2 and CO2 apnea training tables based on personal best (PB).
 *
 * O2 Table (Hypoxia): constant ventilation, increasing apnea
 *   - ventilationMs = 2 min (constant)
 *   - apneaSteps = [0.5, 0.6, 0.7, 0.75, 0.8] × PB (capped at 80% PB)
 *   - 6 rounds
 *
 * CO2 Table (Hypercapnia): decreasing ventilation, constant apnea
 *   - apneaMs = 0.5 × PB (constant)
 *   - ventilationSteps = [2.0, 1.75, 1.5, 1.25, 1.0] × 60s
 *   - 5 rounds
 */
class ApneaTableGenerator @Inject constructor() {

    companion object {
        private val O2_APNEA_RATIOS = listOf(0.5, 0.6, 0.7, 0.75, 0.8)
        private val CO2_VENTILATION_MINUTES = listOf(2.0, 1.75, 1.5, 1.25, 1.0)
        private const val O2_VENTILATION_MS = 2 * 60 * 1000L
        private const val ROUNDS = 5
    }

    fun generateO2Table(personalBestMs: Long): ApneaTable {
        val steps = mutableListOf<ApneaTableStep>()
        O2_APNEA_RATIOS.take(ROUNDS).forEachIndexed { index, ratio ->
            val apneaMs = (personalBestMs * ratio).toLong()
            steps.add(
                ApneaTableStep(
                    apneaDurationMs = apneaMs,
                    ventilationDurationMs = O2_VENTILATION_MS,
                    roundNumber = index + 1
                )
            )
        }
        return ApneaTable(ApneaTableType.O2, steps, personalBestMs)
    }

    fun generateCo2Table(personalBestMs: Long): ApneaTable {
        val apneaMs = (personalBestMs * 0.5).toLong()
        val steps = mutableListOf<ApneaTableStep>()
        CO2_VENTILATION_MINUTES.take(ROUNDS).forEachIndexed { index, minutes ->
            val ventilationMs = (minutes * 60 * 1000).toLong()
            steps.add(
                ApneaTableStep(
                    apneaDurationMs = apneaMs,
                    ventilationDurationMs = ventilationMs,
                    roundNumber = index + 1
                )
            )
        }
        return ApneaTable(ApneaTableType.CO2, steps, personalBestMs)
    }
}
