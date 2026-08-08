# Tail Integration Protocol v2 — Minute-Based Habit Reporting

**Date:** 2026-08-08
**Status:** WAGS side implemented; Tail side needs changes described below.

---

## Overview

Previously, WAGS told Tail only **"did a session"** (increment by 1) when a
resonance-breathing, RF-assessment, or meditation session completed.

**Protocol v2** changes this so WAGS sends the **actual number of minutes** the
session lasted. This requires two changes on the Tail side:

1. **Real-time increments** — read a new `EXTRA_MINUTES` extra from the existing
   `ACTION_INCREMENT_HABIT` broadcast and use it as the increment amount.
2. **Retroactive backfill** — handle a new `ACTION_SET_HABIT_VALUES` broadcast
   that SETS (replaces) the stored value for multiple dates at once.

Both changes are **backward compatible**: if Tail ignores the new extras/actions,
the old behaviour (increment by 1) still works.

---

## Change 1: Real-Time Minute Increments

### What WAGS sends

| Field | Value |
|-------|-------|
| **Action** | `com.example.tail.ACTION_INCREMENT_HABIT` (unchanged) |
| **Package** | `com.example.tail` (unchanged) |
| **Permission** | `com.example.tail.permission.TAIL_INTEGRATION` (unchanged) |
| `EXTRA_HABIT_ID` | Habit name (String) — unchanged |
| `EXTRA_SLOT` (aka `wags_slot`) | Slot name (String) — unchanged, informational |
| **`EXTRA_MINUTES`** (NEW) | **Int** — number of minutes to add. Always ≥ 1. |

### What Tail needs to do

In [`HabitIncrementReceiver.onReceive()`](app/src/main/java/com/example/tail/ipc/HabitIncrementReceiver.kt:47),
after resolving the habit name, check for `EXTRA_MINUTES`:

```kotlin
// Resolve the increment amount: use EXTRA_MINUTES if present, default to 1
val amount = if (intent.hasExtra("EXTRA_MINUTES")) {
    intent.getIntExtra("EXTRA_MINUTES", 1)
} else {
    1
}

// Use 'amount' instead of the hardcoded 1:
habitsRepo.incrementHabit(uri, appContext, habitName, amount)
```

That's it for real-time. The existing `incrementHabit(uri, context, name, amount)`
method already accepts an `amount` parameter — the receiver just hardcodes `1`.

### Affected slots

Only these WAGS slots send `EXTRA_MINUTES`:

| Slot | Source of minutes |
|------|-------------------|
| `RESONANCE_BREATHING` | Resonance breathing sessions + RF assessments |
| `MEDITATION` | Meditation / NSDR sessions |

All other slots (apnea, tables, readiness, etc.) continue to send the old
increment-by-1 behaviour with no `EXTRA_MINUTES`.

### Minute rounding

WAGS rounds session duration to the nearest whole minute, with a minimum of 1:

```kotlin
fun secondsToMinutes(seconds: Int): Int =
    round(seconds / 60.0).toInt().coerceAtLeast(1)
```

Examples:
- 30 s → **1 min**
- 90 s → **2 min**
- 5 min 30 s → **6 min**
- 10 min exactly → **10 min**

---

## Change 2: Retroactive Backfill (SET values for past dates)

### What WAGS sends

| Field | Value |
|-------|-------|
| **Action** | **`com.example.tail.ACTION_SET_HABIT_VALUES`** (NEW) |
| **Package** | `com.example.tail` |
| **Permission** | `com.example.tail.permission.TAIL_INTEGRATION` |
| `EXTRA_HABIT_ID` | Habit name (String) |
| `EXTRA_SLOT` (aka `wags_slot`) | Slot name (String) — informational |
| **`EXTRA_VALUES_JSON`** (NEW) | **JSON String** — see below |

### `EXTRA_VALUES_JSON` format

A compact JSON object mapping ISO-8601 date strings to integer minute values:

```json
{"2026-01-15": 10, "2026-01-16": 5, "2026-01-20": 15}
```

- **Keys:** `yyyy-MM-dd` date strings (e.g. `"2026-01-15"`).
- **Values:** Total minutes for that date (positive `Int`).
- Tail should **SET** (replace) the stored value for each date — not add to it.
  This makes the operation **idempotent**: running the backfill multiple times
  produces the same result.

### What Tail needs to do

#### Option A: New BroadcastReceiver (recommended)

Create a new receiver registered in `AndroidManifest.xml`:

```xml
<receiver
    android:name=".ipc.HabitValueSetReceiver"
    android:exported="true"
    android:permission="com.example.tail.permission.TAIL_INTEGRATION">
    <intent-filter>
        <action android:name="com.example.tail.ACTION_SET_HABIT_VALUES" />
    </intent-filter>
</receiver>
```

