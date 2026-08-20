package com.example.wags.domain.model

import java.util.Calendar

/**
 * User-selectable dimension used to bucket apnea records by *when* they happened.
 *
 *  * [TIME_OF_DAY] — the classic 3 buckets (Morning / Day / Night), stored on
 *    each record's `timeOfDay` column. Everything behaves exactly as it always
 *    has.
 *  * [BY_HOUR] — 24 buckets, one per hour of the day ("H00" … "H23"). The
 *    bucket is **always derived from the record's start timestamp** — the user
 *    cannot pick it manually. All records, personal bests, trophies, stats,
 *    recommended settings and record forecasts are calculated against the
 *    hour bucket instead of the Morning/Day/Night column. Because the hour is
 *    derived at query time from data that already exists in the DB, the
 *    recalculation is fully retroactive and switching between the two modes
 *    never rewrites any data.
 */
enum class TimeDimension {
    TIME_OF_DAY,
    BY_HOUR;

    fun displayName(): String = when (this) {
        TIME_OF_DAY -> "Time of Day"
        BY_HOUR    -> "By the Hour"
    }

    companion object {
        fun fromName(name: String?): TimeDimension =
            entries.firstOrNull { it.name == name } ?: TIME_OF_DAY
    }
}

/**
 * Helpers for the "time bucket" strings that flow through every `timeOfDay`
 * parameter in the apnea stack.
 *
 * A bucket is either:
 *  * a legacy [TimeOfDay] enum name ("MORNING", "DAY", "NIGHT") — matched
 *    against the record's stored `timeOfDay` column, or
 *  * an hour bucket "H00" … "H23" — matched against the local hour derived
 *    from the record's timestamp (see the SQL in the apnea DAOs).
 */
object TimeBuckets {

    const val HOUR_PREFIX = "H"

    /** All 24 hour-bucket names in ascending order. */
    val HOUR_BUCKETS: List<String> = (0..23).map { fromHour(it) }

    /** True when [bucket] is an hour bucket ("H00" … "H23"). */
    fun isHourBucket(bucket: String): Boolean =
        bucket.length == 3 &&
            bucket[0] == HOUR_PREFIX[0] &&
            bucket[1].isDigit() && bucket[2].isDigit() &&
            hourOf(bucket) != null

    /** Hour bucket string ("H08") for the given hour (0–23). */
    fun fromHour(hour: Int): String = "%s%02d".format(HOUR_PREFIX, hour)

    /** Hour (0–23) encoded in an hour bucket, or null when [bucket] is not one. */
    fun hourOf(bucket: String): Int? =
        if (bucket.length == 3 && bucket[0] == HOUR_PREFIX[0])
            bucket.substring(1).toIntOrNull()?.takeIf { it in 0..23 }
        else null

    /** Local hour (0–23) of the given epoch-ms timestamp. */
    fun hourOfTimestamp(timestampMs: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /** Hour bucket for the given epoch-ms timestamp (e.g. a record's timestamp). */
    fun fromTimestamp(timestampMs: Long): String = fromHour(hourOfTimestamp(timestampMs))

    /** Hour bucket for the current moment. */
    fun current(): String = fromTimestamp(System.currentTimeMillis())

    /**
     * Human-readable label for any bucket:
     *  * "MORNING"/"DAY"/"NIGHT" → "Morning"/"Day"/"Night"
     *  * "H08" → "08"
     */
    fun display(bucket: String): String {
        hourOf(bucket)?.let { return "%02d".format(it) }
        return runCatching { TimeOfDay.valueOf(bucket).displayName() }.getOrDefault(bucket)
    }

    /**
     * The Morning/Day/Night name for any bucket. Hour buckets are mapped back
     * through [TimeOfDay.fromHour] so summary/detail screens can keep showing
     * the classic "Time of Day" line regardless of the selected dimension.
     */
    fun timeOfDayNameOf(bucket: String): String {
        hourOf(bucket)?.let { return TimeOfDay.fromHour(it).name }
        return bucket
    }

    /**
     * Resolves the effective bucket for a *session-scoped* query in the given
     * dimension: legacy names pass through in TIME_OF_DAY mode, while in
     * BY_HOUR mode any legacy name is replaced with the current hour bucket
     * (the bucket is automatic and cannot be user-selected). Values that are
     * already hour buckets (or filter sentinels like "" / "ALL") pass through
     * untouched.
     */
    fun normalizeSessionBucket(bucket: String, dimension: TimeDimension): String =
        if (dimension == TimeDimension.BY_HOUR && !isHourBucket(bucket) &&
            TimeOfDay.entries.any { it.name == bucket }
        ) current() else bucket
}
