# ADR: Apnea Stats extremes drill-down — ranked holds list

**Date:** 2026-08-16
**Status:** Accepted

## Context
The Apnea History → Stats tab shows single-record extremes ("Highest HR", "Lowest SpO₂", start/end HR & SpO₂). Users wanted to see ALL holds ranked by the clicked metric, not just the single record holding the extreme. Additionally, clickable stat labels needed a visual affordance (underline).

## Decision
1. **New screen pair** `HoldsRankedListScreen` / `HoldsRankedListViewModel` (ui/apnea). Route `holds_ranked/{metricKey}/{lungVolume}/{prepType}/{timeOfDay}/{posture}/{audio}/{showAll}` registered in WagsNavGraph. Cards are sorted best-first (lowest-first for "Lowest …", highest-first for "Highest …"); tapping a card opens `apnea_record_detail/{recordId}`.
2. **Metric taxonomy**: `RankedHoldMetric` enum (11 entries) mirrors the Stats extremes rows. Overall metrics (MAX_HR, MIN_HR, LOWEST_SPO2) read from `apnea_records` columns; start/end metrics read from the first/last `free_hold_telemetry` sample per record via two new bulk DAO queries (`getFirstSamplesOnce` / `getLastSamplesOnce`, GROUP BY recordId) exposed as `ApneaRepository.getFirstTelemetrySamplesOnce/getLastTelemetrySamplesOnce` — one query instead of N per-record queries.
3. **Filter parity**: the ranked list receives the Stats tab's active settings + showAll flag via nav args and applies the same predicate semantics as the stats SQL (`'ALL'` or exact match) and the same physiological bounds (HR 20–250 bpm, SpO₂ 1–100 %) so rank #1 always matches the extreme shown on the Stats tab.
4. **Affordance**: labels that open the ranked list are underlined (`TextDecoration.Underline`) and brighter (TextPrimary); the row body keeps its existing tap-to-record-detail behaviour. `HistoryStatsRow` labels with an onClick (time-chart rows) are also underlined.
5. **SpO₂ chart floor removed**: `ApneaRecordDetailScreen` no longer passes `yMin = 70f` to `LineChart`/`MinBreathSessionChart`; yMin defaults to the sample minimum so the graph extends to the lowest actual reading (yMax stays 100).

## Consequences
- Adding a new extremes row to the Stats tab requires a matching `RankedHoldMetric` entry and an `onLabelClick` wiring.
- Bulk boundary-sample queries assume one representative sample per record (SQLite GROUP BY picks one row per group on ties), which matches the existing stats SQL pattern.
