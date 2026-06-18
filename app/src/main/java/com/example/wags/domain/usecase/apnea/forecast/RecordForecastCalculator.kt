package com.example.wags.domain.usecase.apnea.forecast

import android.util.Log
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.trophyCount
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "ForecastCalc"

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
     * @param records  Records of the desired type. For Free Hold, the caller filters by
     *                  tableType only. For parameterized drills (Progressive O₂, Min Breath)
     *                  the caller should pass ALL records of that drill type (NOT pre-filtered
     *                  by drillParamValue) and supply [drillParam]; the parameter is then a
     *                  regression feature so the whole history feeds one fit.
     * @param settings The 5 settings currently selected on the apnea screen.
     * @param nowEpochMs  Current epoch-ms (used to compute the trend term for the pending hold).
     * @param recordLabel  Label for records in the dialog (e.g. "holds", "sessions").
     * @param ceilingMs  Optional physical maximum for durationMs (e.g. session duration for Min Breath).
     *                   When a category's PB equals or exceeds the ceiling, its probability is 0%
     *                   because it cannot be beaten. Also allows a definitive 0% forecast even with
     *                   fewer than [MIN_TOTAL_RECORDS] records.
     * @param drillParam  Optional drill parameter (breath-period sec / session-duration sec).
     *                   When non-null: (1) it is added as a regression feature so all parameter
     *                   buckets share one fit, (2) the pending-hold prediction is made at this
     *                   value, and (3) per-category PB lookups only consider records whose
     *                   drillParamValue matches this value — because a record at one parameter
     *                   is a different record than at another.
     * @return RecordForecast with all 32 category probabilities.
     */
    fun compute(
        records: List<ApneaRecordEntity>,
        settings: ForecastSettings,
        nowEpochMs: Long,
        recordLabel: String = "holds",
        ceilingMs: Long? = null,
        drillParam: Int? = null
    ): RecordForecast {
        Log.d(TAG, "=== ForecastCalc.compute() START ===")
        Log.d(TAG, "Input: records.size=${records.size}, drillParam=$drillParam, settings=$settings, ceilingMs=$ceilingMs")
        
        // For parameterized drills, only use same-param records for both regression
        // and PB lookups. Training on mixed-param data makes predictions unreliable
        // when compared against same-param PB thresholds.
        val sameParamRecords = if (drillParam != null) {
            records.filter { it.drillParamValue == drillParam }
        } else {
            records
        }
        Log.d(TAG, "sameParamRecords.size=${sameParamRecords.size}")
        
        // Records eligible for the regression fit (duration filter only).
        val regressionRecords = sameParamRecords.filter { it.durationMs >= MIN_DURATION_MS }
        // Records used for per-category PB lookups.
        val pbRecords = regressionRecords
        val n = regressionRecords.size
        Log.d(TAG, "regressionRecords.size=$n (after MIN_DURATION_MS filter)")

        // If a ceiling exists and the best at this parameter has reached it, we can return
        // a definitive 0% forecast without needing MIN_TOTAL_RECORDS records.
        if (ceilingMs != null && pbRecords.isNotEmpty()) {
            val globalBest = pbRecords.maxOf { it.durationMs }
            Log.d(TAG, "Ceiling check: globalBest=$globalBest, ceilingMs=$ceilingMs")
            if (globalBest >= ceilingMs) {
                Log.d(TAG, "Returning ceilingReachedForecast (globalBest >= ceilingMs)")
                return ceilingReachedForecast(pbRecords, settings, ceilingMs, recordLabel)
            }
        }

        if (n < MIN_TOTAL_RECORDS) {
            Log.d(TAG, "Insufficient data: n=$n < MIN_TOTAL_RECORDS=$MIN_TOTAL_RECORDS, calling insufficientDataForecast")
            // Not enough data for regression, but we can still give useful forecasts:
            // categories with no record → 100% (any hold sets a new PB),
            // categories with a record → 50% (neutral estimate).
            return insufficientDataForecast(pbRecords, settings, recordLabel)
        }

        val firstTs = regressionRecords.minOf { it.timestamp }
        val daysSinceFirst = max(0.0, (nowEpochMs - firstTs) / 86_400_000.0)
        Log.d(TAG, "firstTs=$firstTs, daysSinceFirst=$daysSinceFirst")

        // ── Fit OLS model ─────────────────────────────────────────────────────
        val designResult = FreeHoldFeatureExtractor.buildDesignMatrix(
            regressionRecords, firstTs, includeDrillParam = drillParam != null
        ) ?: return insufficientDataForecast(pbRecords, settings, recordLabel)

        val (X, y) = designResult
        val fit = OlsRegression.fit(X, y)
            ?: return insufficientDataForecast(pbRecords, settings, recordLabel)
        Log.d(TAG, "OLS fit successful: X.size=${X.size}, y.size=${y.size}")

        // ── Predict for the pending hold ──────────────────────────────────────
        val xPending = FreeHoldFeatureExtractor.encodePendingHold(settings, daysSinceFirst, drillParam)
        val (muLogSec, predVariance) = OlsRegression.predict(xPending, fit)
        val sigmaPred = sqrt(max(1e-6, predVariance))
        Log.d(TAG, "Prediction: muLogSec=$muLogSec, predVariance=$predVariance, sigmaPred=$sigmaPred")

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
        Log.d(TAG, "Confidence=$confidence (n=$n)")

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

            // Find best record matching this sub-combination (within the selected
            // drill parameter for parameterized drills — see pbRecords).
            val bestMs = findBestForSubCombo(pbRecords, fixedIndices, settingValues)

            // Compute probability
            // All categories use the same regression prediction (muLogSec).
            // This guarantees monotonicity: exact probability ≥ broader probability,
            // because broader PB thresholds are always ≥ exact PB thresholds
            // (more records included → best is at least as high).
            val probability = when {
                bestMs == null -> {
                    Log.d(TAG, "[$category] bestMs=null → probability=1.0")
                    1.0f  // no record → 100%
                }
                ceilingMs != null && bestMs >= ceilingMs -> {
                    Log.d(TAG, "[$category] bestMs=$bestMs >= ceilingMs=$ceilingMs → probability=0.0")
                    0.0f  // hit ceiling → impossible to beat
                }
                else -> {
                    val recordLogSec = ln(bestMs / 1000.0)
                    val z = (recordLogSec - muLogSec) / sigmaPred
                    val p = NormalCdf.upperTail(z)
                    val prob = p.coerceIn(0.0, 1.0).toFloat()
                    Log.d(TAG, "[$category] bestMs=$bestMs, recordLogSec=$recordLogSec, z=$z, p=$p, probability=$prob")
                    prob
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
        Log.d(TAG, "=== ForecastCalc.compute() END ===")
        Log.d(TAG, "Exact probability: $exactProb, exactEntry: $exactEntry")
        Log.d(TAG, "Returning RecordForecast with ${categories.size} categories")

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

    /**
     * Build a forecast when there aren't enough records for regression.
     * Categories with no existing record get 100% (any hold sets a new PB);
     * categories with an existing record also get 100% (optimistic, LOW confidence).
     * This ensures the UI never shows "not enough data" when the user has
     * never attempted the current settings — they always have a 100% chance
     * to set a new PB.
     */
    private fun insufficientDataForecast(
        records: List<ApneaRecordEntity>,
        settings: ForecastSettings,
        recordLabel: String
    ): RecordForecast {
        Log.d(TAG, "=== insufficientDataForecast() START ===")
        Log.d(TAG, "Input: records.size=${records.size}, settings=$settings, recordLabel=$recordLabel")
        
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

            // No record → 100% (any hold is a new PB); record exists → 50% (neutral estimate)
            val probability = if (bestMs == null) 1.0f else 0.5f
            Log.d(TAG, "[$category] bestMs=$bestMs, probability=$probability")

            categories.add(CategoryForecast(
                category = category,
                trophyCount = category.trophyCount(),
                label = label,
                recordMs = bestMs,
                probability = probability,
                confidence = ForecastConfidence.LOW
            ))
        }
        Log.d(TAG, "=== insufficientDataForecast() END ===")

        categories.sortWith(compareByDescending<CategoryForecast> { it.probability }.thenBy { it.trophyCount })

        val exactEntry = categories.find { it.category == PersonalBestCategory.EXACT }
        val exactProb = exactEntry?.probability ?: 1.0f

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
                bestMs == null -> 1.0f  // no record → 100% (any hold is a new PB)
                bestMs >= ceilingMs -> 0.0f  // at or above ceiling → impossible to beat
                else -> 0.5f  // record exists but below ceiling → neutral estimate
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
