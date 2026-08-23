# ADR: Apnea History — Graphs tab redesign & Settings-tab drill-down navigation

Date: 2026-08-23

## Context
The apnea History "Graphs" tab used bare Canvas line charts with no axes, no animation and only four series. The "Settings" comparison tab showed ranked lists (master ranking + per-category) whose far-right headline metric (best/avg) had no drill-down.

## Decisions
1. **Graphs tab extracted to `ApneaGraphsTab.kt`** (was ~430 lines inside `ApneaHistoryScreen.kt`). Charts now use TextMeasurer in-canvas labels, Catmull-Rom smoothing, PathMeasure reveal animation, gradient fills, nice-tick gridlines, dashed rolling-average overlay, step mode for PB progression, and a new volume bar chart (adaptive day/week/month bucketing in `ApneaHistoryViewModel.buildVolumeBuckets`).
2. **New chart series** added to `ApneaChartData`: `pbProgression`, `volumePerBucket`(+`volumeBucketLabel`), `contractionEasePct`, `hrDrop` — all derived from existing columns, no schema change.
3. **Best-time drill-down**: `SettingsComparisonCalculator` now tracks the source record per hold (`HoldSample.recordId`, `ExtractedHold`, `SettingsOptionResult.bestRecordId`). Tapping a "best" headline navigates to `apnea_record_detail/{recordId}`.
4. **Average drill-down**: `SettingsComparisonTabContent` obtains the SAME `AllApneaRecordsViewModel` instance as the embedded All Records tab via `hiltViewModel()` (both live in the `apnea_history` NavBackStackEntry scope), applies `applyPresetFilters(...)` (new bulk setter, single reload), and the host switches `selectedTabOrdinal` to ALL_RECORDS. Hour-of-day options map to `TimeBuckets.fromHour` hour buckets ("H08"), which the DAO already supports.

## Consequences
- Headline clicks are only active when the active metric is BEST or AVG (HOLDS/SCORE headlines stay inert).
- The shared-ViewModel pattern only works because both tabs are composed inside the same nav destination; if All Records ever becomes its own destination, the preset must flow through nav args (`apnea_all_records/...` route already supports most of them).
