package com.example.wags.domain.usecase.hrv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Regression tests for the 2026-10-22 dropped-beat fix.
 *
 * Scenario under test (observed in a real capture): a 2-minute test at
 * ~62 BPM produced RMSSD ≈ 194 ms with pNN50 = 25% because the legacy
 * pipeline DELETED merged intervals (two beats delivered as one ~2000 ms
 * RR), splicing non-adjacent beats together.
 */
class ArtifactCorrectionPipelineTest {

    private val useCase = ArtifactCorrectionUseCase()
    private val calculator = TimeDomainHrvCalculator()

    /** Clean sinus-rhythm-ish RR series: ~1000 ms ± 30 ms respiratory swing. */
    private fun cleanSeries(n: Int, baseMs: Double = 1000.0): List<Double> =
        (0 until n).map { baseMs + 30.0 * sin(2.0 * Math.PI * it / 10.0) }

    @Test
    fun `dropped beats do not inflate rmssd`() {
        val clean = cleanSeries(120).toMutableList()
        // Drop 15 beats at random-ish positions (merge with predecessor)
        val dropIdx = listOf(10, 25, 41, 58, 66, 79, 88, 97, 103, 112, 5, 33, 71, 90, 118)
        for (i in dropIdx.sortedDescending()) {
            val merged = clean[i] + clean[i - 1]
            clean.removeAt(i)
            clean[i - 1] = merged
        }

        val corrected = useCase.execute(clean)
        val hrv = calculator.calculate(corrected.correctedNn, corrected.artifactMask)

        // Without the fix RMSSD landed ~190+ ms; with reconstruction it must
        // stay close to the clean series' RMSSD (~40 ms) — allow generous 25 ms.
        assertTrue(
            "RMSSD inflated to ${hrv.rmssdMs} ms after dropped beats",
            hrv.rmssdMs < 65.0
        )
    }

    @Test
    fun `clean series is preserved`() {
        val clean = cleanSeries(120)
        val corrected = useCase.execute(clean)
        val hrv = calculator.calculate(corrected.correctedNn, corrected.artifactMask)

        // RMSSD of successive sine diffs ≈ 30 * 2 * sin(pi/10) ≈ 18.5 ms;
        // the pipeline must not distort a clean series.
        assertTrue(abs(hrv.rmssdMs - 18.5) < 6.0)
        assertEquals(1.0, corrected.validRatio, 0.05)
    }

    @Test
    fun `single missed beat split restores beat count`() {
        val series = cleanSeries(60).toMutableList()
        series[30] = series[30] + series[31] // merge two into one
        series.removeAt(31)

        val result = MissedBeatReconstructor.reconstruct(series)
        assertEquals(60, result.rr.size)
        assertEquals(1, result.reconstructedBeats)
    }

    @Test
    fun `quotient filter masks residual giant step`() {
        // Series with one absurd jump that survives earlier stages
        val series = cleanSeries(60).toMutableList()
        series[40] = 400.0 // 2.5x drop — ratio far outside 0.8-1.2

        val corrected = useCase.execute(series)
        val hrv = calculator.calculate(corrected.correctedNn, corrected.artifactMask)

        // The 400 ms beat's diffs must be excluded, RMSSD stays modest
        assertTrue("RMSSD inflated to ${hrv.rmssdMs}", hrv.rmssdMs < 80.0)
    }
}
