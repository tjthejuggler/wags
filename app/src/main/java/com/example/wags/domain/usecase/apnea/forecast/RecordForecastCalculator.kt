package com.example.wags.domain.usecase.apnea.forecast

import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.trophyCount
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Orchestrator: takes all free-hold records + current settings, fits the
 * log-linear regression, and computes P(next hold > PB) for each of the
 * 32 sub-combinations of the 5 settings.
 *
 * Public entry point: [compute].
 */
object RecordForecastCalculator {

    /** Minimum total free holds to produce a forecast (user-set to 5). */
    private const val MIN_TOTAL_HOLDS = 5

    /** Minimum hold duration to include in the model (ms). */
    private const val MIN_DURATION_MS = 10_000L

    // ── Setting display names for labels ──────────────────────────────────────
    private val LUNG_DISPLAY = mapOf("FULL" to "Full", "EMPTY" to "Empty", "PARTIAL" to "Partial")
    private val PREP_DISPLAY  = mapOf("NO_PREP" to "No Prep", "RESONANCE" to "Resonance", "HYPER" to "Hyper")
    private val TOD_DISPLAY   = mapOf("MORNING" to "Morning", "DAY" to "Day", "NIGHT" to "Night")
    private val POS_DISPLAY   = mapOf("SITTING" to "Sitting", "LAYING" to "Laying")
    private val AUD_DISPLAY   = mapOf("SILENCE" to "Silence", "MUSIC" to "Music", "MOVIE" to "Movie", "GUIDED" to "Guided")

    private val SETTING_KEYS = listOf("lungVolume", "prepType", "timeOfDay", "posture", "audio")
    private val SETTING_DISPLAYS = listOf(LUNG_DISPLAY, PREP_DISPLAY, TOD_DISPLAY, POS_DISPLAY, AUD_DISPLAY)

    /**
     * Compute the record-breaking forecast for the current settings.
     *
     * @param records  All free-hold records (caller should filter tableType == null).
     * @param settings The 5 settings currently selected on the apnea screen.
     * @param nowEpochMs  Current epoch-ms (used to compute the trend term for the pending hold).
     * @return RecordForecast with all 32 category probabilities.
     */
    fun compute(
        records: List<ApneaRecordEntity>,
        settings: ForecastSettings,
        nowEpochMs: Long
    ): RecordForecast {
        val filtered = records.filter { it.durationMs >= MIN_DURATION_MS }
        val n = filtered.size

        if (n < MIN_TOTAL_HOLDS) {
            return RecordForecast(
                status = ForecastStatus.InsufficientData,
                exactProbability = 0f,
                categories = emptyList(),
                totalFreeHolds = n,
                confidence = ForecastConfidence.LOW
            )
        }

        val firstTs = filtered.minOf { it.timestamp }
        val daysSinceFirst = max(0.0, (nowEpochMs - firstTs) / 86_400_000.0)

        // ── Fit OLS model ─────────────────────────────────────────────────────
        val designResult = FreeHoldFeatureExtractor.buildDesignMatrix(filtered, firstTs)
            ?: return insufficientData(n)

        val (X, y) = designResult
        val fit = OlsRegression.fit(X, y)
            ?: return insufficientData(n)

        // ── Predict for the pending hold ──────────────────────────────────────
        val xPending = FreeHoldFeatureExtractor.encodePendingHold(settings, daysSinceFirst)
        val (muLogSec, predVariance) = OlsRegression.predict(xPending, fit)
        val sigmaPred = sqrt(max(1e-6, predVariance))

        // ── Enumerate 32 sub-combinations ─────────────────────────────────────
        val settingValues = listOf(
            settings.lungVolume, settings.prepType, settings.timeOfDay,
            settings.posture, settings.audio
        )

        val categories = mutableListOf<CategoryForecast>()
        val confidence = when {
            n >= 150 -> ForecastConfidence.HIGH
            n >= 50  -> ForecastConfidence.MEDIUM
            else     -> ForecastConfidence.LOW
        }

        // Bitmask 0..31: bit i set → setting i is fixed
        for (mask in 0..31) {
            val fixedIndices = mutableSetOf<Int>()
            for (i in 0..4) {
                if (mask and (1 shl i) != 0) fixedIndices.add(i)
            }

            val fixedCount = fixedIndices.size
            val category = when (fixedCount) {
                5 -> PersonalBestCategory.EXACT
                4 -> PersonalBestCategory.FOUR_SETTINGS
                3 -> PersonalBestCategory.THREE_SETTINGS
                2 -> PersonalBestCategory.TWO_SETTINGS
                1 -> PersonalBestCategory.ONE_SETTING
                0 -> PersonalBestCategory.GLOBAL
                else -> continue
            }

            // Build label
            val label = if (fixedCount == 0) "All settings"
            else fixedIndices.sorted().map { i ->
                SETTING_DISPLAYS[i][settingValues[i]] ?: settingValues[i]
            }.joinToString(" · ")

            // Find best record matching this sub-combination
            val bestMs = findBestForSubCombo(filtered, fixedIndices, settingValues)

            // Compute probability
            // All categories use the same regression prediction (muLogSec).
            // This guarantees monotonicity: exact probability ≥ broader probability,
            // because broader PB thresholds are always ≥ exact PB thresholds
            // (more records included → best is at least as high).
            val probability = if (bestMs == null) {
                1.0f  // no record → 100%
            } else {
                val recordLogSec = ln(bestMs / 1000.0)
                val z = (recordLogSec - muLogSec) / sigmaPred
                val p = NormalCdf.upperTail(z)
                p.coerceIn(0.0, 1.0).toFloat()
            }

            categories.add(CategoryForecast(
                category = category,
                trophyCount = category.trophyCount(),
                label = label,
                recordMs = bestMs,
                probability = probability,
                confidence = confidence
            ))
        }

        // Sort by probability descending, then by trophy count ascending (fewer trophies first when tied)
        categories.sortWith(compareByDescending<CategoryForecast> { it.probability }.thenBy { it.trophyCount })

        // Extract exact-combo probability for the card
        val exactEntry = categories.find { it.category == PersonalBestCategory.EXACT }
        val exactProb = exactEntry?.probability ?: 1.0f

        return RecordForecast(
            status = ForecastStatus.Ready,
            exactProbability = exactProb,
            categories = categories,
            totalFreeHolds = n,
            confidence = confidence
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun insufficientData(n: Int) = RecordForecast(
        status = ForecastStatus.InsufficientData,
        exactProbability = 0f,
        categories = emptyList(),
        totalFreeHolds = n,
        confidence = ForecastConfidence.LOW
    )

    /**
     * Find the best (longest) duration among records that match the fixed
     * settings of a sub-combination. Returns null if no records match.
     */
    private fun findBestForSubCombo(
        records: List<ApneaRecordEntity>,
        fixedIndices: Set<Int>,
        settingValues: List<String>
    ): Long? {
        val settingFields = listOf(
            { r: ApneaRecordEntity -> r.lungVolume },
            { r: ApneaRecordEntity -> r.prepType },
            { r: ApneaRecordEntity -> r.timeOfDay },
            { r: ApneaRecordEntity -> r.posture },
            { r: ApneaRecordEntity -> r.audio }
        )

        var best: Long? = null
        for (r in records) {
            var match = true
            for (i in fixedIndices) {
                if (settingFields[i](r) != settingValues[i]) {
                    match = false
                    break
                }
            }
            if (match) {
                if (best == null || r.durationMs > best) {
                    best = r.durationMs
                }
            }
        }
        return best
    }
}
