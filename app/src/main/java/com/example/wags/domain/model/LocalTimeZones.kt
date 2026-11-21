package com.example.wags.domain.model

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for *local-time-where-it-happened* handling.
 *
 * Problem this solves (2026-11): the app derived each record's local hour/day
 * from its epoch timestamp using **the current device timezone at query time**
 * (Kotlin `Calendar`/`ZoneId.systemDefault()`) or SQLite `'localtime'` (which
 * on Android app processes resolves to **UTC**, since bionic app processes
 * have no `TZ` env var). After a timezone change, every historical record was
 * silently re-bucketed — e.g. a 07:00 Turkey hold (UTC+3) became "04:00" under
 * the SQL path and "12:00" under the Kotlin path once the phone moved to
 * Malaysia (UTC+8), so "PB at 07:00 with these settings" found nothing.
 *
 * Strategy (see ADR "Local time semantics"):
 *  1. **Freeze at save time.** Every session-ish row stores `zoneId` — the
 *     IANA zone the device was in when the row was created. `apnea_records`
 *     additionally stores `localHour` because hour-bucket SQL filters need a
 *     plain column to compare against.
 *  2. **Read frozen values.** Hour bucketing reads `localHour`; day grouping
 *     uses the row's `zoneId` (falling back to the current zone only for rows
 *     written before this fix that the one-time backfill could not repair).
 *  3. **"Now" still uses the current zone** — the current session bucket,
 *     "today" boundaries, and window edges describe the present moment.
 *  4. **Historic repair.** [seedZoneAt] resolves a past instant's zone from a
 *     compact interval seed (`assets/local_time_zones.txt`, generated from the
 *     companion Tail app's daily GPS history), defaulting to the home zone
 *     (Europe/Istanbul) for days the seed does not cover.
 */
object LocalTimeZones {

    /** Home zone: where the overwhelming majority of sessions happen. */
    val HOME: ZoneId = ZoneId.of("Europe/Istanbul")

    /** Device zone right now — used when stamping new rows. */
    fun currentZoneId(): String = ZoneId.systemDefault().id

    fun zoneOf(zoneId: String?): ZoneId =
        zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: HOME

    /**
     * Zone for a *historical* instant, from the Tail-derived seed. Intervals
     * are `[start, end]` inclusive; days outside every interval fall back to
     * [HOME]. Pure function over the parsed seed so callers can cache.
     */
    fun seedZoneAt(instantMs: Long, seed: List<ZoneInterval>): ZoneId {
        val date = Instant.ofEpochMilli(instantMs).atZone(HOME).toLocalDate()
        // Binary search on interval start dates.
        var lo = 0
        var hi = seed.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val iv = seed[mid]
            when {
                date < iv.startDate -> hi = mid - 1
                date > iv.endDate -> lo = mid + 1
                else -> return iv.zoneId
            }
        }
        return HOME
    }

    /** Local hour (0–23) of [timestampMs] in [zoneId]. */
    fun hourOf(timestampMs: Long, zoneId: String?): Int =
        Instant.ofEpochMilli(timestampMs).atZone(zoneOf(zoneId)).hour

    /**
     * Local calendar date of [timestampMs] in [zoneId] (as epoch-day so it can
     * round-trip through Room projections without java.time converters).
     */
    fun epochDayOf(timestampMs: Long, zoneId: String?): Long =
        Instant.ofEpochMilli(timestampMs).atZone(zoneOf(zoneId)).toLocalDate().toEpochDay()

    /** Effective zone for a stored row: its own zone, else [fallback]. */
    fun effectiveZone(storedZoneId: String?, fallback: ZoneId): ZoneId =
        storedZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    /** ZonedDateTime of a stored timestamp in its own (or fallback) zone. */
    fun zonedDateTimeOf(timestampMs: Long, storedZoneId: String?, fallback: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(timestampMs).atZone(effectiveZone(storedZoneId, fallback))
}

/** One contiguous same-zone date interval from the seed asset. */
data class ZoneInterval(val startDate: LocalDate, val endDate: LocalDate, val zoneId: ZoneId) {
    companion object {
        /** Parses one `startDate|endDate|zoneId` line, or null when malformed. */
        fun parse(line: String): ZoneInterval? {
            val parts = line.trim().split('|')
            if (parts.size != 3) return null
            val start = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            val end = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return null
            val zone = runCatching { ZoneId.of(parts[2]) }.getOrNull() ?: return null
            if (end < start) return null
            return ZoneInterval(start, end, zone)
        }
    }
}

/** Loads and caches the seed intervals from bundled assets. */
@Singleton
class ZoneSeedProvider @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    @Volatile private var cached: List<ZoneInterval>? = null

    fun get(): List<ZoneInterval> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): List<ZoneInterval> = runCatching {
        context.assets.open("local_time_zones.txt").bufferedReader().readLines()
            .mapNotNull(ZoneInterval.Companion::parse)
            .sortedBy { it.startDate }
    }.getOrElse { emptyList() }
}
