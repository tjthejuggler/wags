# ADR: Multi-select apnea setting filters with header All/None toggle

Date: 2026-08-25

## Context
The apnea setting filters (lung volume, prep, time-of-day, posture, audio) in All Records, Min Breath, Progressive O2, and Contraction Table used single-select String state where "" meant "all", with an 'All' chip mixed in among the option chips.

## Decision
1. Filter state is now `Set<String>` per category in all 4 ViewModels (MinBreathViewModel, ProgressiveO2ViewModel, ContractionTableViewModel, AllApneaRecordsViewModel). Full option set = unfiltered; empty set = nothing matches (early-return empty list).
2. Shared UI lives in `ui/apnea/SettingFilterWidgets.kt`: `MultiSelectFilterCategory` renders a header + FlowRow of toggleable FilterChips; `AllNoneHeaderToggle` is a small pill-shaped button next to the header label. It shows "None" while selectedCount*2 > totalCount (clicking clears to empty set), otherwise "All" (clicking selects the full option set). It is visually distinct from the option chips (small bordered pill, not a FilterChip).
3. SQL compatibility: Room DAO queries only support single-value equality (`:param = '' OR field = :param`). AllApneaRecordsViewModel bridges this with `sqlFilter(selected) = selected.singleOrNull() ?: ""` for the paged fetch, then refines multi-selections in memory after the fetch. The other three ViewModels already filter in memory.
4. Time dimension duality: in BY_HOUR mode the tod option space is the 24 hour buckets (TimeBuckets.HOUR_BUCKETS) instead of Morning/Day/Night. Record matching derives the bucket from the timestamp (`byHourTod = selection.any { TimeBuckets.isHourBucket(it) }`). Summary functions (`buildMinBreathFilterSummary`, `buildProgressiveO2FilterSummary`, `buildContractionTableFilterSummary`, `buildFilterSummary`) take a `byHour` param; screens pass `timeDimension == TimeDimension.BY_HOUR`.
5. `isFiltered` (drives the quick-clear chip) uses `!selected.coversAll(options)` per category rather than isNotEmpty().
6. Nav-arg/preset entry points (`SavedStateHandle` args, `applyPresetFilters`) keep String inputs and convert internally via `presetToSelection(value, options)`.

## Consequences
- Option lists and label functions are centralized in `SettingFilterOptions`; adding a new setting option updates all filter UIs at once.
- resetFilters() sets single-value sets from current session settings; clearAllFilters() sets full option sets (dimension-aware for tod).
- The ApneaHistoryScreen StatsSettingsDialog (FILTER_ALL sentinel, single-select) is a settings selector, not a history filter, and was intentionally left unchanged.