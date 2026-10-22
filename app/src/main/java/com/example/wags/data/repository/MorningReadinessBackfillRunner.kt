package com.example.wags.data.repository

import android.content.Context
import android.util.Log
import com.example.wags.data.db.dao.MorningReadinessDao
import com.example.wags.di.IoDispatcher
import com.example.wags.data.db.dao.MorningReadinessTelemetryDao
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.hrv.TimeDomainHrvCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * One-shot migration companion to MIGRATION_44_45 (2026-10-22).
 *
 * The legacy HRV pipeline deleted merged (dropped-beat) RR intervals, which
 * spliced non-adjacent beats together and inflated RMSSD. For MORNING
 * readiness readings we still have per-beat telemetry (hrBpm per beat), so
 * supine metrics can be recomputed with the v2 pipeline.
 *
 * Limitations:
 *  - Telemetry stores HR rounded to whole bpm, so reconstructed RR values
 *    (60000/bpm) are quantized (~1% at 60 bpm). This is enough to strip the
 *    gross dropped-beat outliers but v2 recomputed values carry a small
 *    quantization noise floor (~5-10 ms RMSSD).
 *  - Standing metrics and orthostatic ratios are left untouched (they depend
 *    on precise timing); only supine HRV is recomputed.
 *  - HRV-readiness daily_readings have NO stored raw data, so those legacy
 *    rows keep their values, marked metricsVersion=1, with implausible rows
 *    (RMSSD > 150 ms) quality-flagged by the SQL migration.
 *
 * Runs once, guarded by a SharedPreferences flag; idempotent.
 */
@Singleton
class MorningReadinessBackfillRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val morningReadinessDao: MorningReadinessDao,
    private val telemetryDao: MorningReadinessTelemetryDao,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val timeDomainCalculator: TimeDomainHrvCalculator,
    private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "MrBackfill"
        private const val PREFS = "hrv_pipeline_v2_backfill"
        private const val KEY_DONE = "morning_v2_backfill_done"
        private const val MIN_SUPINE_BEATS = 30
        private const val IMPLAUSIBLE_RMSSD_MS = 150.0
    }

    fun runIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        prefs.edit().putBoolean(KEY_DONE, true).apply() // idempotent guard set immediately

        scope.launch {
            try {
                val updated = withContext(ioDispatcher) { backfill() }
                Log.i(TAG, "Morning readiness v2 backfill complete: $updated reading(s) recomputed")
            } catch (e: Exception) {
                Log.e(TAG, "Morning readiness v2 backfill failed", e)
            }
        }
    }

    private suspend fun backfill(): Int {
        val legacy = morningReadinessDao.getLegacyMetricsV1()
        var updated = 0
        for (reading in legacy) {
            val telemetry = telemetryDao.getByReadingId(reading.id)
                .filter { it.phase == "SUPINE" && it.hrBpm in 30..220 }
            if (telemetry.size < MIN_SUPINE_BEATS) continue

            // Reconstruct RR intervals from per-beat HR (quantized, but
            // sufficient to catch dropped-beat merged intervals).
            val rr = telemetry.map { 60_000.0 / it.hrBpm }

            val corrected = artifactCorrection.execute(rr)
            if (corrected.validCount < MIN_SUPINE_BEATS) continue

            val hrv = timeDomainCalculator.calculate(corrected.correctedNn, corrected.artifactMask)
            if (hrv.sampleCount < MIN_SUPINE_BEATS || hrv.rmssdMs <= 0.0) continue

            val qualityFlag = hrv.rmssdMs > IMPLAUSIBLE_RMSSD_MS
            morningReadinessDao.updateSupineMetricsV2(
                id = reading.id,
                rmssdMs = hrv.rmssdMs,
                lnRmssd = if (hrv.rmssdMs > 0) ln(hrv.rmssdMs) else 0.0,
                sdnnMs = hrv.sdnnMs,
                qualityFlag = qualityFlag
            )
            updated++
        }
        return updated
    }
}
