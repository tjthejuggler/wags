package com.example.wags.data.ipc

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Handles all IPC communication with the companion Habit-tracking app.
 *
 * Each WAGS activity that can trigger a habit increment has its own independent
 * habit slot so the user can map different habits to different activities:
 *
 *  • [Slot.FREE_HOLD]           – Apnea free breath hold (personal best)
 *  • [Slot.TABLE_TRAINING]      – Apnea O2 / CO2 table session completion
 *  • [Slot.MORNING_READINESS]   – Morning Readiness assessment completion
 *  • [Slot.HRV_READINESS]       – HRV Readiness session completion
 *  • [Slot.RESONANCE_BREATHING] – Resonance Breathing session stop
 *  • [Slot.MEDITATION]          – Meditation / NSDR session completion
 *  • [Slot.MUSIC]               – Apnea session with music audio (once per TimeOfDay per day)
 *
 * The broadcast is:
 *  - **Explicit** (package + action set) — required for reliable delivery on API 26+
 *  - **Permission-guarded** via [PERMISSION_TAIL] — only the Habit app can receive it
 */
@Singleton
class HabitIntegrationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("habit_prefs") private val prefs: SharedPreferences
) {

    // ── Activity slots ────────────────────────────────────────────────────────

    enum class Slot(
        val idKey: String,
        val nameKey: String,
        val label: String
    ) {
        FREE_HOLD(
            idKey   = "habit_id_free_hold",
            nameKey = "habit_name_free_hold",
            label   = "Apnea Free Hold"
        ),
        APNEA_NEW_RECORD(
            idKey   = "habit_id_apnea_new_record",
            nameKey = "habit_name_apnea_new_record",
            label   = "Apnea New Record"
        ),
        TABLE_TRAINING(
            idKey   = "habit_id_table_training",
            nameKey = "habit_name_table_training",
            label   = "Apnea Table Training"
        ),
        MORNING_READINESS(
            idKey   = "habit_id_morning_readiness",
            nameKey = "habit_name_morning_readiness",
            label   = "Morning Readiness"
        ),
        HRV_READINESS(
            idKey   = "habit_id_hrv_readiness",
            nameKey = "habit_name_hrv_readiness",
            label   = "HRV Readiness"
        ),
        RESONANCE_BREATHING(
            idKey   = "habit_id_resonance_breathing",
            nameKey = "habit_name_resonance_breathing",
            label   = "Resonance Breathing"
        ),
        MEDITATION(
            idKey   = "habit_id_meditation",
            nameKey = "habit_name_meditation",
            label   = "Meditation / NSDR"
        ),
        RAPID_HR_CHANGE(
            idKey   = "habit_id_rapid_hr_change",
            nameKey = "habit_name_rapid_hr_change",
            label   = "Rapid HR Change"
        ),
        PROGRESSIVE_O2(
            idKey   = "habit_id_progressive_o2",
            nameKey = "habit_name_progressive_o2",
            label   = "Progressive O₂"
        ),
        MIN_BREATH(
            idKey   = "habit_id_min_breath",
            nameKey = "habit_name_min_breath",
            label   = "Min Breath"
        ),
        MUSIC(
            idKey   = "habit_id_music",
            nameKey = "habit_name_music",
            label   = "Music Session"
        )
    }

    // ── Content Provider query ────────────────────────────────────────────────

    /**
     * Queries the Habit app's Content Provider and returns a list of [HabitEntry]
     * objects sorted by name. Returns an empty list if the Habit app is not
     * installed or the provider is unavailable.
     *
     * Must be called from a coroutine; switches to [Dispatchers.IO] internally.
     */
    suspend fun fetchHabits(): List<HabitEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<HabitEntry>()
        try {
            context.contentResolver.query(
                /* uri        */ HABITS_CONTENT_URI,
                /* projection */ arrayOf(COL_HABIT_NAME),
                /* selection  */ null,
                /* selArgs    */ null,
                /* sortOrder  */ null   // Tail returns habits in user-defined screen order
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(COL_HABIT_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)
                    results += HabitEntry(
                        // Use the habit name as the ID — the Tail receiver accepts a
                        // name string for EXTRA_HABIT_ID and it is stable across reorders.
                        habitId   = name,
                        habitName = name
                    )
                }
            }
        } catch (e: Exception) {
            // Provider not installed, permission denied, or column mismatch — fail silently.
            Log.w(TAG, "fetchHabits: could not query Tail app — ${e.message}")
        }
        results
    }

    // ── Per-slot persistence ──────────────────────────────────────────────────

    /** Returns the persisted habit_id for [slot], or "" if none selected. */
    fun getHabitId(slot: Slot): String =
        prefs.getString(slot.idKey, "") ?: ""

    /** Returns the persisted habit display name for [slot], or "" if none selected. */
    fun getHabitName(slot: Slot): String =
        prefs.getString(slot.nameKey, "") ?: ""

    /** Persists the selected [entry] for [slot]. */
    fun setHabit(slot: Slot, entry: HabitEntry) {
        prefs.edit()
            .putString(slot.idKey,   entry.habitId)
            .putString(slot.nameKey, entry.habitName)
            .apply()
    }

    /** Clears the selection for [slot]. */
    fun clearHabit(slot: Slot) {
        prefs.edit()
            .putString(slot.idKey,   "")
            .putString(slot.nameKey, "")
            .apply()
    }

    // ── Music habit with Time-of-Day deduplication ────────────────────────────

    /**
     * Attempts to increment the MUSIC habit slot.
     *
     * The increment is only sent if:
     * 1. The session's [AudioSetting] is [AudioSetting.MUSIC] (and at least one
     *    track actually played, so "MUSIC but nothing played" is treated as SILENCE).
     * 2. No increment has already been sent for the same [TimeOfDay] bucket
     *    (Morning / Day / Night) on the current calendar date.
     *
     * This means the maximum number of music-habit increments per day is 3
     * (one per TimeOfDay bucket).
     */
    fun sendMusicHabitIncrementIfNeeded(audioSetting: String, timeOfDay: String) {
        if (audioSetting != AudioSetting.MUSIC.name) {
            Log.d(TAG, "sendMusicHabitIncrementIfNeeded: audio=$audioSetting, not MUSIC — skipping")
            return
        }

        val tod = try { TimeOfDay.valueOf(timeOfDay) } catch (_: IllegalArgumentException) {
            Log.w(TAG, "sendMusicHabitIncrementIfNeeded: unknown timeOfDay=$timeOfDay — skipping")
            return
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val key = "habit_music_sent_${tod.name}_$today"

        if (prefs.getBoolean(key, false)) {
            Log.d(TAG, "sendMusicHabitIncrementIfNeeded: already sent for ${tod.name} on $today — skipping")
            return
        }

        // Mark as sent *before* firing so a crash mid-broadcast can't cause a double-fire
        prefs.edit().putBoolean(key, true).apply()

        sendHabitIncrement(Slot.MUSIC)
        Log.d(TAG, "sendMusicHabitIncrementIfNeeded: sent for ${tod.name} on $today")
    }

    // ── Broadcast trigger ─────────────────────────────────────────────────────

    /**
     * Sends an explicit, permission-guarded broadcast to the Habit app requesting
     * that the habit mapped to [slot] be incremented by one.
     *
     * Does nothing if no habit has been selected for [slot].
     *
     * **Deprecated in favour of [sendHabitIncrementWithMinutes]** — kept for
     * slots that are inherently count-based (apnea holds, table training, etc.).
     */
    fun sendHabitIncrement(slot: Slot) {
        sendHabitIncrementInternal(slot, minutes = null)
    }

    /**
     * Sends an explicit, permission-guarded broadcast to the Habit app requesting
     * that the habit mapped to [slot] be incremented by [minutes].
     *
     * The Tail app should read [EXTRA_MINUTES] and use it as the increment amount
     * instead of the default of 1. If the Tail app has not yet been updated, it
     * will simply ignore the extra and increment by 1 (backward compatible).
     *
     * Does nothing if no habit has been selected for [slot] or if [minutes] < 1.
     */
    fun sendHabitIncrementWithMinutes(slot: Slot, minutes: Int) {
        if (minutes < 1) {
            Log.d(TAG, "sendHabitIncrementWithMinutes(${slot.name}): minutes=$minutes < 1, skipping")
            return
        }
        sendHabitIncrementInternal(slot, minutes = minutes)
    }

    /**
     * Internal helper that fires the increment broadcast with an optional
     * [EXTRA_MINUTES] extra.
     */
    private fun sendHabitIncrementInternal(slot: Slot, minutes: Int?) {
        val habitId = getHabitId(slot)
        if (habitId.isBlank()) {
            Log.d(TAG, "sendHabitIncrement(${slot.name}): no habit selected, skipping")
            return
        }

        try {
            val intent = Intent(ACTION_INCREMENT).apply {
                // Explicit broadcast — required for reliable delivery on API 26+
                `package` = HABIT_APP_PACKAGE
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_SLOT, slot.name)
                if (minutes != null) {
                    putExtra(EXTRA_MINUTES, minutes)
                }
            }

            // receiverPermission ensures only the Habit app (which declared the
            // signature permission) can receive this broadcast.
            context.sendBroadcast(intent, PERMISSION_TAIL)
            Log.d(TAG, "sendHabitIncrement(${slot.name}): fired for habitId=$habitId" +
                    if (minutes != null) ", minutes=$minutes" else "")
        } catch (e: SecurityException) {
            // On Android 14+ (API 34), sendBroadcast with a receiverPermission that
            // is not defined by any installed app can throw SecurityException.
            // This happens when the Tail companion app is not installed.
            Log.w(TAG, "sendHabitIncrement(${slot.name}): SecurityException — " +
                    "Tail app likely not installed. ${e.message}")
        } catch (e: Exception) {
            // Catch-all: habit integration must never crash the host app.
            Log.w(TAG, "sendHabitIncrement(${slot.name}): unexpected error — ${e.message}")
        }
    }

    // ── Retroactive backfill ──────────────────────────────────────────────────

    /**
     * Sends a permission-guarded broadcast to the Habit app requesting that the
     * habit mapped to [slot] be **SET** (not incremented) to the given minute
     * values for each date in [dateMinutes].
     *
     * This is used for the one-time retroactive backfill of past session minutes.
     *
     * The map keys are ISO-8601 date strings (`yyyy-MM-dd`) and the values are
     * total minutes for that date. Tail should REPLACE the stored value for each
     * date (not add to it), making the operation idempotent.
     *
     * Does nothing if no habit has been selected for [slot] or if the map is empty.
     */
    fun sendHabitValuesForDates(slot: Slot, dateMinutes: Map<String, Int>) {
        val habitId = getHabitId(slot)
        if (habitId.isBlank()) {
            Log.d(TAG, "sendHabitValuesForDates(${slot.name}): no habit selected, skipping")
            return
        }
        if (dateMinutes.isEmpty()) {
            Log.d(TAG, "sendHabitValuesForDates(${slot.name}): empty map, skipping")
            return
        }

        // Serialise the date→minutes map as a compact JSON object.
        val json = buildString {
            append("{")
            dateMinutes.entries.forEachIndexed { i, (date, mins) ->
                if (i > 0) append(",")
                append("\"").append(date).append("\":").append(mins)
            }
            append("}")
        }

        try {
            val intent = Intent(ACTION_SET_HABIT_VALUES).apply {
                `package` = HABIT_APP_PACKAGE
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_SLOT, slot.name)
                putExtra(EXTRA_VALUES_JSON, json)
            }
            context.sendBroadcast(intent, PERMISSION_TAIL)
            Log.d(TAG, "sendHabitValuesForDates(${slot.name}): fired for habitId=$habitId, " +
                    "${dateMinutes.size} dates")
        } catch (e: SecurityException) {
            Log.w(TAG, "sendHabitValuesForDates(${slot.name}): SecurityException — " +
                    "Tail app likely not installed. ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "sendHabitValuesForDates(${slot.name}): unexpected error — ${e.message}")
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "HabitIntegrationRepo"

        /** Package name of the Tail habit-tracking app. */
        const val HABIT_APP_PACKAGE = "com.example.tail"

        /**
         * Converts a duration in seconds to whole minutes, rounded to the nearest
         * integer, with a minimum of 1. Used by all call sites that report session
         * durations to Tail so the rounding logic is consistent everywhere.
         */
        fun secondsToMinutes(seconds: Int): Int =
            kotlin.math.round(seconds / 60.0).toInt().coerceAtLeast(1)

        /**
         * Converts a duration in milliseconds to whole minutes, rounded to the
         * nearest integer, with a minimum of 1.
         */
        fun millisToMinutes(millis: Long): Int =
            kotlin.math.round(millis / 60_000.0).toInt().coerceAtLeast(1)

        /**
         * Content Provider URI exposed by the Tail app.
         * Authority: com.example.tail.provider   Path: /habits
         * Columns  : habit_id (Int index), habit_name (String)
         */
        val HABITS_CONTENT_URI: Uri =
            Uri.parse("content://com.example.tail.provider/habits")

        /** Column names returned by the Tail app's Content Provider. */
        const val COL_HABIT_ID   = "habit_id"
        const val COL_HABIT_NAME = "habit_name"

        /**
         * Broadcast action the Tail app's [HabitIncrementReceiver] listens for.
         * The receiver accepts EXTRA_HABIT_ID as either a habit name (String) or
         * a 0-based index (Int).  We send the habit name so it is human-readable
         * and resilient to reordering.
         */
        const val ACTION_INCREMENT = "com.example.tail.ACTION_INCREMENT_HABIT"

        /**
         * Intent extra key expected by the Tail app's receiver.
         * Value: the habit name (String) — matches what the ContentProvider returns
         * in the habit_name column.
         */
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"

        /** Intent extra key carrying the originating WAGS activity slot name (informational). */
        const val EXTRA_SLOT = "wags_slot"

        // ── Protocol v2: minute-based increments ───────────────────────────────

        /**
         * **Protocol v2** — Optional Int extra for [ACTION_INCREMENT].
         *
         * When present, the Tail app should use this value as the increment amount
         * instead of the default of 1. This allows WAGS to report the actual number
         * of minutes a resonance-breathing or meditation session lasted, rather
         * than a simple "did a session" = 1.
         *
         * If the Tail app has not yet been updated to read this extra, it will
         * simply ignore it and increment by 1 (fully backward compatible).
         *
         * Value: a positive Int (number of minutes). 0 or negative values are
         * never sent by WAGS.
         */
        const val EXTRA_MINUTES = "EXTRA_MINUTES"

        // ── Protocol v2: retroactive backfill ──────────────────────────────────

        /**
         * **Protocol v2** — New broadcast action for the retroactive backfill of
         * past session minutes.
         *
         * Unlike [ACTION_INCREMENT] (which adds to today's count), this action
         * asks Tail to **SET** (replace) the stored value for each date provided
         * in [EXTRA_VALUES_JSON]. This makes the operation idempotent: running
         * the backfill multiple times produces the same result.
         *
         * Extras required:
         *  - [EXTRA_HABIT_ID]    — habit name (String)
         *  - [EXTRA_VALUES_JSON] — JSON object: `{"yyyy-MM-dd": <minutes:Int>, ...}`
         *
         * Extras optional:
         *  - [EXTRA_SLOT] — WAGS slot name (informational)
         */
        const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"

        /**
         * **Protocol v2** — JSON string extra for [ACTION_SET_HABIT_VALUES].
         *
         * Format: `{"2026-01-15": 10, "2026-01-16": 5, "2026-01-20": 15}`
         *
         * Keys are ISO-8601 date strings (`yyyy-MM-dd`), values are total minutes
         * for that date (positive Int). Tail should REPLACE the stored value for
         * each date key (not add to it).
         */
        const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"

        /**
         * Signature-level permission declared by the Tail app.
         * Used both as the readPermission on the ContentProvider and as the
         * receiverPermission argument to [Context.sendBroadcast].
         */
        const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"
    }
}
