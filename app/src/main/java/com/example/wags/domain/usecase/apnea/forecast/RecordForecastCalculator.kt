package com.example.wags.domain.usecase.apnea.forecast

import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.trophyCount
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Orchestrator: takes records + current settings, fits the
 * log-linear regression, and computes P(next hold > PB) for each of the
 * 32 sub-combinations of the 5 settings.
 *
 * Works for any apnea record type (Free Hold, Progressive O₂, Min Breath)
 * — the caller is responsible for filtering records to the desired type.
 *
 * Public entry point: [compute].
 */
object RecordForecastCalculator {

    /** Minimum total records to produce a forecast (user-set to 5). */
    private const val MIN_TOTAL_RECORDS = 5

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
     * @param records  Records of the desired type (caller should filter by tableType and drillParamValue).
     * @param settings The 5 settings currently selected on the apnea screen.
     * @param nowEpochMs  Current epoch-ms (used to compute the trend term for the pending hold).
     * @param recordLabel  Label for records in the dialog (e.g. "holds", "sessions").
     * @param ceilingMs  Optional physical maximum for durationMs (e.g. session duration for Min Breath).
     *                   When a category's PB equals or exceeds the ceiling, its probability is 0%
     *                   because it cannot be beaten. Also allows a definitive 0% forecast even with
     *                   fewer than [MIN_TOTAL_RECORDS] records.
     * @return RecordForecast with all 32 category probabilities.
     */
    fun compute(
        records: List<ApneaRecordEntity>,
        settings: ForecastSettings,
        nowEpochMs: Long,
        recordLabel: String = "holds",
        ceilingMs: Long? = null
    ): RecordForecast {
        val filtered = records.filter { it.durationMs >= MIN_DURATION_MS }
        val n = filtered.size

        // If a ceiling exists and the global best has reached it, we can return
        // a definitive 0% forecast without needing MIN_TOTAL_RECORDS records.
        if (ceilingMs != null && filtered.isNotEmpty()) {
            val globalBest = filtered.maxOf { it.durationMs }
            if (globalBest >= ceilingMs) {
                return ceilingReachedForecast(filtered, settings, ceilingMs, recordLabel)
            }
        }

        if (n < MIN_TOTAL_RECORDS) {
            return RecordForecast(
                status = ForecastStatus.InsufficientData,
                exactProbability = 0f,
                categories = emptyList(),
                totalRecords = n,
                confidence = ForecastConfidence.LOW,
                recordLabel = recordLabel
            )
        }

        val firstTs = filtered.minOf { it.timestamp }
        val daysSinceFirst = max(0.0, (nowEpochMs - firstTs) / 86_400_000.0)

        // ── Fit OLS model ─────────────────────────────────────────────────────
        val designResult = FreeHoldFeatureExtractor.buildDesignMatrix(filtered, firstTs)
            ?: return insufficientData(n, recordLabel)

        val (X, y) = designResult
        val fit = OlsRegression.fit(X, y)
            ?: return insufficientData(n, recordLabel)

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
            val probability = when {
                bestMs == null -> 1.0f  // no record → 100%
                ceilingMs != null && bestMs >= ceilingMs -> 0.0f  // hit ceiling → impossible to beat
                else -> {
                    val recordLogSec = ln(bestMs / 1000.0)
                    val z = (recordLogSec - muLogSec) / sigmaPred
                    val p = NormalCdf.upperTail(z)
                    p.coerceIn(0.0, 1.0).toFloat()
                }
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
            totalRecords = n,
            confidence = confidence,
            recordLabel = recordLabel
        )
    }

