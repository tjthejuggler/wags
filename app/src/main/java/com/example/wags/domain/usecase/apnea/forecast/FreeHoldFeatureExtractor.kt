package com.example.wags.domain.usecase.apnea.forecast

import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlin.math.ln
import kotlin.math.max

/**
 * Converts apnea records and pending-hold settings into the feature vectors
 * used by the OLS regression.
 *
 * Base feature vector layout (12 columns):
 *   0  intercept (always 1)
 *   1  lungVolume == FULL
 *   2  lungVolume == PARTIAL
 *   3  prepType   == RESONANCE
 *   4  prepType   == HYPER
 *   5  timeOfDay  == DAY
 *   6  timeOfDay  == NIGHT
 *   7  posture    == SITTING
 *   8  audio      == MUSIC
 *   9  audio      == MOVIE
 *  10  audio      == GUIDED
 *  11  days_since_first_hold / 365   (training trend, normalized to years)
 *
 * When [includeDrillParam] is true an extra 13th column is appended:
 *  12  drillParamValue / 60          (drill parameter, normalized to minutes-ish)
 *
 * The drill-param column is what lets Progressive O₂ (breath-period seconds) and
 * Min Breath (session-duration seconds) feed *all* of their records into a single
 * regression — instead of pre-filtering to one parameter bucket and starving the
 * fit. The model then learns how the parameter shifts expected hold time, and the
 * pending-hold prediction is made at the user's currently-selected parameter.
 *
 * Reference levels (all dummies = 0): EMPTY, NO_PREP, MORNING, LAYING, SILENCE.
 */
object FreeHoldFeatureExtractor {

    /** Base column count for Free Hold (no drill parameter). */
    const val FEATURE_COUNT = 12

    /** Column count when a drill parameter is included. */
    const val FEATURE_COUNT_WITH_PARAM = 13

    /** Normalizer for the drill-param column (keeps the coefficient well-scaled). */
    private const val DRILL_PARAM_NORM = 60.0

    /** Minimum hold duration to include in the model (ms). Discards accidental taps. */
    private const val MIN_DURATION_MS = 10_000L

    /**
     * Build the design matrix X and response vector y from a list of apnea records.
     *
     * @param records  All records of the drill type (caller filters by tableType only,
     *                  NOT by drillParamValue when [includeDrillParam] is true).
     * @param firstHoldTimestamp  Epoch-ms of the earliest record (used for the trend term).
     * @param includeDrillParam  When true, [ApneaRecordEntity.drillParamValue] is added as
     *                  a regression feature so all parameter buckets share one fit.
     * @return Pair(X, y) where X is n×p and y is n-vector of log-seconds,
     *         or null if no records pass the duration filter.
     */
    fun buildDesignMatrix(
        records: List<ApneaRecordEntity>,
        firstHoldTimestamp: Long,
        includeDrillParam: Boolean = false
    ): Pair<Array<DoubleArray>, DoubleArray>? {
        val filtered = records.filter { it.durationMs >= MIN_DURATION_MS }
        if (filtered.isEmpty()) return null

        val featureCount = if (includeDrillParam) FEATURE_COUNT_WITH_PARAM else FEATURE_COUNT
        val n = filtered.size
        val X = Array(n) { DoubleArray(featureCount) }
        val y = DoubleArray(n)

        for (i in filtered.indices) {
            val r = filtered[i]
            X[i] = encodeRow(
                lungVolume = r.lungVolume,
                prepType = r.prepType,
                timeOfDay = r.timeOfDay,
                posture = r.posture,
                audio = r.audio,
                daysSinceFirst = max(0.0, (r.timestamp - firstHoldTimestamp) / 86_400_000.0),
                drillParam = if (includeDrillParam) r.drillParamValue else null
            )
            y[i] = ln(r.durationMs / 1000.0)
        }
        return Pair(X, y)
    }

    /**
     * Encode a "pending hold" (the settings the user is about to use) into a
     * feature vector for prediction.
     *
     * @param drillParam  When non-null, the drill-param column is appended. This MUST be
     *                    consistent with how [buildDesignMatrix] was called (i.e. supply a
     *                    value here exactly when includeDrillParam was true there).
     */
    fun encodePendingHold(
        settings: ForecastSettings,
        daysSinceFirstHold: Double,
        drillParam: Int? = null
    ): DoubleArray = encodeRow(
        lungVolume = settings.lungVolume,
        prepType = settings.prepType,
        timeOfDay = settings.timeOfDay,
        posture = settings.posture,
        audio = settings.audio,
        daysSinceFirst = daysSinceFirstHold,
        drillParam = drillParam
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun encodeRow(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        daysSinceFirst: Double,
        drillParam: Int?
    ): DoubleArray {
        val featureCount = if (drillParam != null) FEATURE_COUNT_WITH_PARAM else FEATURE_COUNT
        return DoubleArray(featureCount) { 0.0 }.also { x ->
            x[0] = 1.0  // intercept
            if (lungVolume == "FULL")    x[1] = 1.0
            if (lungVolume == "PARTIAL") x[2] = 1.0
            if (prepType == "RESONANCE") x[3] = 1.0
            if (prepType == "HYPER")     x[4] = 1.0
            if (timeOfDay == "DAY")      x[5] = 1.0
            if (timeOfDay == "NIGHT")    x[6] = 1.0
            if (posture == "SITTING")    x[7] = 1.0
            if (audio == "MUSIC")        x[8] = 1.0
            if (audio == "MOVIE")        x[9] = 1.0
            if (audio == "GUIDED")       x[10] = 1.0
            x[11] = daysSinceFirst / 365.0  // normalized to years
            if (drillParam != null) {
                x[12] = drillParam / DRILL_PARAM_NORM
            }
        }
    }
}
