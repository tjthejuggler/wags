# ADR: Till Contraction record semantics and partial-table handling

**Date:** 2026-08-17
**Status:** Accepted

## Context
The Till Contraction drill (ContractionTableMode.TILL_CONTRACTION, tableType `WONKA_FIRST_CONTRACTION`) previously used the longest single hold as its record headline. The hold screen also carried "End Hold" and "Stop Table" buttons that muddied the drill's single-action nature (the first contraction IS the end of the hold).

## Decision
1. **Record metric = average hold time across all holds of a table.** `ContractionTableState.averageHoldMs` (mean of per-round `totalHoldMs`) is stored as the record's `durationMs` and compared in all PB pools. DB migration 41→42 recomputes legacy TILL records from `apnea_sessions.tableParamsJson` so old and new records are comparable.
2. **Hold UI = one huge First Contraction button** (150 dp). No End Hold, no Stop Table in TILL mode; Contraction Count keeps its existing controls. A record card at the bottom of the active screen shows the avg-hold record + running table average.
3. **Early end = back out.** Backing out of an active TILL table opens a dialog:
   - *Keep for stats* → `ContractionTableViewModel.savePartialSession()` saves session + record with `ApneaRecordEntity.countsAsRecord = false` (new column, DB v42). Hold minutes fire to Tail; the session appears in history tagged "partial".
   - *Discard* → `cancelSession()` wipes everything.
4. **Exclusion mechanism:** `addDrillFilter` in ApneaRecordDao appends `countsAsRecord = 1` to every drill PB query; `countByTableTypeAll` and the record-forecast record filter also exclude non-counting records. Partial tables therefore never affect records, PBs, forecasts, or tables-done counters, but remain in history and stats.

## Consequences
- Partial tables are visible in history but flagged; users comparing records see only fully-run tables in PB pools.
- PiP Stop in TILL mode routes to the partial-save path (no dialog available in PiP).
- Tables with zero finished holds that are backed out of are discarded outright (nothing to keep).