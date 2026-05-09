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
     */
    fun sendHabitIncrement(slot: Slot) {
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
            }

            // receiverPermission ensures only the Habit app (which declared the
            // signature permission) can receive this broadcast.
            context.sendBroadcast(intent, PERMISSION_TAIL)
            Log.d(TAG, "sendHabitIncrement(${slot.name}): fired for habitId=$habitId")
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

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "HabitIntegrationRepo"

        /** Package name of the Tail habit-tracking app. */
        const val HABIT_APP_PACKAGE = "com.example.tail"

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

        /**
         * Signature-level permission declared by the Tail app.
         * Used both as the readPermission on the ContentProvider and as the
         * receiverPermission argument to [Context.sendBroadcast].
         */
        const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"
    }
}
