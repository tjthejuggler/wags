# ADR: Apnea prep-type session snapshot & edit-driven PB notification

**Date:** 2026-08-18
**Status:** Accepted

## Context
Two defects: (1) `ResonancePrepGate.isLocked` (5-min staleness, 2s ticker) was collected unguarded by drill ViewModels, which force-flipped RESONANCE→NO_PREP mid-session, corrupting the saved record and PB evaluation. (2) `ApneaRecordDetailViewModel.saveEdits()` updated records without re-evaluating PB, so an edit that made a record a PB (e.g. NO_PREP→RESONANCE) never fired the Tail `APNEA_NEW_RECORD` habit increment.

## Decision
1. **Lock gates only session START.** All collectors of `resonancePrepGate.isLocked` (and the analogous hyper lock) must be guarded by an idle/session-inactive check before auto-deselecting the prep type.
2. **Session-start prep snapshot.** Drill ViewModels capture `sessionPrepType` in `startSession()`; `saveSession()` uses `sessionPrepType ?: currentState.prepType` for both `checkBroaderPersonalBest()` and the `ApneaRecordEntity`; cleared on `cancelSession()`. Mid-session setting changes can never rewrite history.
3. **Edit-driven PB transition notify.** `saveEdits()` captures `heldPbBefore` (via `holdsCurrentPb()`: `getRecordPbBadges().any { isCurrent }` for free holds, `getAllPersonalBests(drill)` for drills), and fires `HabitIntegrationRepository.Slot.APNEA_NEW_RECORD` exactly once on a none→PB transition. Harmless re-edits of an already-PB record do not re-fire.

## Consequences
- Applied in: ProgressiveO2ViewModel, MinBreathViewModel, ContractionTableViewModel, ApneaViewModel (idle guards), ApneaRecordDetailViewModel (PB transition).
- Retroactive Tail credit for a missed PB point can be delivered via `adb shell run-as com.example.wags am broadcast --user 0 -a com.example.tail.ACTION_INCREMENT_HABIT -p com.example.tail --es EXTRA_HABIT_ID "<habit name>"` (run-as supplies the signature permission; `--user 0` avoids the user -2 SecurityException). Habit names live in wags `habit_integration_prefs` (`habit_id_apnea_new_record`).