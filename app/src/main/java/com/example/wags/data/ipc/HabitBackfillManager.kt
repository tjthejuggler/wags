package com.example.wags.data.ipc

import android.util.Log
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.MeditationRepository
import com.example.wags.data.repository.ResonanceSessionRepository
import com.example.wags.data.repository.RfAssessmentRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retroactively aggregates minutes from all past resonance-breathing,
 * resonance-frequency-assessment, meditation, and apnea sessions, then sends
 * the per-date totals to the Tail habit-tracking app via
 * [HabitIntegrationRepository.sendHabitValuesForDates].
 *
 * This is a one-time "backfill" action triggered manually by the user from
 * the Settings → Tail App Integration screen. It is idempotent: Tail SETS
 * (replaces) the value for each date, so running it multiple times produces
 * the same result.
 *
 * **Slot mapping:**
 *  • [Slot.RESONANCE_BREATHING] – resonance sessions **and** RF assessments
 *    (both contribute minutes to the same habit slot).
 *  • [Slot.MEDITATION] – meditation / NSDR sessions.
 *  • [Slot.FREE_HOLD] – apnea free-hold records (`tableType == null`).
 *  • [Slot.TABLE_TRAINING] – O₂ / CO₂ table sessions (`tableType` is `"O2"` or `"CO2"`).
 *  • [Slot.PROGRESSIVE_O2] – Progressive O₂ drill sessions (`tableType == "PROGRESSIVE_O2"`).
 *  • [Slot.MIN_BREATH] – Min Breath drill sessions (`tableType == "MIN_BREATH"`).
 *
 * **Date handling:** Session timestamps (epoch-ms, UTC) are converted to
 * `yyyy-MM-dd` strings using the device's default timezone, matching how
 * Tail's own receiver uses `LocalDate.now()`.
 */
