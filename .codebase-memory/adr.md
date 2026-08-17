# ADR: Contraction tables hold immediately at session start

**Date:** 2026-08-17
**Status:** Accepted

## Context
User bug note (2026-08-17): "in Till Contraction Table, there shouldn't be a rest before the first hold." Previously `ContractionTableStateMachine.start()` routed round 1 through `startBreathePhase(1)`, so every session began with a full rest countdown even though the user had nothing to recover from.

## Decision
1. `start()` now transitions straight into `beginCruise(1)` — round 1 begins holding immediately; the BREATHE phase only ever precedes rounds ≥ 2.
2. The rest schedule was shifted accordingly: `restScheduleMs` is built with `buildRestSchedule(rounds - 1, restStartSec, restEndSec)`, so `restStartSec` ("First rest") is the rest before round 2 and `restEndSec` the rest before the final round. `startBreathePhase(round)` indexes the schedule at `round - 2`. A single-round table therefore has no rest at all.
3. `ContractionTableRoundResult.restBeforeMs` for round 1 is now 0 (recorded honestly in tableParamsJson).

## Consequences
- Active screen / PiP render phases generically, so starting in CRUISE needed no UI changes beyond removing the now-dead "round 1 breathe" hint.
- Past-config restore feature (`ContractionTablePastConfig`, derived from tableParamsJson of past sessions, unfiltered) reuses the same JSON fields, so old sessions remain parseable; old sessions' round-1 `restBeforeMs` values stay as recorded.
- Related decision (same date): history chart x-axis monthly labels show month name only, appending the year on the first label and on year change (HistoryCharts.kt `axisLabels`), fixing the clipped "2026-0" labels on 1y/All timeframes.