## Sliding Window Live Coherence Fix & Assessment Picker Layout Reorder

**Date:** 2026-08-08

### Context
Two issues reported:
1. The Sliding Window RF assessment never displayed a live coherence score (stuck at 0.0) even after several minutes of running.
2. On the RF Assessment picker screen, the posture selector and Start button were buried at the bottom of a long scrollable list, requiring scrolling to reach them.

### Decision
1. **Coherence fix** — Root cause: `AssessmentRunViewModel.isBaselinePhase` defaults to `true` and is only cleared to `false` when a stepped protocol enters `RfPhase.TEST_BLOCK`. The Sliding Window protocol emits `RfOrchestratorState.SlidingTick` states exclusively and never touches `isBaselinePhase`, so [`startLiveCoherenceLoop()`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunViewModel.kt:495) permanently early-returned at the baseline guard, keeping `liveCoherenceRatio` at 0 for the entire ~16-minute session. Fix: clear `isBaselinePhase = false` and set `currentPhaseRrStartIndex = 0` on the first `SlidingTick` (the sliding protocol has no baseline phase, so coherence should use all session RR data from the start).

2. **Layout reorder** — Moved the posture selector card, HR-device gate banner, and Start button row to the top of the scrollable Column in [`AssessmentPickerScreen`](app/src/main/java/com/example/wags/ui/breathing/AssessmentPickerScreen.kt:101), directly under the "Select Protocol" title. The protocol list, custom-duration slider, and description card now follow below a divider. This makes posture selection and the start action immediately visible without scrolling.

### Consequences
- Live coherence now updates every 2 seconds during Sliding Window sessions (same cadence as stepped protocols).
- Post-session coherence (computed in `buildEnrichedSlidingEntity`) was already correct and unchanged.
- The picker screen layout change is purely presentational; no ViewModel or navigation logic changed.
- Blast radius: 2 files, both in `ui/breathing/`. No data layer, domain, or service impact.