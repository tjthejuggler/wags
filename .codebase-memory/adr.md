# ADR Addendum: All/Current header toggle + interactive progress chart

Date: 2026-08-25 (afternoon)

## Decision changes

1. **Header toggle is All/Current, not All/None.** The per-category header toggle in `SettingFilterWidgets.kt` now jumps between "All" (every option selected) and "Current" (only the setting value currently in use). It shows "Current" while more than half the options are selected, "All" otherwise (same hysteresis rule as before). Rationale: there is never a reason to have zero options selected in a category.
2. **Minimum-one-selected invariant.** `MultiSelectFilterCategory` ignores a chip tap that would deselect the last remaining option. Empty filter sets are therefore unreachable via the UI (ViewModel empty-set branches remain as dead-safety code).
3. **"Current" value sources:** the three drill screens pass their live session settings (`state.lungVolume` etc., with `effectiveTod` for time-of-day so BY_HOUR mode yields the current hour bucket). The All Records screen has no session settings, so "Current" there = the settings of the most recent record (`state.records.maxByOrNull { it.timestamp }`, tod derived from its timestamp in BY_HOUR mode). `MultiSelectFilterCategory.currentValue` is nullable; a value outside the option space falls back to `options.first()`.
4. **Progress chart (`AllRecordsProgressChart`):**
   - Draw order: connecting line (dim, 45% alpha) → dots (with dark halo) → white average trend line LAST, on top of everything, per user priority.
   - Pinch zoom: custom `awaitEachGesture` handler consumes only pinch zooms and horizontal pans; vertical swipes are NOT consumed so the surrounding LazyColumn still scrolls. Zoom window = `visibleCount = ceil(total/zoom)` points over the chronological index space; `panPos ∈ [0,1]` slides it. Zoom/pan reset when the records list changes.
   - Vertical scale (min/max/range) and the average trend line are computed from the VISIBLE slice only.
   - Tap near a point (nearest-x within a threshold) fires `onPointTap(record)`; the screen maps it to the list index (`records.size-1` reversed order) and `animateScrollToItem(3 + index)` — items 0-2 are header surface, chart, count label.
