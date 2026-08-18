# ADR: Unified eucapnic prep completion flow — pop back to setup screen with START HOLD

**Date:** 2026-08-18
**Status:** Accepted (implemented)

## Context
The eucapnic pacer (EucapnicPacerScreen) dispatched on `sessionType` when the prep completed: FREE_HOLD popped back to the Free Hold setup screen with the `eucapnic_prep_completed` savedStateHandle flag, but MIN_BREATH / PROGRESSIVE_O2 / CONTRACTION_TABLE navigated directly to their active screens, which auto-start the hold via `LaunchedEffect(Unit)`. This caused the hold to begin immediately after prep with no user confirmation — inconsistent with the Free Hold flow and unsafe UX for apnea training.

## Decision
1. **EucapnicPacerScreen** completion is now session-type agnostic: it always sets `eucapnic_prep_completed = true` on `navController.previousBackStackEntry?.savedStateHandle` and calls `popBackStack()`. No direct navigation to active screens.
2. Every apnea setup screen that supports EUCAPNIC_DIAPHRAGMATIC prep (Min Breath, Progressive O₂, Contraction Tables — Free Hold already had it) consumes the flag in a `LaunchedEffect(Unit)`: reads it from `currentBackStackEntry?.savedStateHandle`, **clears it** (sets to false) to prevent flag resurrection on later back-stack returns, and pushes the value into ViewModel state via `setEucapnicPrepCompleted(true)`.
3. The START button on each setup screen switches behavior/label based on `eucapnicPrepCompleted`: "START EUCAPNIC" → launches the pacer; "START HOLD" → navigates to the active screen (which then auto-starts the hold deliberately). Starting the hold consumes the flag (`setEucapnicPrepCompleted(false)`), so every new session requires a fresh eucapnic prep.

## Consequences
- Consistent, deliberate hold-start UX across all apnea drills that use eucapnic prep.
- The `eucapnic_prep_completed` savedStateHandle key is the single inter-screen contract for this flow; new setup screens integrating the pacer must implement the same consume-and-clear pattern.
- Files touched: EucapnicPacerScreen.kt, MinBreathScreen.kt, MinBreathViewModel.kt, ProgressiveO2Screen.kt, ProgressiveO2ViewModel.kt, ContractionTableScreen.kt, ContractionTableViewModel.kt, FreeHoldActiveScreen.kt (title fix "Breath Hold" → "Free Hold").