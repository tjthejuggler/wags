package com.example.wags.domain.usecase.apnea

import com.example.wags.domain.model.ApneaTable
import com.example.wags.domain.model.ApneaTableStep
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.domain.model.TableConfig
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates dynamic CO2 and O2 apnea training tables based on personal best (PB),
 * a TableLength (controls number of rounds), and a TableDifficulty (controls PB percentages).
 *
 * CO2 Table (Hypercapnia): fixed hold, progressively shorter rests
 * O2 Table  (Hypoxia):     fixed rest, progressively longer holds
 */
@Singleton
class ApneaTableGenerator @Inject constructor() {

    // ── Public API ────────────────────────────────────────────────────────────

    fun generateCo2Table(pbMs: Long, length: TableLength, difficulty: TableDifficulty): ApneaTable {
        val cfg = buildConfig(length, difficulty)
        val tHold = (pbMs * cfg.co2HoldPercent.toDouble())
            .coerceAtMost(pbMs * 0.55)          // clamped to 55% max
        val r1 = tHold
        val rMin = cfg.co2RestMinSec * 1_000.0
        val n = cfg.rounds
        val deltaR = if (n > 1) (r1 - rMin) / (n - 1) else 0.0

        val steps = (1..n).map { round ->
            val rN = (r1 - ((round - 1) * deltaR)).coerceAtLeast(rMin)
            ApneaTableStep(
                apneaDurationMs = tHold.toLong(),
                ventilationDurationMs = rN.toLong(),
                roundNumber = round
            )
        }
        return ApneaTable(ApneaTableType.CO2, steps, pbMs)
    }

    fun generateO2Table(pbMs: Long, length: TableLength, difficulty: TableDifficulty): ApneaTable {
        val cfg = buildConfig(length, difficulty)
        val tRest = cfg.o2RestSec * 1_000L
        val hMax = pbMs * cfg.o2MaxHoldPercent.toDouble()
        val h1 = pbMs * cfg.o2FirstHoldPercent.toDouble()
        val n = cfg.rounds
        val deltaH = if (n > 1) (hMax - h1) / (n - 1) else 0.0

        val steps = (1..n).map { round ->
            val hN = h1 + ((round - 1) * deltaH)
            ApneaTableStep(
                apneaDurationMs = hN.toLong(),
                ventilationDurationMs = tRest,
                roundNumber = round
            )
        }
        return ApneaTable(ApneaTableType.O2, steps, pbMs)
    }

    fun getConfig(length: TableLength, difficulty: TableDifficulty): TableConfig =
        buildConfig(length, difficulty)

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildConfig(length: TableLength, difficulty: TableDifficulty): TableConfig {
        val rounds = when (length) {
            TableLength.SHORT  -> 4
            TableLength.MEDIUM -> 8
            TableLength.LONG   -> 12
        }
        return when (difficulty) {
            TableDifficulty.EASY -> TableConfig(
                length = length,
                difficulty = difficulty,
                rounds = rounds,
                co2HoldPercent = 0.40f,
                co2RestMinSec = 30,
                o2MaxHoldPercent = 0.70f,
                o2FirstHoldPercent = 0.40f,
                o2RestSec = 120
            )
            TableDifficulty.MEDIUM -> TableConfig(
                length = length,
                difficulty = difficulty,
                rounds = rounds,
                co2HoldPercent = 0.50f,
                co2RestMinSec = 15,
                o2MaxHoldPercent = 0.80f,
                o2FirstHoldPercent = 0.40f,
                o2RestSec = 120
            )
            TableDifficulty.HARD -> TableConfig(
                length = length,
                difficulty = difficulty,
                rounds = rounds,
                co2HoldPercent = 0.55f,
                co2RestMinSec = 10,
                o2MaxHoldPercent = 0.85f,
                o2FirstHoldPercent = 0.40f,
                o2RestSec = 150
            )
        }
    }
}
