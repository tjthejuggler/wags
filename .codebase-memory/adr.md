## ADR: Apnea Secondary Value (Session Count) for Tail Integration

**Date:** 2026-08-12

### Context
Tail added secondary-value support for apnea habits. Each apnea habit can now track two values per day: minutes held (primary/Value 1) and session count (secondary/Value 2). Tail also added a "fallback to secondary" feature where days with 0 minutes but >0 sessions still count as "done" for streak purposes.

### Decision
Extended the existing secondary-value mechanism (already used for meditation) to all four apnea activity slots. After every `sendHabitIncrementWithMinutes(slot, minutes)` call for an apnea slot, a `sendSecondaryValueIncrement(slot, 1)` call is now fired. The backfill path (`HabitBackfillManager`) also builds per-date session-count maps and sends them via `sendSecondaryValuesForDates()` with a 500ms delay between primary and secondary broadcasts (Tail processes broadcasts serially through a mutex).

### Affected Slots
- `FREE_HOLD` — FreeHoldActiveScreen.kt + ApneaViewModel.kt
- `TABLE_TRAINING` — ApneaViewModel.kt
- `PROGRESSIVE_O2` — ProgressiveO2ViewModel.kt
- `MIN_BREATH` — MinBreathViewModel.kt

### Consequences
- `BackfillResult` gained `freeHoldSessions`, `tableTrainingSessions`, `progressiveO2Sessions`, `minBreathSessions` fields and a `totalSessions` computed property.
- The backfill summary message now shows total sessions across all activities instead of meditation-only.
- No changes needed to `HabitIntegrationRepository.kt` — `sendSecondaryValueIncrement()` and `sendSecondaryValuesForDates()` already existed and work for any slot.