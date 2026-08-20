# ADR: Apnea session timestamps mark session START, not save/end time

## Status
Accepted (2026-08-20)

## Context
All apnea save paths (free hold, O2/CO2 tables, Contraction Tables, Progressive O2, Min Breath, Garmin watch holds) wrote `timestamp = System.currentTimeMillis()` at SAVE time — i.e. the session END. Users expect the date/time shown in history to be when they clicked START (hold start, or eucapnic/hyperventilation prep start when prep runs).

## Decision
1. **Future sessions** record the flow-start wall-clock time:
   - Table VMs (`ContractionTableViewModel`, `MinBreathViewModel`, `ProgressiveO2ViewModel`) use their existing `sessionStartMs` (set in `startSession()`).
   - `ApneaViewModel` uses `tableSessionStartTime` for tables and `freeHoldStartTime` for the legacy free-hold path.
   - `FreeHoldActiveScreenViewModel` gained `holdFlowStartMs` + `markHoldFlowStart()` — captured at the FIRST Start click (eucapnic pacer navigation, guided-hyper countdown, or direct hold start), consumed once in `saveFreeHoldRecord`, cleared on cancel/reset.
   - `GarminApneaRepository` uses `payload.startEpochMs` instead of `endEpochMs`.
2. **Retroactive fix** via `MIGRATION_42_43` (DB v42→43): `apnea_records` rows paired with an `apnea_sessions` row (exact timestamp+tableType match — the pairing convention also used by MIGRATION_41_42 and `ApneaRecordDetailViewModel`) shift by the session's `totalSessionDurationMs`; unpaired rows (free holds, Garmin) shift by their own `durationMs` (prep time not recoverable). Sessions shift by `totalSessionDurationMs`. Records update FIRST so the pairing subquery sees unshifted session timestamps; both clamp at 1.

## Consequences
- Record↔session exact-timestamp pairing survives the migration (both sides of a pair shift by the same amount).
- Old free-hold timestamps land on hold start (prep excluded) — accepted imprecision.
- Rows with zero duration keep their timestamp (no data to shift by); nothing is deleted.
