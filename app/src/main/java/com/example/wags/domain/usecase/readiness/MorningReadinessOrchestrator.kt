package com.example.wags.domain.usecase.readiness

import com.example.wags.data.repository.MorningReadinessRepository
import com.example.wags.domain.model.HooperIndex
import com.example.wags.domain.model.HrvMetrics
import com.example.wags.domain.model.MorningReadinessResult
import com.example.wags.domain.model.RrInterval
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import javax.inject.Inject

/**
 * Orchestrates the full Morning Readiness computation pipeline.
 *
 * Call [compute] once the FSM reaches CALCULATING state.
 * Should be invoked on a background dispatcher (MathDispatcher).
 *
 * Pipeline:
 *   1. Artifact correction (supine + standing)
 *   2. HRV metrics (supine + standing)
 *   3. Supine RHR
 *   4. Orthostatic metrics
 *   5. Respiratory rate
 *   6. Fetch baselines from repository
 *   7. Score calculation → MorningReadinessResult
 */
class MorningReadinessOrchestrator @Inject constructor(
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val timeDomainCalculator: TimeDomainHrvCalculator,
    private val ohrrCalculator: OhrrCalculator,
    private val respiratoryRateCalculator: RespiratoryRateCalculator,
    private val scoreCalculator: MorningReadinessScoreCalculator,
    private val repository: MorningReadinessRepository
) {
    data class Input(
        val supineBuffer: List<RrInterval>,
        val standingBuffer: List<RrInterval>,
        val peakStandHr: Int,
        val hooperIndex: HooperIndex?,
        val userAgeYears: Int = 35
    )

    suspend fun compute(input: Input): MorningReadinessResult {
        // Standing is considered skipped if the buffer is empty OR has too few
        // intervals to produce meaningful HRV metrics. This handles the case
        // where the user pressed "Skip Standing" shortly after the standing
        // phase began — the FSM clears the buffer, but as a safety net we also
        // guard against tiny buffers that would crash TimeDomainHrvCalculator
        // (which requires >= 2 NN intervals).
        val standingSkipped = input.standingBuffer.size < MIN_STANDING_INTERVALS

        // 1. Artifact correction
        val supineCorrected = artifactCorrection.execute(
            input.supineBuffer.map { it.intervalMs }
        )
        // Only run artifact correction on standing data if the user actually stood.
        val standingCorrected = if (!standingSkipped) {
            artifactCorrection.execute(input.standingBuffer.map { it.intervalMs })
        } else null

        val supineArtifactPct = if (input.supineBuffer.isNotEmpty()) {
            supineCorrected.artifactCount.toFloat() / input.supineBuffer.size * 100f
        } else 0f

        val standingArtifactPct = if (standingCorrected != null && input.standingBuffer.isNotEmpty()) {
            standingCorrected.artifactCount.toFloat() / input.standingBuffer.size * 100f
        } else 0f

        // 2. HRV metrics
        val supineHrv = timeDomainCalculator.calculate(
            supineCorrected.correctedNn,
            supineCorrected.artifactMask
        )
        // Standing HRV is null when the user skipped standing — we must not fabricate
        // zero-valued metrics that would pollute history charts and comparisons.
        val standingHrv: HrvMetrics? = if (standingCorrected != null) {
            timeDomainCalculator.calculate(
                standingCorrected.correctedNn,
                standingCorrected.artifactMask
            )
        } else null

        // 3. Supine RHR (mean RR → bpm)
        val supineRhr = if (supineCorrected.correctedNn.isNotEmpty()) {
            val meanRr = supineCorrected.correctedNn.average()
            if (meanRr > 0) (60_000.0 / meanRr).toInt() else 60
        } else 60

        // 4. Orthostatic metrics — only when standing data exists.
        // peakStandHr is also null when skipped so the score calculator treats it as unknown.
        val orthostasisMetrics = if (!standingSkipped && standingCorrected != null) {
            val correctedStandingRr = rebuildRrIntervals(
                input.standingBuffer, standingCorrected.correctedNn
            )
            ohrrCalculator.calculate(
                peakStandHr = input.peakStandHr ?: 0,
                standingRrIntervals = correctedStandingRr
            )
        } else null

        // 5. Respiratory rate (from supine buffer)
        val respResult = respiratoryRateCalculator.calculate(input.supineBuffer)

        // 6. Fetch baselines
        val chronicHistory = repository.getChronicBaselineLnRmssd()
        val acuteHistory   = repository.getAcuteBaselineLnRmssd()
        val rhrHistory     = repository.getSupineRhr90Days()

        // 7. Score calculation
        // peakStandHr is null when standing was skipped — pass null so the score calculator
        // and result record correctly reflect that no standing data was collected.
        val peakStandHrOrNull: Int? = if (standingSkipped) null else input.peakStandHr.takeIf { it > 0 }
        val scoreInput = MorningReadinessScoreCalculator.Input(
            supineHrvMetrics          = supineHrv,
            standingHrvMetrics        = standingHrv,
            supineRhr                 = supineRhr,
            peakStandHr               = peakStandHrOrNull,
            orthostasisMetrics        = orthostasisMetrics,
            hooperIndex               = input.hooperIndex,
            respiratoryRateBpm        = respResult?.respiratoryRateBpm,
            slowBreathingFlagged      = respResult?.slowBreathingFlagged ?: false,
            artifactPercentSupine     = supineArtifactPct,
            artifactPercentStanding   = standingArtifactPct,
            userAgeYears              = input.userAgeYears,
            chronicLnRmssdHistory     = chronicHistory,
            acuteLnRmssdHistory       = acuteHistory,
            rhrHistory                = rhrHistory
        )
        val scoreOutput = scoreCalculator.calculate(scoreInput)

        return MorningReadinessResult(
            timestamp                = System.currentTimeMillis(),
            supineHrvMetrics         = supineHrv,
            standingHrvMetrics       = standingHrv,
            supineRhr                = supineRhr,
            peakStandHr              = peakStandHrOrNull,
            thirtyFifteenRatio       = orthostasisMetrics?.thirtyFifteenRatio,
            ohrrAt20s                = orthostasisMetrics?.ohrrAt20sPercent,
            ohrrAt60s                = orthostasisMetrics?.ohrrAt60sPercent,
            respiratoryRateBpm       = respResult?.respiratoryRateBpm,
            slowBreathingFlagged     = respResult?.slowBreathingFlagged ?: false,
            hooperIndex              = input.hooperIndex?.total,
            hooperSleep              = input.hooperIndex?.sleep,
            hooperFatigue            = input.hooperIndex?.fatigue,
            hooperSoreness           = input.hooperIndex?.soreness,
            hooperStress             = input.hooperIndex?.stress,
            artifactPercentSupine    = supineArtifactPct,
            artifactPercentStanding  = standingArtifactPct,
            readinessScore           = scoreOutput.readinessScore,
            readinessColor           = scoreOutput.readinessColor,
            hvBaseScore              = scoreOutput.hrvBaseScore,
            orthoMultiplier          = scoreOutput.orthoMultiplier,
            cvPenaltyApplied         = scoreOutput.cvPenaltyApplied,
            rhrLimiterApplied        = scoreOutput.rhrLimiterApplied,
            skinTempLimiterApplied   = false
        )
    }

    /**
     * Rebuilds a [RrInterval] list using corrected interval values
     * but preserving original timestamps and artifact flags.
     */
    private fun rebuildRrIntervals(
        original: List<RrInterval>,
        correctedNn: DoubleArray
    ): List<RrInterval> {
        val size = minOf(original.size, correctedNn.size)
        return (0 until size).map { i ->
            original[i].copy(intervalMs = correctedNn[i])
        }
    }

    companion object {
        /**
         * Minimum number of standing RR intervals required to treat the standing
         * phase as valid. Below this threshold the orchestrator treats standing as
         * skipped. 10 matches the artifact correction minimum and prevents
         * TimeDomainHrvCalculator from crashing on tiny buffers.
         */
        private const val MIN_STANDING_INTERVALS = 10
    }
}
