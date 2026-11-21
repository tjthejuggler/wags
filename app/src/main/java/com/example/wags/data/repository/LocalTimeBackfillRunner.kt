package com.example.wags.data.repository

import android.content.Context
import android.util.Log
import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.ApneaSessionDao
import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.dao.MeditationSessionDao
import com.example.wags.data.db.dao.MorningReadinessDao
import com.example.wags.data.db.dao.RapidHrSessionDao
import com.example.wags.data.db.dao.ResonanceSessionDao
import com.example.wags.data.db.dao.RfAssessmentDao
import com.example.wags.domain.model.LocalTimeZones
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.model.ZoneInterval
import com.example.wags.domain.model.ZoneSeedProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time historic repair for the 2026-11 local-time bug (see ADR).
 *
 * Migration 46→47 added `zoneId` (+ `localHour` on apnea_records) with
 * NULL/-1 defaults. This runner fills the true values for every pre-fix row:
 * for each row it resolves the device's zone ON THE DAY OF THE ROW from the
 * bundled Tail-derived zone seed (`assets/local_time_zones.txt`) and stamps:
 *  - all 8 session tables → `zoneId`
 *  - apnea_records → `zoneId` + `localHour` = hour(timestamp, zone)
 *
 * Validation: for apnea records the seed-derived hour is checked against the
 * row's stored Morning/Day/Night bucket (which has always been frozen
 * correctly at save time from the true local hour). Mismatches are counted
 * and logged — a large count would indicate a bad seed, not silent corruption
 * (the stamp is still written; the frozen bucket column is never modified).
 *
 * Runs once, guarded by a SharedPreferences flag; idempotent (re-running
 * only re-stamps NULL/-1 rows, which no longer exist).
 */
@Singleton
class LocalTimeBackfillRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apneaRecordDao: ApneaRecordDao,
    private val apneaSessionDao: ApneaSessionDao,
    private val meditationSessionDao: MeditationSessionDao,
    private val resonanceSessionDao: ResonanceSessionDao,
    private val morningReadinessDao: MorningReadinessDao,
    private val dailyReadingDao: DailyReadingDao,
    private val rfAssessmentDao: RfAssessmentDao,
    private val rapidHrSessionDao: RapidHrSessionDao,
    private val zoneSeedProvider: ZoneSeedProvider,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LocalTimeBackfill"
        private const val PREFS = "local_time_backfill"
        private const val KEY_DONE = "zone_stamp_done"
    }

    fun runIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        scope.launch(Dispatchers.IO) {
            try {
                val repaired = backfill(zoneSeedProvider.get())
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                Log.i(TAG, "Zone stamp backfill complete: $repaired rows stamped")
            } catch (t: Throwable) {
                // Retry on next startup; never block the app on this.
                Log.e(TAG, "Zone stamp backfill failed; will retry", t)
            }
        }
    }

    private suspend fun backfill(seed: List<ZoneInterval>): Int {
        var total = 0

        // ── apnea_records: zoneId + localHour, validated against timeOfDay ──
        var todChecked = 0
        var todMismatch = 0
        for (r in apneaRecordDao.getAllOnce()) {
            if (r.zoneId != null && r.localHour in 0..23) continue
            val zone = LocalTimeZones.seedZoneAt(r.timestamp, seed).id
            val hour = LocalTimeZones.hourOf(r.timestamp, zone)
            apneaRecordDao.stampLocalTime(r.recordId, zone, hour)
            if (TimeOfDay.fromHour(hour).name != r.timeOfDay) todMismatch++
            todChecked++
            total++
        }
        if (todChecked > 0) {
            Log.i(
                TAG, "apnea_records: stamped $todChecked, tod-mismatches $todMismatch " +
                    "(mismatches expected only for boundary-hour records or seed-gap days)"
            )
        }

        // ── the other 7 tables: zoneId only ─────────────────────────────────
        for (s in apneaSessionDao.getAllOnce()) {
            if (s.zoneId == null) {
                apneaSessionDao.stampZone(s.sessionId, LocalTimeZones.seedZoneAt(s.timestamp, seed).id); total++
            }
        }
        for (s in meditationSessionDao.getAll()) {
            if (s.zoneId == null) {
                meditationSessionDao.stampZone(s.sessionId, LocalTimeZones.seedZoneAt(s.timestamp, seed).id); total++
            }
        }
        for (s in resonanceSessionDao.getAll()) {
            if (s.zoneId == null) {
                resonanceSessionDao.stampZone(s.sessionId, LocalTimeZones.seedZoneAt(s.timestamp, seed).id); total++
            }
        }
        for (r in morningReadinessDao.getAll()) {
            if (r.zoneId == null) {
                morningReadinessDao.stampZone(r.id, LocalTimeZones.seedZoneAt(r.timestamp, seed).id); total++
            }
        }
        for (r in dailyReadingDao.getAll()) {
            if (r.zoneId == null) {
                dailyReadingDao.stampZone(r.readingId, LocalTimeZones.seedZoneAt(r.timestamp, seed).id); total++
            }
        }
        for (a in rfAssessmentDao.getAll()) {
            if (a.zoneId == null) {
                rfAssessmentDao.stampZone(a.assessmentId, LocalTimeZones.seedZoneAt(a.timestamp, seed).id); total++
            }
        }
        for (s in rapidHrSessionDao.getAll()) {
            if (s.zoneId == null) {
                rapidHrSessionDao.stampZone(s.id, LocalTimeZones.seedZoneAt(s.timestamp, seed).id); total++
            }
        }
        return total
    }
}
