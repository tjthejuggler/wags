# ADR: Contraction Tables — contraction-driven drill suite replacing the Wonka prototypes

**Date:** 2026-08-16
**Status:** Accepted & Implemented

## Context
The two "Wonka" modalities (`WONKA_FIRST_CONTRACTION`, `WONKA_ENDURANCE`) existed only as never-finished inline sections on ApneaScreen, driven by `AdvancedApneaStateMachine` with fixed-ΔT logic, no per-round persistence ("historical amnesia"), and no stats/history/PB integration. Scientific review recommended a contraction-count architecture, decreasing-rest schedules, per-round result persistence, and T_cruise as a CO₂-tolerance biomarker.

## Decision
1. **Replace both prototypes with "Contraction Tables"** — a full 3-screen drill (setup/active/detail) following the Progressive O₂ / Min Breath recipe (plans/apnea_drill_screen_guide.md), with two modes in one setup screen:
   - *Till Contraction*: hold ends at first contraction; headline PB = longest hold (T_cruise).
   - *Contraction Count*: hold for N contractions (1–50); headline PB = total hold time, partitioned by `drillParamValue = target`.
2. **New drift-free state machine** (`ContractionTableStateMachine`): epoch-anchored 100 ms wall-clock ticks; phases IDLE→BREATHE→CRUISE→(STRUGGLE)→COMPLETE; per-round `ContractionTableRoundResult` accumulated in state; decreasing rest via linear interpolation rest-start→rest-end.
3. **Natural-completion observer**: unlike endless Progressive O₂, the fixed-round machine reaches COMPLETE on its own; the ViewModel's state collector detects the phase transition while `isSessionActive` and triggers `stopSession()` (which clears the flag first to prevent re-entry).
4. **Backward-compatible persistence**: keep legacy `tableType` strings "WONKA_FIRST_CONTRACTION"/"WONKA_ENDURANCE" so existing stats counters, history, ranked lists, record-detail, PB pools, and Tail habit plumbing work without schema changes. 4 entities per session (session+telemetry, record+free-hold telemetry); round log serialized into `tableParamsJson`.
5. **Delete the dead inline machinery**: AdvancedApneaScreen/ViewModel/StateMachine, WonkaConfig, TrainingModality, ProgressiveO2Generator, the unreachable ADVANCED_APNEA route, and inline session code in ApneaScreen/ApneaViewModel. ApneaScreen exposes one "Contraction Tables" section card → `contraction_table` route.
6. **Safety**: empty-lung warning dialog on EMPTY lung selection (reference pattern); hyperventilation advisory card when prep = HYPER.

## Consequences
- T_cruise decay and Cruise Ratio analytics available per session (Canvas chart on detail screen).
- PB pools: Till Contraction = one global pool; Contraction Count = pool per target value (DrillContext.contractionCount(target)).
- Detail screen keyed by sessionId (route `contraction_table_detail/{sessionId}`); ViewModel exposes both completedSessionId and completedRecordId after save.
- Tail habits: TABLE_TRAINING minutes+count on completion; APNEA_NEW_RECORD on PB.
- Build verified: `:app:compileDebugKotlin` + `installDebug` (JDK 21; note JAVA_HOME must not point at the snap Android Studio JBR 25).
