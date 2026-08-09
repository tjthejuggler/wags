## ADR: Apnea hold-time minutes sent to Tail app

**Date:** 2026-08-09

### Context
Protocol v2 (2026-08-08) introduced minute-based habit reporting for resonance breathing and meditation. Apnea activities (free holds, O2/CO2 tables, Progressive O2, Min Breath) were still sending count-based increments of 1.

### Decision
Extended Protocol v2 to all four apnea activity slots. Each now sends `EXTRA_MINUTES` containing the total breath-hold time (in minutes) via `sendHabitIncrementWithMinutes()`. The `APNEA_NEW_RECORD` slot remains count-based (event-based, not duration-based).

The retroactive backfill (`HabitBackfillManager`) was extended to query all `ApneaRecordEntity` rows, group by `tableType` (null→FREE_HOLD, O2/CO2→TABLE_TRAINING, PROGRESSIVE_O2, MIN_BREATH), aggregate `durationMs` by date, and send per-slot `ACTION_SET_HABIT_VALUES` broadcasts.

### Key design choices
- Minutes = total breath-hold time, NOT session wall-clock time. This is the meaningful training metric.
- Same `durationMs` field used for both real-time and backfill, guaranteeing consistency.
- No new protocol constants needed — reuses existing `EXTRA_MINUTES` and `ACTION_SET_HABIT_VALUES`.
- Fully backward compatible: Tail ignores unknown extras and falls back to increment-by-1.
