# ADR: Guided Hyperventilation for Min Breath Drill

**Date**: 2026-05-24
**Status**: Accepted

## Context
The Free Hold drill had a "Guided Hyperventilation" checkbox with configurable phase durations (relaxed exhale, purge exhale, transition) and a countdown dialog. The Min Breath drill lacked this feature despite having the same HYPER prep type option.

## Decision
Add guided hyperventilation support to the Min Breath drill, mirroring the Free Hold implementation:

1. **Shared composables**: Extract `GuidedHyperSection`, `GuidedHyperEditSheet`, and `GuidedHyperPhaseRow` from `FreeHoldActiveScreen.kt` into a new `GuidedHyperSection.kt` file with public visibility, so both screens can reuse them.

2. **MinBreathUiState**: Add `isHyperPrep`, `guidedHyperEnabled`, `guidedRelaxedExhaleSec`, `guidedPurgeExhaleSec`, `guidedTransitionSec`, `showGuidedCountdown`, `guidedCountdownComplete`, `startMp3WithHyper` fields.

3. **MinBreathViewModel**: Add setter methods (`setGuidedHyperEnabled`, `setGuidedRelaxedExhaleSec`, etc.), countdown lifecycle methods (`showGuidedCountdown`, `onGuidedCountdownComplete`, `onGuidedCountdownCancelled`), and persist guided hyper data in `saveSession` via `ApneaRecordEntity.guidedHyper`, `guidedRelaxedExhaleSec`, `guidedPurgeExhaleSec`, `guidedTransitionSec` columns.

4. **MinBreathScreen**: Show `GuidedHyperSection` when prep type is HYPER. When the user taps Start with guided hyper enabled, show `GuidedHyperCountdownDialog`. On countdown completion, auto-navigate to the active screen (which auto-starts the session).

5. **Data persistence**: Uses the same `ApneaRecordEntity` columns already present for Free Hold — no DB migration needed.

## Consequences
- Min Breath now has feature parity with Free Hold for guided hyperventilation
- The shared composables reduce code duplication
- History details for Min Breath sessions will show guided hyper usage and timing, same as Free Hold