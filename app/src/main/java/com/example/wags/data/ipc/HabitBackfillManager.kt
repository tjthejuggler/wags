package com.example.wags.data.ipc

import android.util.Log
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.MeditationRepository
import com.example.wags.data.repository.ResonanceSessionRepository
import com.example.wags.data.repository.RfAssessmentRepository
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retroactively aggregates minutes from all past resonance-breathing,
 * resonance-frequency-assessment, meditation, and apnea sessions, then sends
 * the per-date totals to the Tail habit-tracking app via
 * [HabitIntegrationRepository.sendHabitValuesForDates].
 *
 * Two entry points:
 *  • [backfillSlot] – sends the full history backlog for ONE slot. Used by the
 *    automatic backfill that fires when a new habit connection is made in
 *    Settings, so a freshly connected slot immediately receives its entire
 *    history.
 *  • [backfill] – sends the backlog for every slot that has history (the
 *    manual "Backfill Past Sessions" action in Settings).
 *
 * It is idempotent: Tail SETS (replaces) the value for each date, so running
 * it multiple times produces the same result.
 *
 * **Slot → history mapping:**
 *  • [Slot.RESONANCE_BREATHING] – resonance sessions **and** RF assessments
 *    (both contribute minutes to the same habit slot).
 *  • [Slot.MEDITATION] – meditation / NSDR sessions.
 *  • [Slot.FREE_HOLD] – apnea free-hold records (`tableType == null`).
 *  • [Slot.O2_TABLE] – O₂ table sessions (`tableType == "O2"`).
 *  • [Slot.CO2_TABLE] – CO₂ table sessions (`tableType == "CO2"`).
 *  • [Slot.PROGRESSIVE_O2] – Progressive O₂ drill sessions (`tableType == "PROGRESSIVE_O2"`).
 *  • [Slot.MIN_BREATH] – Min Breath drill sessions (`tableType == "MIN_BREATH"`).
 *  • [Slot.TILL_CONTRACTION] – Till Contraction drill sessions (`tableType == "WONKA_FIRST_CONTRACTION"`).
 *  • [Slot.CONTRACTION_COUNT] – Contraction Count drill sessions (`tableType == "WONKA_ENDURANCE"`).
 *
 * Slots without minute-based history (new records, readiness scores, music)
 * have nothing to backfill and return an empty result.
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

    /** Result of backfilling a single slot's history. */
    data class SlotBackfillResult(
        val slot: Slot,
        val dates: Int = 0,
        val minutes: Int = 0,
        val sessions: Int = 0,
        val skipped: Boolean = false
    )

    /** Result of the full manual backfill — one entry per slot with history. */
    data class BackfillResult(
        val slotResults: Map<Slot, SlotBackfillResult>
    ) {
        val totalDates: Int get() = slotResults.values.sumOf { it.dates }
        val totalMinutes: Int get() = slotResults.values.sumOf { it.minutes }
        val totalSessions: Int get() = slotResults.values.sumOf { it.sessions }
        val skippedSlots: List<Slot> get() = slotResults.values.filter { it.skipped }.map { it.slot }
    }

    /** Slots that have minute-based history to backfill, in display order. */
    private val backfillableSlots = listOf(
        Slot.RESONANCE_BREATHING,
        Slot.MEDITATION,
        Slot.FREE_HOLD,
        Slot.O2_TABLE,
        Slot.CO2_TABLE,
        Slot.PROGRESSIVE_O2,
        Slot.MIN_BREATH,
        Slot.TILL_CONTRACTION,
        Slot.CONTRACTION_COUNT
    )

    /**
     * Sends the full per-date history backlog for [slot] to its connected Tail
     * habit. No-op if the slot has no history; reported as skipped if no habit
     * is selected for the slot.
     */
    suspend fun backfillSlot(slot: Slot): SlotBackfillResult {
        val zone = ZoneId.systemDefault()
        val (minutesByDate, sessionsByDate) = aggregateForSlot(slot, zone)

        val skipped = habitRepo.getHabitId(slot).isBlank()
        if (!skipped && minutesByDate.isNotEmpty()) {
            Log.i(TAG, "Backfill ${slot.name}: ${minutesByDate.size} dates, " +
                "${minutesByDate.values.sum()} min, ${sessionsByDate.values.sum()} sessions")
            if (slot in SESSIONS_PRIMARY_SLOTS) {
                // Sessions-primary habits (Aug-21-2026 migration): the session
                // count is the PRIMARY value and the minutes live in Tail's
                // first-class minutes:<habit> slot.
                habitRepo.sendHabitValuesForDates(slot, sessionsByDate)
                // Small delay to let Tail's mutex-serialised receiver finish
                // the primary write before we send the minutes-slot broadcast.
                delay(500)
                habitRepo.sendMinutesSlotValuesForDates(slot, minutesByDate)
            } else {
                habitRepo.sendHabitValuesForDates(slot, minutesByDate)
                // Small delay to let Tail's mutex-serialised receiver finish the primary write
                // before we send the secondary broadcast.
                delay(500)
                habitRepo.sendSecondaryValuesForDates(slot, sessionsByDate)
            }
        }
        return SlotBackfillResult(
            slot = slot,
            dates = minutesByDate.size,
            minutes = minutesByDate.values.sum(),
            sessions = sessionsByDate.values.sum(),
            skipped = skipped
        )
    }

    /**
     * Runs the full retroactive backfill across every slot with history.
     * Slots with no habit selected are silently skipped (reported via
     * [BackfillResult.skippedSlots]).
     */
    suspend fun backfill(): BackfillResult {
        val results = linkedMapOf<Slot, SlotBackfillResult>()
        for (slot in backfillableSlots) {
            results[slot] = backfillSlot(slot)
        }
        return BackfillResult(results)
    }

    // ── Per-slot history aggregation ──────────────────────────────────────────

    /**
     * Aggregates (minutesByDate, sessionsByDate) for [slot].
     * Returns empty maps for slots without minute-based history.
     */
    private suspend fun aggregateForSlot(
        slot: Slot,
        zone: ZoneId
    ): Pair<Map<String, Int>, Map<String, Int>> = when (slot) {
        Slot.RESONANCE_BREATHING -> {
            // Resonance sessions + RF assessments share one habit slot
            val minutes = mutableMapOf<String, Int>()
            val sessions = mutableMapOf<String, Int>()
            for (session in resonanceRepo.getAll()) {
                addSession(
                    minutes, sessions,
                    dateStr = epochMsToDateStr(session.timestamp, zone),
                    minutes = HabitIntegrationRepository.secondsToMinutes(session.durationSeconds)
                )
            }
            for (assessment in rfAssessmentRepo.getAll()) {
                addSession(
                    minutes, sessions,
                    dateStr = epochMsToDateStr(assessment.timestamp, zone),
                    minutes = HabitIntegrationRepository.secondsToMinutes(assessment.durationSeconds)
                )
            }
            minutes to sessions
        }
        Slot.MEDITATION -> {
            val minutes = mutableMapOf<String, Int>()
            val sessions = mutableMapOf<String, Int>()
            for (session in meditationRepo.getAllSessions()) {
                addSession(
                    minutes, sessions,
                    dateStr = epochMsToDateStr(session.timestamp, zone),
                    minutes = HabitIntegrationRepository.millisToMinutes(session.durationMs)
                )
            }
            minutes to sessions
        }
        // Apnea-backed slots — dispatched on ApneaRecordEntity.tableType
        Slot.FREE_HOLD         -> apneaMaps(setOf(null), zone)
        Slot.O2_TABLE          -> apneaMaps(setOf("O2"), zone)
        Slot.CO2_TABLE         -> apneaMaps(setOf("CO2"), zone)
        Slot.PROGRESSIVE_O2    -> apneaMaps(setOf("PROGRESSIVE_O2"), zone)
        Slot.MIN_BREATH        -> apneaMaps(setOf("MIN_BREATH"), zone)
        // Legacy tableType strings kept from the original Wonka prototypes
        Slot.TILL_CONTRACTION  -> apneaMaps(setOf("WONKA_FIRST_CONTRACTION"), zone)
        Slot.CONTRACTION_COUNT -> apneaMaps(setOf("WONKA_ENDURANCE"), zone)
        // No minute-based history to backfill (records, readiness, music)
        else -> emptyMap<String, Int>() to emptyMap()
    }

    /**
     * Aggregates apnea records whose tableType is in [types] into per-date
     * minute and session-count maps.
     */
    private suspend fun apneaMaps(
        types: Set<String?>,
        zone: ZoneId
    ): Pair<Map<String, Int>, Map<String, Int>> {
        val minutes = mutableMapOf<String, Int>()
        val sessions = mutableMapOf<String, Int>()
        for (record in apneaRepo.getAllRecordsOnce()) {
            if (record.tableType !in types) continue
            addSession(
                minutes, sessions,
                dateStr = epochMsToDateStr(record.timestamp, zone),
                minutes = HabitIntegrationRepository.millisToMinutes(record.durationMs)
            )
        }
        return minutes to sessions
    }

    private fun addSession(
        minutesByDate: MutableMap<String, Int>,
        sessionsByDate: MutableMap<String, Int>,
        dateStr: String,
        minutes: Int
    ) {
        minutesByDate[dateStr] = (minutesByDate[dateStr] ?: 0) + minutes
        sessionsByDate[dateStr] = (sessionsByDate[dateStr] ?: 0) + 1
    }

    /** Converts an epoch-ms timestamp to a `yyyy-MM-dd` string in [zone]. */
    private fun epochMsToDateStr(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .toLocalDate()
            .toString() // ISO-8601 format: yyyy-MM-dd

    companion object {
        private const val TAG = "HabitBackfillManager"

        /**
         * Slots whose Tail habits are SESSIONS-PRIMARY (Aug-21-2026 apnea
         * migration; Aug-22-2026 breathing migration): sessions are the
         * primary value and points source; minutes live in the first-class
         * `minutes:<habit>` slot. All other slots keep the legacy layout
         * (minutes = primary, sessions = secondary value).
         */
        val SESSIONS_PRIMARY_SLOTS = setOf(
            Slot.FREE_HOLD,
            Slot.O2_TABLE,
            Slot.CO2_TABLE,
            Slot.PROGRESSIVE_O2,
            Slot.MIN_BREATH,
            Slot.MEDITATION,
            Slot.RESONANCE_BREATHING,
            Slot.TILL_CONTRACTION
        )
    }
}
