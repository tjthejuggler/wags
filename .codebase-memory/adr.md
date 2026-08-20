## ADR: Trophy/PB Display-Only Time Filter & Auto-Set Strategies

**Date:** 2026-08-20
**Status:** Accepted

### Context
With By-the-Hour bucketing, the personal-bests and trophy lists explode to ~24 rows per setting combination, making the screens cluttered. Users need a way to trim the list without changing any underlying values. Separately, the free-hold "auto set" button only applied one strategy (easiest = forecast-optimal settings).

### Decision
1. **TrophyTimeFilterBar** (`ui/apnea/TrophyTimeFilterBar.kt`) is a *display-only* filter rendered as the first item of the PB LazyColumn on `PersonalBestsScreen` (all drill types) and `TrophiesTabContent` (history 🏆 tab). Three radio-style modes: `ALL` (unfiltered), `TIME_OF_DAY` (dropdown of 3; hour buckets map back via `TimeBuckets.timeOfDayNameOf`), `HOUR` (dropdown of 24). Plus a "Show Empty" toggle implemented as a highlightable label (not a checkbox), default off. Default mode is `HOUR` preselected with the current hour (`TimeBuckets.hourOfTimestamp(now)`).
2. Filtering happens entirely in composables via `PersonalBestEntry.matchesTrophyTimeFilter(...)` — no ViewModel/DB involvement. Entries whose `timeOfDay` is empty (combos not involving time) always pass. `showEmpty=false` hides `durationMs == null` rows.
3. `ApneaHistoryViewModel.loadTrophies()` no longer drops `durationMs == null` entries — empty combos flow to the UI so "Show Empty" can reveal them; they're gated at display level.
4. **Auto set → popup menu** (`RecordForecastSummary`): "easiest" keeps the old `autoSetBestSettings()` behavior; "record" calls `ApneaViewModel.autoSetRecordBest()`, which resolves the current bucket via `TimeBuckets.normalizeSessionBucket` (hour bucket in BY_HOUR mode, classic tod otherwise), fetches the best free hold for that bucket via new DAO `getBestFreeHoldForBucket` (full entity, other settings relaxed) → repo `getBestFreeHoldForTimeBucket`, and applies its 4 non-time settings. If no PB exists for the bucket, a one-shot `_flashMessage` StateFlow surfaces a Toast in ApneaScreen (pattern from TrophyChartScreen).
5. **Settings ordering convention:** the Time-of-Day / Hour-Bucket section is the LAST section in every settings/filter surface (FreeHoldSettingsDialog, MinBreath/ProgressiveO2 filter dialogs, AllApneaRecordsScreen filters, StatsSettingsDialog, ApneaSettingsContent) and tod is the last segment of the collapsed `ApneaSettingsSummaryBanner` line.
6. Drill PB labels keep exact minutes: `DrillContext.minBreath` formats as `%d:%02d` (m:ss) instead of rounding to whole minutes.

### Consequences
- PB/trophy screens stay readable in BY_HOUR mode; "All" restores the previous full view exactly.
- The trophy tab header reads "Free Hold Personal Bests" to disambiguate from drill PBs.
- `matchesTrophyTimeFilter` params are named `filterTod`/`filterHour` to avoid shadowing the entry's own `timeOfDay` field (Kotlin parameter shadowing bug caught at build time).
- Record auto-set never changes the time setting itself — the bucket is automatic in BY_HOUR mode and user-selected otherwise.