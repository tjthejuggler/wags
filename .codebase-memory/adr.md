## ADR: Trophy Chart Per-Duration Drill Contexts + Debug Queue Persistence + Debug Bubble Rotation Fix

**Date**: 2026-05-10

### Bug 1: Trophy chart missing min breath / progressive O2 trophies
**Root cause**: `TrophyChartViewModel.computeDays()` used `MIN_BREATH_ANY` and `PROGRESSIVE_O2_ANY` which pool all session durations together. A 2-min min breath PB wouldn't appear if a better 5-min record existed, because the "any" query only returns the single best across all durations.

**Fix**: Changed `computeDays()` to discover distinct `drillParamValue` values from existing records and create per-duration `DrillContext` instances (e.g. `DrillContext.minBreath(120)`, `DrillContext.progressiveO2(60)`). This matches the PersonalBests screen behavior where each duration gets its own trophy hierarchy. Falls back to `*_ANY` only when no records with `drillParamValue` exist yet.

**Files changed**: `TrophyChartViewModel.kt`

### Bug 2: Debug bubble invisible on Trophy Chart (landscape) screen
**Root cause**: `DebugBubbleOverlay` initialized `offsetX`/`offsetY` with `remember` which doesn't update when screen configuration changes. When TrophyChartScreen forces landscape rotation, the bubble's Y position (calculated from portrait height) goes off-screen.

**Fix**: Added `LaunchedEffect(screenWidthPx, screenHeightPx)` that re-clamps `offsetX` and `offsetY` to valid screen bounds whenever dimensions change.

**Files changed**: `DebugBubbleOverlay.kt`

### Bug 3: Queued debug notes lost on app restart
**Root cause**: `DebugNoteRepository._queue` was a `MutableStateFlow(emptyList())` — purely in-memory. Saved notes were persisted to SharedPreferences, but queued notes were not.

**Fix**: Added `loadQueuedNotes()` and `saveQueuedNotes()` to `DebugPreferences`, initialized `_queue` from persisted data, and added `debugPrefs.saveQueuedNotes()` calls after every queue mutation (`enqueueNote`, `removeFromQueue`, `queueSavedNote`, `submitQueue`).

**Files changed**: `DebugPreferences.kt`, `DebugNoteRepository.kt`