    /**
     * Compute the exact-combo probability for every setting combination
     * (with time-of-day fixed), returning them sorted by probability descending.
     * Used by the "auto set" feature to find the best settings to use.
     *
     * @param records  Records of the desired type (caller should filter by tableType).
     * @param fixedTimeOfDay  Time-of-day to keep fixed (cannot be changed by auto-set).
     * @param nowEpochMs  Current epoch-ms.
     * @return List of [SettingsWithProbability] sorted by probability descending.
     */
    fun computeBestSettings(
        records: List<ApneaRecordEntity>,
        fixedTimeOfDay: String,
        nowEpochMs: Long
    ): List<SettingsWithProbability> {
        val filtered = records.filter { it.durationMs >= MIN_DURATION_MS }
        if (filtered.size < MIN_TOTAL_RECORDS) return emptyList()

        val firstTs = filtered.minOf { it.timestamp }
        val daysSinceFirst = max(0.0, (nowEpochMs - firstTs) / 86_400_000.0)

        val designResult = FreeHoldFeatureExtractor.buildDesignMatrix(filtered, firstTs)
            ?: return emptyList()

        val (X, y) = designResult
        val fit = OlsRegression.fit(X, y) ?: return emptyList()

        val lungVolumes = listOf("FULL", "EMPTY", "PARTIAL")
        val prepTypes = listOf("NO_PREP", "RESONANCE", "HYPER")
        val postures = listOf("SITTING", "LAYING")
        val audios = listOf("SILENCE", "MUSIC", "MOVIE", "GUIDED")

        val results = mutableListOf<SettingsWithProbability>()

        for (lv in lungVolumes) {
            for (pt in prepTypes) {
                for (pos in postures) {
                    for (aud in audios) {
                        val settings = ForecastSettings(
                            lungVolume = lv,
                            prepType = pt,
                            timeOfDay = fixedTimeOfDay,
                            posture = pos,
                            audio = aud
                        )
                        val xRow = FreeHoldFeatureExtractor.encodePendingHold(settings, daysSinceFirst)
                        val (muLogSec, predVariance) = OlsRegression.predict(xRow, fit)
                        val sigmaPred = sqrt(max(1e-6, predVariance))

                        // Find the exact-combo PB
                        val bestMs = findBestForSubCombo(
                            filtered,
                            setOf(0, 1, 2, 3, 4),
                            listOf(lv, pt, fixedTimeOfDay, pos, aud)
                        )

                        val probability = if (bestMs == null) {
                            1.0f
                        } else {
                            val recordLogSec = ln(bestMs / 1000.0)
                            val z = (recordLogSec - muLogSec) / sigmaPred
                            NormalCdf.upperTail(z).coerceIn(0.0, 1.0).toFloat()
                        }

                        results.add(SettingsWithProbability(settings, probability))
                    }
                }
            }
        }

        // Sort by probability descending
        results.sortByDescending { it.probability }
        return results
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun insufficientData(n: Int, recordLabel: String = "holds") = RecordForecast(
        status = ForecastStatus.InsufficientData,
        exactProbability = 0f,
        categories = emptyList(),
        totalRecords = n,
        confidence = ForecastConfidence.LOW,
        recordLabel = recordLabel
    )

    /**
     * Build a forecast where the global best has reached the physical ceiling
     * (e.g. 100% hold time in a Min Breath session). Every category whose PB
     * equals or exceeds the ceiling gets 0% probability; categories with no
     * record at all still get 100% (no prior record to beat).
     */
    private fun ceilingReachedForecast(
        records: List<ApneaRecordEntity>,
        settings: ForecastSettings,
        ceilingMs: Long,
        recordLabel: String
    ): RecordForecast {
        val settingValues = listOf(
            settings.lungVolume, settings.prepType, settings.timeOfDay,
            settings.posture, settings.audio
        )

        val categories = mutableListOf<CategoryForecast>()

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

            val label = if (fixedCount == 0) "All settings"
            else fixedIndices.sorted().map { i ->
                SETTING_DISPLAYS[i][settingValues[i]] ?: settingValues[i]
            }.joinToString(" · ")

            val bestMs = findBestForSubCombo(records, fixedIndices, settingValues)

            val probability = when {
                bestMs == null -> 1.0f
                bestMs >= ceilingMs -> 0.0f
                else -> 1.0f  // below ceiling but insufficient data for regression → optimistic
            }

            categories.add(CategoryForecast(
                category = category,
                trophyCount = category.trophyCount(),
                label = label,
                recordMs = bestMs,
                probability = probability,
                confidence = ForecastConfidence.LOW
            ))
        }

        categories.sortWith(compareByDescending<CategoryForecast> { it.probability }.thenBy { it.trophyCount })

        val exactEntry = categories.find { it.category == PersonalBestCategory.EXACT }
        val exactProb = exactEntry?.probability ?: 0.0f

        return RecordForecast(
            status = ForecastStatus.Ready,
            exactProbability = exactProb,
            categories = categories,
            totalRecords = records.size,
            confidence = ForecastConfidence.LOW,
            recordLabel = recordLabel
        )
    }

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
