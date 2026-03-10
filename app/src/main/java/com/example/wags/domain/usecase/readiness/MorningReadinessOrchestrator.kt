package com.example.wags.domain.usecase.readiness

import com.example.wags.data.repository.MorningReadinessRepository
import com.example.wags.domain.model.HooperIndex
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
        // 1. Artifact correction
        val supineCorrected = artifactCorrection.execute(
            input.supineBuffer.map { it.intervalMs }
        )
        val standingCorrected = artifactCorrection.execute(
            input.standingBuffer.map { it.intervalMs }
        )

        val supineArtifactPct = if (input.supineBuffer.isNotEmpty()) {
            supineCorrected.artifactCount.toFloat() / input.supineBuffer.size * 100f
        } else 0f

        val standingArtifactPct = if (input.standingBuffer.isNotEmpty()) {
            standingCorrected.artifactCount.toFloat() / input.standingBuffer.size * 100f
        } else 0f

        // 2. HRV metrics
        val supineHrv = timeDomainCalculator.calculate(
            supineCorrected.correctedNn,
            supineCorrected.artifactMask
        )
        val standingHrv = timeDomainCalculator.calculate(
            standingCorrected.correctedNn,
            standingCorrected.artifactMask
        )

        // 3. Supine RHR (mean RR → bpm)
        val supineRhr = if (supineCorrected.correctedNn.isNotEmpty()) {
            val meanRr = supineCorrected.correctedNn.average()
            if (meanRr > 0) (60_000.0 / meanRr).toInt() else 60
        } else 60

        // 4. Orthostatic metrics (rebuild RrIntervals with corrected values)
        val correctedStandingRr = rebuildRrIntervals(
            input.standingBuffer, standingCorrected.correctedNn
        )
        val orthostasisMetrics = ohrrCalculator.calculate(
            peakStandHr = input.peakStandHr,
            standingRrIntervals = correctedStandingRr
        )

        // 5. Respiratory rate (from supine buffer)
        val respResult = respiratoryRateCalculator.calculate(input.supineBuffer)

        // 6. Fetch baselines
        val chronicHistory = repository.getChronicBaselineLnRmssd()
        val acuteHistory   = repository.getAcuteBaselineLnRmssd()
        val rhrHistory     = repository.getSupineRhr90Days()

        // 7. Score calculation
        val scoreInput = MorningReadinessScoreCalculator.Input(
            supineHrvMetrics          = supineHrv,
            standingHrvMetrics        = standingHrv,
            supineRhr                 = supineRhr,
            peakStandHr               = input.peakStandHr,
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
            peakStandHr              = input.peakStandHr,
            thirtyFifteenRatio       = orthostasisMetrics.thirtyFifteenRatio,
            ohrrAt20s                = orthostasisMetrics.ohrrAt20sPercent,
            ohrrAt60s                = orthostasisMetrics.ohrrAt60sPercent,
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
}
