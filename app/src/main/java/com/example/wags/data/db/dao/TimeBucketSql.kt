@file:Suppress("ktlint:filename")

package com.example.wags.data.db.dao

/**
 * SQL fragments for hour-bucket-aware `timeOfDay` matching.
 *
 * When the app's apnea time dimension is "By the Hour" (see
 * `com.example.wags.domain.model.TimeDimension`), the `:timeOfDay` query
 * parameter carries an hour bucket of the form "H00".."H23". Such values are
 * matched against the **local hour derived from the record's timestamp** —
 * which is what makes the feature fully retroactive: every historical record
 * already has a timestamp, so no migration or data rewrite is needed.
 *
 * Any other value (the legacy "MORNING"/"DAY"/"NIGHT" names, or the ""
 * / 'ALL' filter sentinels) keeps matching the stored `timeOfDay` column
 * exactly as before, so "Time of Day" behaviour is bit-for-bit unchanged.
 *
 * These are compile-time constants so they can be interpolated into
 * Room `@Query` annotation strings.
 */

/** Local hour (0–23) of the un-aliased `timestamp` column (epoch ms). */
internal const val SQL_HOUR_OF_TS =
    "CAST(strftime('%H', timestamp /1000, 'unixepoch', 'localtime') AS INTEGER)"

/** Local hour of `r.timestamp` (aliased outer table). */
internal const val SQL_HOUR_OF_TS_R =
    "CAST(strftime('%H', r.timestamp /1000, 'unixepoch', 'localtime') AS INTEGER)"

/** Local hour of `o.timestamp` (aliased inner/other table in is-best queries). */
internal const val SQL_HOUR_OF_TS_O =
    "CAST(strftime('%H', o.timestamp /1000, 'unixepoch', 'localtime') AS INTEGER)"

/** Local hour of `older.timestamp` (aliased in the paged-PB NOT EXISTS sub-query). */
internal const val SQL_HOUR_OF_TS_OLDER =
    "CAST(strftime('%H', older.timestamp /1000, 'unixepoch', 'localtime') AS INTEGER)"

/** Exact bucket match on the un-aliased apnea_records table. */
internal const val TOD_MATCH =
    "(CASE WHEN :timeOfDay LIKE 'H%' THEN 'H' || printf('%02d', $SQL_HOUR_OF_TS) ELSE timeOfDay END) = :timeOfDay"

/** Exact bucket match on a table aliased as `r`. */
internal const val TOD_MATCH_R =
    "(CASE WHEN :timeOfDay LIKE 'H%' THEN 'H' || printf('%02d', $SQL_HOUR_OF_TS_R) ELSE r.timeOfDay END) = :timeOfDay"

/** Exact bucket match on a table aliased as `older`. */
internal const val TOD_MATCH_OLDER =
    "(CASE WHEN :timeOfDay LIKE 'H%' THEN 'H' || printf('%02d', $SQL_HOUR_OF_TS_OLDER) ELSE older.timeOfDay END) = :timeOfDay"

/** Bucket match with the "empty string relaxes the filter" sentinel. */
internal const val TOD_MATCH_OR_EMPTY = "(:timeOfDay = '' OR $TOD_MATCH)"

/** Bucket match with the "'ALL' relaxes the filter" sentinel. */
internal const val TOD_MATCH_OR_ALL = "(:timeOfDay = 'ALL' OR $TOD_MATCH)"

/** `r`-aliased bucket match with the "empty string relaxes" sentinel. */
internal const val TOD_MATCH_R_OR_EMPTY = "(:timeOfDay = '' OR $TOD_MATCH_R)"

/** `r`-aliased bucket match with the "'ALL' relaxes" sentinel. */
internal const val TOD_MATCH_R_OR_ALL = "(:timeOfDay = 'ALL' OR $TOD_MATCH_R)"

/** `older`-aliased bucket match with the "empty string relaxes" sentinel. */
internal const val TOD_MATCH_OLDER_OR_EMPTY = "(:timeOfDay = '' OR $TOD_MATCH_OLDER)"
