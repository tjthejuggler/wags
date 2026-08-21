# ADR: Dimension-aware effective time bucket for the collapsed settings banner

## Context
With the "By the Hour" apnea time dimension enabled, the collapsed one-line settings
summary (`ApneaSettingsSummaryBanner`) still showed the legacy time-of-day word
("Morning"/"Day"/"Night") on all session-type screens (main apnea, free hold, O₂/CO₂
tables, table training, contraction tables, min breath, progressive O₂), because every
call site passed the raw stored `timeOfDay` value. The banner already knew how to
render hour buckets ("H14" → "14") but never received one.

## Decision
- `ApneaTimeDimensionStore` (singleton, already the owner of the dimension choice) now
  hosts the shared hour-rollover tick and exposes
  `effectiveTod(selectedTod: Flow<String>): Flow<String>` — legacy names pass through in
  TIME_OF_DAY mode; in BY_HOUR mode any legacy name is replaced with the automatic
  current-hour bucket via `TimeBuckets.normalizeSessionBucket`, re-emitted at every
  local-hour boundary so long-lived collectors never display a stale hour.
- `ApneaViewModel`'s private `hourTick`/`_effectiveTod` was refactored onto the store
  helper (identical semantics for forecasts/combo badges/filtered queries) and is now
  also exposed publicly as `effectiveTod: StateFlow<String>` for the UI.
- `FreeHoldActiveViewModel`, `ContractionTableViewModel`, `MinBreathViewModel` and
  `ProgressiveO2ViewModel` each expose the same `effectiveTod: StateFlow<String>`
  derived from their `_uiState` time-of-day field.
- All 7 `ApneaSettingsSummaryBanner` call sites pass `effectiveTod` instead of the raw
  stored value. In BY_HOUR mode the banner line now ends with the current hour ("14");
  in TIME_OF_DAY mode it is unchanged ("Morning"/"Day"/"Night").

## Consequences
- Display-only change: record saving, queries, PBs, trophies and forecasts are untouched
  (the repository already normalizes hour buckets independently).
- The banner hour stays correct across hour boundaries on screens left open, matching
  the pre-existing hourTick behavior documented for forecasts.
- Future session-type screens should reuse `timeDimensionStore.effectiveTod(...)` rather
  than re-implementing the tick locally.

---

# ADR: Conditional history-filter re-sync on resume (Min Breath & Progressive O₂)

## Context
Both drill screens reset their history filter to the current "settings to be used" on
every `ON_RESUME`. Returning from a record's detail screen therefore wiped the user's
filter edits made in the filter dialog. The reset is only wanted when the settings
themselves changed while the user was away (e.g. changed on the main apnea screen, or
applied from a record via ApneaRecordDetailViewModel's "use these settings", which
writes the shared `setting_*` SharedPreferences keys).

## Decision
- `MinBreathViewModel` / `ProgressiveO2ViewModel`:
  - `resetFilters()` now records `filtersSyncedTo`, a signature of the five settings
    (lung volume, prep type, tod normalized to the active time dimension, posture,
    audio) it synced the filters to.
  - New `syncFiltersOnResume()` is the ON_RESUME entry point: it re-reads the persisted
    `setting_*` prefs (they may have changed while the destination sat in the back
    stack). Signature unchanged → keep the user's filter edits and only reload the
    history list (so deletions/edits made in the detail screen still show up).
    Signature changed → adopt the new settings through the existing setters (so the
    banner/settings state stays truthful) and re-sync the filters to them.
  - First sync after ViewModel creation (filtersSyncedTo == null) keeps the old
    behavior: mirror the freshly loaded settings — time-of-day stays smart-set from the
    clock at init rather than adopting a stale persisted value.
- Screens call `viewModel.syncFiltersOnResume()` instead of `viewModel.resetFilters()`
  in their ON_RESUME observers. The filter dialog's Reset button still calls
  `resetFilters()` directly (explicit user action), and `clearAllFilters()` deliberately
  does NOT touch the sync signature (an "All" filter survives resumes).

## Consequences
- Drill → detail → back: filter preserved (the reported bug).
- Main apnea settings changed → re-enter drill: fresh ViewModel loads new prefs and the
  first sync maps the filter to them (unchanged from before).
- Settings applied from a record detail while the drill screen is in the back stack are
  now adopted on return (previously the drill screen kept stale settings in its banner).
- ContractionTableScreen still resets unconditionally on resume (same old pattern);
  change it to `syncFiltersOnResume()` too if the same behavior is wanted there.
