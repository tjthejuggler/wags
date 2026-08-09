# Tail Integration Protocol v2 — Minute-Based Habit Reporting

**Date:** 2026-08-08
**Status:** WAGS side implemented; Tail side needs changes described below.

---

## Overview

Previously, WAGS told Tail only **"did a session"** (increment by 1) when a
resonance-breathing, RF-assessment, meditation, or apnea session completed.

**Protocol v2** changes this so WAGS sends the **actual number of minutes** the
session lasted (or, for apnea, the total breath-hold time). This requires two
changes on the Tail side:

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

These WAGS slots send `EXTRA_MINUTES`:

| Slot | Source of minutes |
|------|-------------------|
| `RESONANCE_BREATHING` | Resonance breathing sessions + RF assessments |
| `MEDITATION` | Meditation / NSDR sessions |
| `FREE_HOLD` | Apnea free breath-hold (single hold duration) |
| `TABLE_TRAINING` | O₂ / CO₂ table session (sum of all hold durations) |
| `PROGRESSIVE_O2` | Progressive O₂ drill (sum of all hold durations) |
| `MIN_BREATH` | Min Breath drill (sum of all hold durations) |

Slots that remain count-based (increment by 1, no `EXTRA_MINUTES`):

| Slot | Why |
|------|-----|
| `APNEA_NEW_RECORD` | Event-based: fires once when a new personal best is achieved |
| `MORNING_READINESS` | Completion-based assessment |
| `HRV_READINESS` | Completion-based assessment |
| `RAPID_HR_CHANGE` | Completion-based assessment |
| `MUSIC` | Once-per-TimeOfDay deduplicated event |

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

1. Queries all past resonance-breathing sessions, RF assessments, meditation
   sessions, and apnea records from its local Room database.
2. Groups them by calendar date (device timezone).
3. Sums the minutes per date.
4. Sends one `ACTION_SET_HABIT_VALUES` broadcast per habit slot:
   - `RESONANCE_BREATHING` slot: combined minutes from resonance sessions +
     RF assessments.
   - `MEDITATION` slot: minutes from meditation sessions.
   - `FREE_HOLD` slot: total hold minutes from free-hold records.
   - `TABLE_TRAINING` slot: total hold minutes from O₂/CO₂ table sessions.
   - `PROGRESSIVE_O2` slot: total hold minutes from Progressive O₂ drills.
   - `MIN_BREATH` slot: total hold minutes from Min Breath drills.
5. Shows a summary (number of dates, total minutes) in the UI.

Slots with no habit selected are silently skipped.

---

## Apnea hold-time minutes

### What "minutes" means for apnea

For resonance breathing and meditation, the minutes sent to Tail are the
**session duration** (how long the user breathed / meditated).

For apnea activities, the minutes sent are the **total breath-hold time** —
the cumulative time the user spent holding their breath, **not** the total
session wall-clock time. This is the metric that matters for apnea training.

| Slot | What is summed |
|------|----------------|
| `FREE_HOLD` | Duration of the single breath hold (e.g. a 3-minute hold → 3 min) |
| `TABLE_TRAINING` | Sum of all hold durations across all rounds in the O₂/CO₂ table |
| `PROGRESSIVE_O2` | Sum of all hold durations across all rounds in the Progressive O₂ drill |
| `MIN_BREATH` | Sum of all hold durations across all rounds in the Min Breath drill |

### Example: Table Training session

An O₂ table with 8 rounds where each round has a progressively longer hold
(30 s, 45 s, 60 s, …, 2 min). The `EXTRA_MINUTES` value sent to Tail is the
**sum** of all 8 hold durations, not the longest single hold and not the
total session time (which includes breathing/ventilation periods).

### Real-time vs backfill consistency

The same `durationMs` field on the `ApneaRecordEntity` is used for both:
- **Real-time:** converted to minutes via `millisToMinutes(durationMs)` and
  sent as `EXTRA_MINUTES` immediately after the session completes.
- **Backfill:** the same `durationMs` values are queried from the database,
  grouped by date, summed, and sent via `ACTION_SET_HABIT_VALUES`.

This guarantees that running the backfill after a session produces the same
result as the real-time increment for that date.

### What Tail needs to do

**Nothing new.** The Tail receiver already handles `EXTRA_MINUTES` for
resonance/meditation (Change 1 above) and `ACTION_SET_HABIT_VALUES` for
backfill (Change 2 above). The apnea slots use the exact same protocol —
they just now include `EXTRA_MINUTES` where they previously did not.

The only requirement is that the user has mapped the apnea slots to Tail
habits in WAGS Settings → Tail App Integration. Each apnea activity has its
own independent habit slot, so minutes flow to the correct habit.

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