The receiver:

```kotlin
class HabitValueSetReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_HABIT_VALUES) return

        val habitName = intent.getStringExtra(EXTRA_HABIT_ID)
        val json = intent.getStringExtra(EXTRA_VALUES_JSON)
        if (habitName.isNullOrBlank() || json.isNullOrBlank()) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            try {
                val settingsRepo = SettingsRepository(appContext)
                val habitsRepo = HabitsRepository()
                val settings = settingsRepo.settingsFlow.first()

                val uri = Uri.parse(settings.fileUri)

                // Parse the JSON: {"2026-01-15": 10, ...}
                val dateValues = parseDateMinuteJson(json)

                // SET each date's value (replace, not add)
                for ((dateStr, minutes) in dateValues) {
                    val date = LocalDate.parse(dateStr)
                    habitsRepo.setHabitValueForDate(uri, appContext, habitName, minutes, date)
                }

                HabitIncrementBus.emit(habitName)
                Log.i("HabitValueSetRx", "Set ${dateValues.size} dates for '$habitName'")
            } catch (e: Exception) {
                Log.e("HabitValueSetRx", "Failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun parseDateMinuteJson(json: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        // Use org.json.JSONObject (available on Android) or your JSON library
        val obj = org.json.JSONObject(json)
        for (key in obj.keys()) {
            result[key] = obj.getInt(key)
        }
        result
    }
}
```

#### Option B: Extend the existing receiver

Alternatively, add an `<action>` to the existing `HabitIncrementReceiver`'s
intent-filter and handle both actions in `onReceive()`.

#### New repository method needed

Add a `setHabitValueForDate` method to `HabitsRepository` (analogous to the
existing `incrementHabitForDate`, but SET instead of ADD):

```kotlin
suspend fun setHabitValueForDate(
    uri: Uri,
    context: Context,
    habitName: String,
    value: Int,
    date: LocalDate
): HabitsDatabase = withContext(Dispatchers.IO) {
    val loadResult = loadDatabaseResult(uri, context)
    if (loadResult !is HabitsLoadResult.Success) {
        throw HabitsLoadFailedException(loadResult)
    }
    val db = loadResult.db.toMutableMap()
    val dateStr = dateString(date)

    val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
    habitEntries[dateStr] = value  // SET, not add

    db[habitName] = habitEntries.toSortedMap()

    saveDatabase(uri, context, db)
    db
}
```

---

## How the backfill is triggered

The user taps **"Backfill Past Sessions" → "Send"** in WAGS Settings →
Tail App Integration. WAGS then:

1. Queries all past resonance-breathing sessions, RF assessments, and meditation
   sessions from its local Room database.
2. Groups them by calendar date (device timezone).
3. Sums the minutes per date.
4. Sends one `ACTION_SET_HABIT_VALUES` broadcast per habit slot:
   - `RESONANCE_BREATHING` slot: combined minutes from resonance sessions +
     RF assessments.
   - `MEDITATION` slot: minutes from meditation sessions.
5. Shows a summary (number of dates, total minutes) in the UI.

Slots with no habit selected are silently skipped.

---

## Summary of all constants

| Constant | Value | Used in |
|----------|-------|---------|
| `ACTION_INCREMENT_HABIT` | `com.example.tail.ACTION_INCREMENT_HABIT` | Existing — real-time |
| `ACTION_SET_HABIT_VALUES` | `com.example.tail.ACTION_SET_HABIT_VALUES` | **New** — backfill |
| `EXTRA_HABIT_ID` | `EXTRA_HABIT_ID` | Both |
| `EXTRA_SLOT` / `wags_slot` | `wags_slot` | Both (informational) |
| `EXTRA_MINUTES` | `EXTRA_MINUTES` | **New** — real-time |
| `EXTRA_VALUES_JSON` | `EXTRA_VALUES_JSON` | **New** — backfill |
| `PERMISSION_TAIL` | `com.example.tail.permission.TAIL_INTEGRATION` | Both |

---

## Migration notes

- **No data migration needed.** The backfill SET operation will overwrite any
  old "1" values that were previously sent for past dates, replacing them with
  the actual minute totals.
- **Today's value:** If the user does a session today and then runs the
  backfill, today's value will be SET to the total minutes from all of today's
  sessions (including the one just completed). This is correct behaviour.
- **Backward compatibility:** Until Tail is updated, WAGS broadcasts will still
  be received — Tail will just ignore the new extras and increment by 1 as
  before. No breakage.
