@file:Suppress("ktlint:filename")

package com.example.wags.data.db.dao

/**
 * SQL fragments for hour-bucket-aware `timeOfDay` matching.
 *
 * When the app's apnea time dimension is "By the Hour" (see
 * `com.example.wags.domain.model.TimeDimension`), the `:timeOfDay` query
 * parameter carries an hour bucket of the form "H00".."H23". Such values are
 * matched against the record's `localHour` column — the **local hour frozen
 * at save time** in the device zone of that moment.
 *
 * History (2026-11 timezone bug): these fragments used to derive the hour
 * with `strftime('%H', timestamp/1000, 'unixepoch', 'localtime')`. Two
 * defects: (1) Android app processes have no `TZ` env var, so SQLite
 * 'localtime' resolved to **UTC** — a "constant global time" that never
 * matched the local hours the rest of the app used; (2) even where the
 * process zone was set, deriving a *stored* record's hour from the *current*
 * zone silently re-bucketed all history whenever the user travelled. Both are
 * fixed by the `localHour` column (frozen at save; repaired historically by
 * LocalTimeBackfillRunner from the Tail-derived zone seed). The fallback
 * expression below only covers un-repaired legacy rows (-1) and matches the
 * UTC-hour derivation they had before, so behaviour never regresses for
 * rows the seed could not place.
 *
 * Any other value (the legacy "MORNING"/"DAY"/"NIGHT" names, or the ""
 * / 'ALL' filter sentinels) keeps matching the stored `timeOfDay` column
 * exactly as before, so "Time of Day" behaviour is bit-for-bit unchanged.
 *
 * These are compile-time constants so they can be interpolated into
 * Room `@Query` annotation strings.
 */

/**
 * Local hour (0–23) of the un-aliased `apnea_records` row: the frozen
 * `localHour` column when present, else the legacy UTC-hour derivation for
 * un-repaired rows.
 */
internal const val SQL_HOUR_OF_TS =
    "COALESCE(NULLIF(localHour, -1), CAST(strftime('%H', timestamp /1000, 'unixepoch') AS INTEGER))"

/** Same, for the outer table aliased as `r`. */
internal const val SQL_HOUR_OF_TS_R =
    "COALESCE(NULLIF(r.localHour, -1), CAST(strftime('%H', r.timestamp /1000, 'unixepoch') AS INTEGER))"

/** Same, for the inner/other table aliased as `o` in is-best queries. */
internal const val SQL_HOUR_OF_TS_O =
    "COALESCE(NULLIF(o.localHour, -1), CAST(strftime('%H', o.timestamp /1000, 'unixepoch') AS INTEGER))"

/** Exact bucket match on the un-aliased apnea_records table. */
internal const val TOD_MATCH =
    "(CASE WHEN :timeOfDay LIKE 'H%' THEN 'H' || printf('%02d', $SQL_HOUR_OF_TS) ELSE timeOfDay END) = :timeOfDay"

/** Exact bucket match on a table aliased as `r`. */
internal const val TOD_MATCH_R =
    "(CASE WHEN :timeOfDay LIKE 'H%' THEN 'H' || printf('%02d', $SQL_HOUR_OF_TS_R) ELSE r.timeOfDay END) = :timeOfDay"

/** Bucket match with the "empty string relaxes the filter" sentinel. */
internal const val TOD_MATCH_OR_EMPTY = "(:timeOfDay = '' OR $TOD_MATCH)"

/** Bucket match with the "'ALL' relaxes the filter" sentinel. */
internal const val TOD_MATCH_OR_ALL = "(:timeOfDay = 'ALL' OR $TOD_MATCH)"

/** `r`-aliased bucket match with the "'ALL' relaxes" sentinel. */
internal const val TOD_MATCH_R_OR_ALL = "(:timeOfDay = 'ALL' OR $TOD_MATCH_R)"
