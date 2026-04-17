package com.example.wags.domain.usecase.apnea.forecast

import com.example.wags.data.db.entity.ApneaRecordEntity
import kotlin.math.ln
import kotlin.math.max

/**
 * Converts free-hold records and pending-hold settings into the feature vectors
 * used by the OLS regression.
 *
 * Feature vector layout (12 columns):
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
 * Reference levels (all dummies = 0): EMPTY, NO_PREP, MORNING, LAYING, SILENCE.
 */
object FreeHoldFeatureExtractor {

    const val FEATURE_COUNT = 12

    /** Minimum hold duration to include in the model (ms). Discards accidental taps. */
    private const val MIN_DURATION_MS = 10_000L

    /**
     * Build the design matrix X and response vector y from a list of free-hold records.
     *
     * @param records  All free-hold records (tableType == null already filtered by caller).
     * @param firstHoldTimestamp  Epoch-ms of the earliest record (used to compute the trend term).
     * @return Pair(X, y) where X is n×p and y is n-vector of log-seconds,
     *         or null if fewer than MIN_RECORDS records pass the duration filter.
     */
    fun buildDesignMatrix(
        records: List<ApneaRecordEntity>,
        firstHoldTimestamp: Long
    ): Pair<Array<DoubleArray>, DoubleArray>? {
        val filtered = records.filter { it.durationMs >= MIN_DURATION_MS }
        if (filtered.isEmpty()) return null

        val n = filtered.size
        val X = Array(n) { DoubleArray(FEATURE_COUNT) }
        val y = DoubleArray(n)

        for (i in filtered.indices) {
            val r = filtered[i]
            X[i] = encodeRow(
                lungVolume = r.lungVolume,
                prepType = r.prepType,
                timeOfDay = r.timeOfDay,
                posture = r.posture,
                audio = r.audio,
                daysSinceFirst = max(0.0, (r.timestamp - firstHoldTimestamp) / 86_400_000.0)
            )
            y[i] = ln(r.durationMs / 1000.0)
        }
        return Pair(X, y)
    }

    /**
     * Encode a "pending hold" (the settings the user is about to use) into a
     * feature vector for prediction.
     */
    fun encodePendingHold(
        settings: ForecastSettings,
        daysSinceFirstHold: Double
    ): DoubleArray = encodeRow(
        lungVolume = settings.lungVolume,
        prepType = settings.prepType,
        timeOfDay = settings.timeOfDay,
        posture = settings.posture,
        audio = settings.audio,
        daysSinceFirst = daysSinceFirstHold
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
        daysSinceFirst: Double
    ): DoubleArray {
        return DoubleArray(FEATURE_COUNT) { 0.0 }.also { x ->
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
        }
    }
}
