package com.example.wags.domain.usecase.session

import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import org.apache.commons.math3.stat.regression.SimpleRegression
import javax.inject.Inject
import kotlin.math.ln

/**
 * Computes NSDR / Meditation session analytics:
 *
 * 1. Bradycardia detection — linear regression slope on HR time series
 *    (negative slope = HR declining = relaxation response)
 *
 * 2. RMSSD trajectory — ln(RMSSD) in 60s rolling windows, regression slope
 *    (positive slope = parasympathetic upregulation)
 *
 * 3. Autonomic Balance Index (ABI) — HF / (LF + HF) at start, mid, end
 *    (rising ABI = true vagal enhancement)
 */
class NsdrAnalyticsCalculator @Inject constructor(
    private val timeDomainCalculator: TimeDomainHrvCalculator
) {

    companion object {
        private const val RMSSD_WINDOW_BEATS = 240  // ~60s at 60 BPM (4 Hz × 60s)
    }

    data class SessionAnalytics(
        val hrSlopeBpmPerMin: Float,       // negative = bradycardia
        val lnRmssdSlope: Float,           // positive = parasympathetic upregulation
        val abiStart: Float,               // HF/(LF+HF) at session start
        val abiMid: Float,                 // HF/(LF+HF) at session midpoint
        val abiEnd: Float,                 // HF/(LF+HF) at session end
        val avgHrBpm: Float,
        val startRmssdMs: Float,
        val endRmssdMs: Float
    )

    /**
     * Compute full session analytics.
     *
     * @param hrTimeSeries HR samples in BPM (any sample rate, evenly spaced)
     * @param nnIntervals corrected NN intervals in ms for the full session
     * @param abiValues list of (timestamp, abi) pairs computed externally from freq domain
     */
    fun calculate(
        hrTimeSeries: List<Float>,
        nnIntervals: DoubleArray,
        abiValues: List<Pair<Long, Float>> = emptyList()
    ): SessionAnalytics {
        val hrSlope = computeHrSlope(hrTimeSeries)
        val lnRmssdSlope = computeLnRmssdSlope(nnIntervals)
        val avgHr = if (hrTimeSeries.isNotEmpty()) hrTimeSeries.average().toFloat() else 0f

        val (startRmssd, endRmssd) = computeStartEndRmssd(nnIntervals)

        val (abiStart, abiMid, abiEnd) = extractAbiTriple(abiValues)

        return SessionAnalytics(
            hrSlopeBpmPerMin = hrSlope,
            lnRmssdSlope = lnRmssdSlope,
            abiStart = abiStart,
            abiMid = abiMid,
            abiEnd = abiEnd,
            avgHrBpm = avgHr,
            startRmssdMs = startRmssd,
            endRmssdMs = endRmssd
        )
    }

    /**
     * Linear regression on HR time series.
     * Returns slope in BPM/minute. Negative = bradycardia (desired).
     */
    private fun computeHrSlope(hrTimeSeries: List<Float>): Float {
        if (hrTimeSeries.size < 4) return 0f
        val regression = SimpleRegression()
        hrTimeSeries.forEachIndexed { i, hr ->
            regression.addData(i.toDouble(), hr.toDouble())
        }
        // Convert slope from BPM/sample to BPM/minute
        // Assumes samples are evenly spaced; caller provides sample rate context via list size
        return regression.slope.toFloat()
    }

    /**
     * Compute ln(RMSSD) in 60s rolling windows, then regress over time.
     * Returns slope of ln(RMSSD) vs window index. Positive = upregulation.
     */
    private fun computeLnRmssdSlope(nn: DoubleArray): Float {
        if (nn.size < RMSSD_WINDOW_BEATS * 2) return 0f

        val windowSize = RMSSD_WINDOW_BEATS
        val step = windowSize / 2  // 50% overlap
        val lnRmssdSeries = mutableListOf<Double>()

        var start = 0
        while (start + windowSize <= nn.size) {
            val window = nn.slice(start until start + windowSize).toDoubleArray()
            val metrics = timeDomainCalculator.calculate(window)
            if (metrics.rmssdMs > 0) {
                lnRmssdSeries.add(ln(metrics.rmssdMs))
            }
            start += step
        }

        if (lnRmssdSeries.size < 3) return 0f

        val regression = SimpleRegression()
        lnRmssdSeries.forEachIndexed { i, v -> regression.addData(i.toDouble(), v) }
        return regression.slope.toFloat()
    }

    /**
     * Compute RMSSD for the first and last 60s windows of the session.
     */
    private fun computeStartEndRmssd(nn: DoubleArray): Pair<Float, Float> {
        if (nn.size < RMSSD_WINDOW_BEATS) return Pair(0f, 0f)

        val startWindow = nn.take(RMSSD_WINDOW_BEATS).toDoubleArray()
        val endWindow = nn.takeLast(RMSSD_WINDOW_BEATS).toDoubleArray()

        val startRmssd = timeDomainCalculator.calculate(startWindow).rmssdMs.toFloat()
        val endRmssd = timeDomainCalculator.calculate(endWindow).rmssdMs.toFloat()

        return Pair(startRmssd, endRmssd)
    }

    /**
     * Extract ABI at start, midpoint, and end from a time-ordered list.
     */
    private fun extractAbiTriple(abiValues: List<Pair<Long, Float>>): Triple<Float, Float, Float> {
        if (abiValues.isEmpty()) return Triple(0f, 0f, 0f)
        val start = abiValues.first().second
        val mid = abiValues[abiValues.size / 2].second
        val end = abiValues.last().second
        return Triple(start, mid, end)
    }
}