@Singleton
class HabitBackfillManager @Inject constructor(
    private val habitRepo: HabitIntegrationRepository,
    private val resonanceRepo: ResonanceSessionRepository,
    private val rfAssessmentRepo: RfAssessmentRepository,
    private val meditationRepo: MeditationRepository,
    private val apneaRepo: ApneaRepository
) {

    data class BackfillResult(
        val resonanceDates: Int,
        val resonanceMinutes: Int,
        val meditationDates: Int,
        val meditationMinutes: Int,
        val meditationSessions: Int,
        val freeHoldDates: Int,
        val freeHoldMinutes: Int,
        val tableTrainingDates: Int,
        val tableTrainingMinutes: Int,
        val progressiveO2Dates: Int,
        val progressiveO2Minutes: Int,
        val minBreathDates: Int,
        val minBreathMinutes: Int,
        val resonanceSkipped: Boolean,
        val meditationSkipped: Boolean,
        val freeHoldSkipped: Boolean,
        val tableTrainingSkipped: Boolean,
        val progressiveO2Skipped: Boolean,
        val minBreathSkipped: Boolean
    ) {
        val totalDates: Int get() = resonanceDates + meditationDates +
            freeHoldDates + tableTrainingDates + progressiveO2Dates + minBreathDates
        val totalMinutes: Int get() = resonanceMinutes + meditationMinutes +
            freeHoldMinutes + tableTrainingMinutes + progressiveO2Minutes + minBreathMinutes
    }

    /**
     * Runs the full retroactive backfill.
     *
     * Returns a [BackfillResult] summarising what was sent. Slots with no
     * habit selected are silently skipped (reported via the `*Skipped` flags).
     */
    suspend fun backfill(): BackfillResult {
        val zone = ZoneId.systemDefault()

        // ── Resonance Breathing + RF Assessments ──────────────────────────────
        val resonanceMinutesByDate = mutableMapOf<String, Int>()

        // Normal resonance sessions
        val resonanceSessions = resonanceRepo.getAll()
        for (session in resonanceSessions) {
            val dateStr = epochMsToDateStr(session.timestamp, zone)
            val minutes = HabitIntegrationRepository.secondsToMinutes(session.durationSeconds)
            resonanceMinutesByDate[dateStr] = (resonanceMinutesByDate[dateStr] ?: 0) + minutes
        }
        Log.i(TAG, "Resonance sessions: ${resonanceSessions.size}, " +
                "${resonanceMinutesByDate.size} unique dates")

        // RF assessments (same habit slot)
        val assessments = rfAssessmentRepo.getAll()
        for (assessment in assessments) {
            val dateStr = epochMsToDateStr(assessment.timestamp, zone)
            val minutes = HabitIntegrationRepository.secondsToMinutes(assessment.durationSeconds)
            resonanceMinutesByDate[dateStr] = (resonanceMinutesByDate[dateStr] ?: 0) + minutes
        }
        Log.i(TAG, "RF assessments: ${assessments.size} (merged into resonance dates)")

        val resonanceSkipped = habitRepo.getHabitId(Slot.RESONANCE_BREATHING).isBlank()
        if (!resonanceSkipped && resonanceMinutesByDate.isNotEmpty()) {
            habitRepo.sendHabitValuesForDates(Slot.RESONANCE_BREATHING, resonanceMinutesByDate)
        }

        // ── Meditation ────────────────────────────────────────────────────────
        // Minutes → primary slot (Value 1), session count → secondary_value slot (Value 2)
        val meditationMinutesByDate = mutableMapOf<String, Int>()
        val meditationSessionsByDate = mutableMapOf<String, Int>()

        val meditationSessions = meditationRepo.getAllSessions()
        for (session in meditationSessions) {
            val dateStr = epochMsToDateStr(session.timestamp, zone)
            val minutes = HabitIntegrationRepository.millisToMinutes(session.durationMs)
            meditationMinutesByDate[dateStr] = (meditationMinutesByDate[dateStr] ?: 0) + minutes
            meditationSessionsByDate[dateStr] = (meditationSessionsByDate[dateStr] ?: 0) + 1
        }
        Log.i(TAG, "Meditation sessions: ${meditationSessions.size}, " +
                "${meditationMinutesByDate.size} unique dates, " +
                "${meditationSessionsByDate.values.sum()} total sessions")

        val meditationSkipped = habitRepo.getHabitId(Slot.MEDITATION).isBlank()
        if (!meditationSkipped && meditationMinutesByDate.isNotEmpty()) {
            Log.i(TAG, "Sending meditation minutes (primary): ${meditationMinutesByDate.size} dates, " +
                    "${meditationMinutesByDate.values.sum()} total minutes")
            habitRepo.sendHabitValuesForDates(Slot.MEDITATION, meditationMinutesByDate)
            // Small delay to let Tail's mutex-serialised receiver finish the primary write
            // before we send the secondary broadcast.
            kotlinx.coroutines.delay(500)
            Log.i(TAG, "Sending meditation sessions (secondary): ${meditationSessionsByDate.size} dates, " +
                    "${meditationSessionsByDate.values.sum()} total sessions")
            habitRepo.sendSecondaryValuesForDates(Slot.MEDITATION, meditationSessionsByDate)
        }

        // ── Apnea (free holds, tables, progressive O₂, min breath) ────────────
        //
        // Every apnea activity saves a single ApneaRecordEntity whose durationMs
        // is the TOTAL hold time for that session. The tableType field
        // distinguishes the activity:
        //   null             → free hold
        //   "O2" / "CO2"     → O₂/CO₂ table training
        //   "PROGRESSIVE_O2" → Progressive O₂ drill
        //   "MIN_BREATH"     → Min Breath drill
        val freeHoldMinutesByDate = mutableMapOf<String, Int>()
        val tableTrainingMinutesByDate = mutableMapOf<String, Int>()
        val progressiveO2MinutesByDate = mutableMapOf<String, Int>()
        val minBreathMinutesByDate = mutableMapOf<String, Int>()

        val apneaRecords = apneaRepo.getAllRecordsOnce()
        for (record in apneaRecords) {
            val dateStr = epochMsToDateStr(record.timestamp, zone)
            val minutes = HabitIntegrationRepository.millisToMinutes(record.durationMs)
            val targetMap = when (record.tableType) {
                null                    -> freeHoldMinutesByDate
                "O2", "CO2"             -> tableTrainingMinutesByDate
                "PROGRESSIVE_O2"        -> progressiveO2MinutesByDate
                "MIN_BREATH"            -> minBreathMinutesByDate
                else                    -> null // unknown type — skip
            }
            if (targetMap != null) {
                targetMap[dateStr] = (targetMap[dateStr] ?: 0) + minutes
            }
        }
        Log.i(TAG, "Apnea records: ${apneaRecords.size} | " +
                "freeHold=${freeHoldMinutesByDate.size} dates, " +
                "table=${tableTrainingMinutesByDate.size} dates, " +
                "progO2=${progressiveO2MinutesByDate.size} dates, " +
                "minBreath=${minBreathMinutesByDate.size} dates")

        val freeHoldSkipped = habitRepo.getHabitId(Slot.FREE_HOLD).isBlank()
        if (!freeHoldSkipped && freeHoldMinutesByDate.isNotEmpty()) {
            habitRepo.sendHabitValuesForDates(Slot.FREE_HOLD, freeHoldMinutesByDate)
        }

        val tableTrainingSkipped = habitRepo.getHabitId(Slot.TABLE_TRAINING).isBlank()
        if (!tableTrainingSkipped && tableTrainingMinutesByDate.isNotEmpty()) {
            habitRepo.sendHabitValuesForDates(Slot.TABLE_TRAINING, tableTrainingMinutesByDate)
        }

        val progressiveO2Skipped = habitRepo.getHabitId(Slot.PROGRESSIVE_O2).isBlank()
        if (!progressiveO2Skipped && progressiveO2MinutesByDate.isNotEmpty()) {
            habitRepo.sendHabitValuesForDates(Slot.PROGRESSIVE_O2, progressiveO2MinutesByDate)
        }

        val minBreathSkipped = habitRepo.getHabitId(Slot.MIN_BREATH).isBlank()
        if (!minBreathSkipped && minBreathMinutesByDate.isNotEmpty()) {
            habitRepo.sendHabitValuesForDates(Slot.MIN_BREATH, minBreathMinutesByDate)
        }

        return BackfillResult(
            resonanceDates       = resonanceMinutesByDate.size,
            resonanceMinutes     = resonanceMinutesByDate.values.sum(),
            meditationDates      = meditationMinutesByDate.size,
            meditationMinutes    = meditationMinutesByDate.values.sum(),
            meditationSessions   = meditationSessionsByDate.values.sum(),
            freeHoldDates        = freeHoldMinutesByDate.size,
            freeHoldMinutes      = freeHoldMinutesByDate.values.sum(),
            tableTrainingDates   = tableTrainingMinutesByDate.size,
            tableTrainingMinutes = tableTrainingMinutesByDate.values.sum(),
            progressiveO2Dates   = progressiveO2MinutesByDate.size,
            progressiveO2Minutes = progressiveO2MinutesByDate.values.sum(),
            minBreathDates       = minBreathMinutesByDate.size,
            minBreathMinutes     = minBreathMinutesByDate.values.sum(),
            resonanceSkipped     = resonanceSkipped,
            meditationSkipped    = meditationSkipped,
            freeHoldSkipped      = freeHoldSkipped,
            tableTrainingSkipped = tableTrainingSkipped,
            progressiveO2Skipped = progressiveO2Skipped,
            minBreathSkipped     = minBreathSkipped
        )
    }

    /** Converts an epoch-ms timestamp to a `yyyy-MM-dd` string in [zone]. */
    private fun epochMsToDateStr(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .toLocalDate()
            .toString() // ISO-8601 format: yyyy-MM-dd

    companion object {
        private const val TAG = "HabitBackfillManager"
    }
}
