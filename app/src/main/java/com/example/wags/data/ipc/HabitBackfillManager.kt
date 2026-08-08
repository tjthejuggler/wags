package com.example.wags.data.ipc

import android.util.Log
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
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
 * resonance-frequency-assessment, and meditation sessions, then sends the
 * per-date totals to the Tail habit-tracking app via
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
    private val meditationRepo: MeditationRepository
) {

    data class BackfillResult(
        val resonanceDates: Int,
        val resonanceMinutes: Int,
        val meditationDates: Int,
        val meditationMinutes: Int,
        val resonanceSkipped: Boolean,
        val meditationSkipped: Boolean
    ) {
        val totalDates: Int get() = resonanceDates + meditationDates
        val totalMinutes: Int get() = resonanceMinutes + meditationMinutes
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
        val meditationMinutesByDate = mutableMapOf<String, Int>()

        val meditationSessions = meditationRepo.getAllSessions()
        for (session in meditationSessions) {
            val dateStr = epochMsToDateStr(session.timestamp, zone)
            val minutes = HabitIntegrationRepository.millisToMinutes(session.durationMs)
            meditationMinutesByDate[dateStr] = (meditationMinutesByDate[dateStr] ?: 0) + minutes
        }
        Log.i(TAG, "Meditation sessions: ${meditationSessions.size}, " +
                "${meditationMinutesByDate.size} unique dates")

        val meditationSkipped = habitRepo.getHabitId(Slot.MEDITATION).isBlank()
        if (!meditationSkipped && meditationMinutesByDate.isNotEmpty()) {
            habitRepo.sendHabitValuesForDates(Slot.MEDITATION, meditationMinutesByDate)
        }

        return BackfillResult(
            resonanceDates   = resonanceMinutesByDate.size,
            resonanceMinutes = resonanceMinutesByDate.values.sum(),
            meditationDates   = meditationMinutesByDate.size,
            meditationMinutes = meditationMinutesByDate.values.sum(),
            resonanceSkipped = resonanceSkipped,
            meditationSkipped = meditationSkipped
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
