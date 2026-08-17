# ADR: Tail slot split (O2/CO2) + Contraction Tables slots + auto-backfill on connect

**Date:** 2026-08-17
**Status:** Accepted

## Context
The Tail (habit app) integration had a single TABLE_TRAINING slot covering both O2 and CO2 tables, and the two new Contraction Tables drills (Till Contraction, Contraction Count) were firing that generic slot. Users wanted per-table-type habits and full history pushed when a new habit connection is made.

## Decision
1. **Slot enum restructure** (`HabitIntegrationRepository.Slot`): TABLE_TRAINING removed, replaced by O2_TABLE (`habit_id_o2_table`) and CO2_TABLE (`habit_id_co2_table`); added TILL_CONTRACTION and CONTRACTION_COUNT slots. A one-time init migration copies any legacy `habit_id_table_training` selection into both new table slots.
2. **Live increments dispatch by type**: ApneaViewModel COMPLETE handler selects O2_TABLE vs CO2_TABLE via `currentTable.type` (ApneaTableType); ContractionTableViewModel.saveSession selects TILL_CONTRACTION vs CONTRACTION_COUNT via `s.mode`.
3. **Per-slot backfill architecture** (`HabitBackfillManager`): new `backfillSlot(slot): SlotBackfillResult` aggregates one slot's full per-date history (minutes primary + sessions secondary) and sends the backlog; `backfill()` delegates over all backfillable slots. History dispatch keys on the legacy `ApneaRecordEntity.tableType` strings ("O2", "CO2", "WONKA_FIRST_CONTRACTION", "WONKA_ENDURANCE") which are kept for stats compatibility.
4. **Auto-backfill on connect**: `SettingsViewModel.selectHabit()` fires `backfillSlot(slot)` in a coroutine after persisting the selection, surfacing a "Backfilled <slot>: N dates (M min, S sessions)" message. Slots without minute-based history (records/readiness/music) are no-ops.

## Consequences
- Connecting any habit slot instantly seeds Tail with that activity's complete history (idempotent, Tail SETS per-date values).
- The manual "Backfill Past Sessions" action reuses the same per-slot path; BackfillResult became a map of Slot→SlotBackfillResult with a generic skipped-slots message.
- Old TABLE_TRAINING prefs don't orphan: migration seeds both split slots with the same habit.
