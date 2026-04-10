# WAGS — Active Context

*Last updated: 2026-04-10 06:43 UTC-6*

### 2026-04-10 06:43 (UTC-6)

**Follow-up fixes for Min Breath detail screen**

1. **Removed "Total Hold Time" from Summary section** — For MIN_BREATH records, the Duration/Total Hold Time row is no longer shown in the top Summary card since it's already displayed in the Min Breath Session section below.

2. **Header changed to "Session Details" / "Min Breath"** — For MIN_BREATH records, the top bar now shows "Session Details" as the title with "Min Breath" as a smaller subtitle below it. Other record types still show "Hold Detail".

3. **Hold Breakdown shows breathing time** — Removed ⚡ contraction time from hold breakdown. Now shows breathing time after each hold with comma separator (e.g. "Hold #1: 1m 30s, breath 15s"). Per-breath-period durations are now tracked in the ViewModel (`breathDurations` map) and stored in `tableParamsJson` as `breathDurationMs` per hold. For old data without per-period breath durations, the total breath time is distributed evenly.

4. **DB migration v28→v29** — Backfills old MIN_BREATH records' `durationMs` with `totalHoldTimeMs` from the matching session's `tableParamsJson`. This fixes the All Records card showing the wrong value for old records.

Files modified: `ApneaRecordDetailScreen.kt`, `MinBreathDetailContent.kt`, `MinBreathViewModel.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`

### 2026-04-10 06:10 (UTC-6)

**Fixed: Min Breath drill — 7 issues across detail screen, active screen, All Records, and trophy system**

1. **Detail screen chart now shows entire session** — For MIN_BREATH records, HR/SpO₂ charts use `MinBreathSessionChart` (new composable in `MinBreathDetailContent.kt`) that plots data across the full session duration with shaded breathing-period bands and dashed vertical lines for first contractions.

2. **Button colors now greyscale** — Replaced `TealButton` (teal `#4ECDC4`) and `ContractionOrange` (orange `#E8A849`) with `ButtonPrimary`/`SurfaceVariant`/`TextPrimary` greyscale theme colors in `MinBreathActiveScreen.kt`. "BREATHING" label also changed from teal to `TextPrimary`.

3. **First contraction data now displayed** — The "Table Session" section for MIN_BREATH now uses `MinBreathSessionDetails` composable which correctly parses the `tableParamsJson` holds array format (`{"holds":[{"hold":1,"durationMs":...,"contractionMs":...}]}`) and shows per-hold breakdown with ⚡ contraction times.

4. **"Table Session" renamed to "Min Breath Session"** — The detail screen now shows "Min Breath Session" header for MIN_BREATH records, with Min Breath-specific layout (total hold time, total breath time, hold %, hold breakdown). Generic "Table Session" layout preserved for O₂/CO₂ tables.

5. **Total hold time shown in detail screen** — Summary card shows "Total Hold Time" instead of "Duration" for MIN_BREATH records. The `durationMs` field on `ApneaRecordEntity` now stores `totalHoldTimeMs` (not longest single hold) for Min Breath records.

6. **Total hold time on All Records card** — Min Breath cards in the All Records list now show "Total hold time: X" instead of "Longest hold: X". Progress chart Y-label changed to "Total hold time".

7. **Trophy/PB system now based on total hold time** — Since `durationMs` now stores `totalHoldTimeMs` for Min Breath, the existing `checkBroaderPersonalBest()` call automatically compares total hold times. PB celebrations and trophy tiers are now correctly based on cumulative hold time across the session.

Key files:
- New: `MinBreathDetailContent.kt` (~300 lines) — `MinBreathSessionDetails`, `MinBreathSessionChart`, `parseMinBreathParams()`
- Modified: `MinBreathActiveScreen.kt` — greyscale button colors
- Modified: `MinBreathViewModel.kt` — `durationMs = totalHoldTimeMs` (was `longestHoldMs`)
- Modified: `ApneaRecordDetailScreen.kt` — Min Breath session section, session-aware charts, "Total Hold Time" label
- Modified: `AllApneaRecordsScreen.kt` — "Total hold time" on Min Breath cards
- Modified: `AllApneaRecordsViewModel.kt` — chart Y-label "Total hold time"

### 2026-04-09 20:57 (UTC-6)

**Fixed: Trophy display uses specific param value + shows param label + refreshes on resume**

Trophy display on main ApneaScreen now shows PBs for the **currently selected** breath period / session duration (not across all). When user changes param in drill setup screen and navigates back, trophies update via `refreshDrillParams()` on ON_RESUME. Param label shown above trophies (e.g. "60s breath period", "5min session"). Trophy click navigates with specific `drillParamValue`.

Key changes:
- `ApneaViewModel.kt`: Added `_progO2BreathPeriodSec` / `_minBreathSessionDurationSec` MutableStateFlows. Trophy queries use specific `DrillContext.progressiveO2(bp)` / `DrillContext.minBreath(sd)`. Added `refreshDrillParams()`.
- `ApneaUiState`: Added `progO2BreathPeriodSec`, `minBreathSessionDurationSec` fields.
- `ApneaScreen.kt`: Added lifecycle observer for ON_RESUME → `refreshDrillParams()`. `DrillSectionContent` shows `paramLabel`. Trophy click passes specific `drillParamValue`.

### 2026-04-09 20:31 (UTC-6)

**Added: Trophy display on main ApneaScreen for Progressive O₂ and Min Breath**

Added `DrillSectionContent` composable, `getDrillBestAndTrophy()` repo method, `computeBroadestCurrentCategoryForDrill()`, `PROGRESSIVE_O2_ANY` / `MIN_BREATH_ANY` DrillContext constants.

### 2026-04-09 20:23 (UTC-6)

**Implemented: Universal Trophy System (DrillContext abstraction)**

The trophy/personal-best system has been generalized from free-hold-only to work with **any drill type**. Each drill type × drill-specific parameter value gets its own full 6-tier trophy hierarchy across the 5 standard apnea settings.

**Core abstraction:** `DrillContext` data class (`domain/model/DrillContext.kt`) identifies a PB pool:
- `DrillContext.FREE_HOLD` → `tableType IS NULL` (unchanged behavior)
- `DrillContext.progressiveO2(breathPeriodSec=60)` → `tableType='PROGRESSIVE_O2' AND drillParamValue=60`
- `DrillContext.minBreath(sessionDurationSec=300)` → `tableType='MIN_BREATH' AND drillParamValue=300`

**Changes made:**
1. **New file:** `domain/model/DrillContext.kt` (~50 lines) — DrillContext data class with factory methods and `fromNavArgs()` for navigation reconstruction
2. **DB migration v26→v27:** Added `drillParamValue INTEGER DEFAULT NULL` column to `apnea_records` table
3. **DAO generalization:** New `buildBestDrillQuery()`, `buildIsBestDrillQuery()`, `buildWasBestAtTimeDrillQuery()`, `buildBestDrillRecordQuery()`, `buildAllDrillRecordsQuery()` functions. Old free-hold builders are now thin wrappers.
4. **Repository generalization:** New drill-aware `checkBroaderPersonalBest(drill, ...)`, `getAllPersonalBests(drill)`, `getAllRecordsForChart(drill, ...)` methods. Old free-hold methods unchanged.
5. **PersonalBestsViewModel:** Reads `DrillContext` from `SavedStateHandle` nav args, passes to `getAllPersonalBests(drill)`
6. **PersonalBestsScreen:** Shows drill name in title bar when not free hold; passes drill params to chart navigation
7. **PbChartViewModel:** Reads `DrillContext` from `SavedStateHandle`, uses `getAllRecordsForChart(drill, ...)` for non-free-hold drills
8. **Navigation routes:** `PERSONAL_BESTS` and `PB_CHART` now accept optional `drillType` and `drillParamValue` query params with empty defaults
9. **ProgressiveO2ViewModel:** Sets `drillParamValue=breathPeriodSec` on record save, checks PB before saving, shows celebration dialog
10. **ProgressiveO2ActiveScreen:** Shows `NewPersonalBestDialog` when `state.newPersonalBest != null`
11. **ProgressiveO2Screen:** Added "🏆 Personal Bests" `OutlinedButton` navigating to `personalBests(drillType="PROGRESSIVE_O2", drillParamValue=breathPeriodSec)`
12. **MinBreathViewModel:** Sets `drillParamValue=sessionDurationSec` on record save, checks PB before saving, shows celebration dialog
13. **MinBreathActiveScreen:** Shows `NewPersonalBestDialog` when `state.newPersonalBest != null`
14. **MinBreathScreen:** Added "🏆 Personal Bests" `OutlinedButton` navigating to `personalBests(drillType="MIN_BREATH", drillParamValue=sessionDurationSec)`

**Zero regression risk for free holds:** All existing free-hold PB behavior is unchanged — old methods are thin wrappers around the new generalized ones, and `DrillContext.FREE_HOLD` produces identical SQL (`tableType IS NULL`).

**Build status:** Compiles successfully. Device was offline so `installDebug` failed at install step only.

Files created (1): `domain/model/DrillContext.kt`
Files modified (13): `ApneaRecordEntity.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`, `ApneaRecordDao.kt`, `ApneaRepository.kt`, `PersonalBestsViewModel.kt`, `PersonalBestsScreen.kt`, `PbChartViewModel.kt`, `WagsNavGraph.kt`, `ProgressiveO2ViewModel.kt`, `ProgressiveO2ActiveScreen.kt`, `ProgressiveO2Screen.kt`, `MinBreathViewModel.kt`, `MinBreathActiveScreen.kt`, `MinBreathScreen.kt`, `ApneaScreen.kt`

---

### 2026-04-09 15:00 (UTC-6)

**Three Fixes for Progressive O₂ Screens**

1. **Fix 1 — Use existing ApneaRecordDetailScreen for Progressive O₂ details:**
   - Changed `completedSessionId` → `completedRecordId` in `ProgressiveO2UiState` and `ProgressiveO2ViewModel`. The `saveSession()` method now returns the `recordId` (from `apneaRepository.saveRecord()`) instead of `sessionId`.
   - In `ProgressiveO2ActiveScreen.kt`, the "View Details" button now navigates to `WagsRoutes.apneaRecordDetail(recordId)` instead of `WagsRoutes.progressiveO2Detail(sessionId)`.
   - Removed `PROGRESSIVE_O2_DETAIL` route, `progressiveO2Detail()` helper, and `ProgressiveO2DetailScreen` import from `WagsNavGraph.kt`. The orphaned `ProgressiveO2DetailScreen.kt` and `ProgressiveO2DetailViewModel.kt` files remain but are unreferenced.

2. **Fix 2 — Replace past sessions list with breath period history:**
   - Replaced `ProgressiveO2PastSession` data class with `BreathPeriodHistory(breathPeriodSec, maxHoldReachedSec, sessionCount)`.
   - Renamed `loadPastSessions()` → `loadBreathPeriodHistory()` in ViewModel. New `buildBreathPeriodHistory()` method groups all PROGRESSIVE_O2 sessions by `breathPeriodSec` from `tableParamsJson`, computes max completed hold per group.
   - Replaced `PastSessionsSection`/`PastSessionCard` composables with `BreathPeriodHistorySection` — simple clickable rows showing breath period and max hold. Clicking a row sets the current breath period to that value. Currently selected breath period is highlighted.

3. **Fix 3 — Center the breath period stepper:**
   - Restructured `BreathPeriodSection` composable: Column with `horizontalAlignment = CenterHorizontally`, Row with `horizontalArrangement = Center`, fixed `widthIn(min = 80.dp)` on value text. Title upgraded from `titleSmall`/`TextSecondary` to `titleMedium`/`TextPrimary`. Spacers (24.dp) between buttons and value for symmetry.

Files modified:
1. `ui/apnea/ProgressiveO2ViewModel.kt` — UI state, data classes, save/load methods
2. `ui/apnea/ProgressiveO2ActiveScreen.kt` — Navigation to record detail
3. `ui/apnea/ProgressiveO2Screen.kt` — Breath period history section, centered stepper
4. `ui/navigation/WagsNavGraph.kt` — Removed detail route/import

---

### 2026-04-09 14:34 (UTC-6)

**Fix: Progressive O₂ Screen Navigation + Greyscale Compliance**

- Fixed past session cards in `ProgressiveO2Screen.kt` navigating to wrong screen — changed `WagsRoutes.sessionAnalytics(sessionId)` → `WagsRoutes.progressiveO2Detail(sessionId)` so cards now open the Progressive O₂ Detail screen instead of the generic SessionAnalytics screen.
- Fixed `ProgressiveO2ActiveScreen.kt` using colored text (red `#FF6B6B` for HOLD, teal `#4ECDC4` for BREATHE) — replaced all `Color(...)` usages with greyscale theme colors: phase labels → `TextPrimary`, countdown timer → `TextPrimary`, stop button → `TextSecondary` border/text, round ✓/✗ icons → `TextPrimary`/`TextSecondary`. Removed `HoldColor`/`BreatheColor` constants and `Color` import.
- Fixed `ProgressiveO2DetailScreen.kt` HR chart line color from red `#FF6B6B` → light grey `#D0D0D0` (greyscale). Changed HR stat text labels (`SmallLabel`, `StatBox`) from `HrLineColor` → `TextPrimary` for greyscale compliance. SpO₂ chart line was already grey (`#B0B0B0`), no change needed.

Files modified:
1. `ui/apnea/ProgressiveO2Screen.kt` — Line 162: navigation route fix
2. `ui/apnea/ProgressiveO2ActiveScreen.kt` — Removed colored constants, replaced all Color usages with greyscale theme colors
3. `ui/apnea/ProgressiveO2DetailScreen.kt` — HR line color greyscale, stat text labels greyscale

---

### 2026-04-09 14:08 (UTC-6)

**Follow-up: Progressive O₂ — Settings Banner, Song Picker, and Guide Document**

- Added clickable `ApneaSettingsSummaryBanner` to the Progressive O₂ setup screen — tapping opens `FreeHoldSettingsDialog` popup (monochrome greyscale, matching app aesthetic). Settings changes persist to SharedPreferences.
- Added Spotify song picker to Progressive O₂ setup screen — "Choose a Song" button when audio=MUSIC, `SongPickerDialog`, `SpotifyConnectPrompt` when not connected, selected song banner.
- Added Spotify integration to `ProgressiveO2ViewModel` — injected `SpotifyManager`, `SpotifyApiClient`, `SpotifyAuthManager`; added `loadPreviousSongs()`, `selectSong()`, `clearSelectedSong()` methods.
- Created comprehensive guide document at `plans/apnea_drill_screen_guide.md` — 12-section, ~830-line guide for creating new apnea drill screens. Covers state machine, ViewModel, 3 screens, navigation, data storage, reusable components, and step-by-step checklist. Can be used as a template for Min Breath, Wonka, and future drills.

Files modified:
1. `ui/apnea/ProgressiveO2ViewModel.kt` — Added Spotify DI, settings state/setters, song picker methods
2. `ui/apnea/ProgressiveO2Screen.kt` — Added settings banner, settings dialog, song picker UI

Files created:
1. `plans/apnea_drill_screen_guide.md` — Comprehensive guide for creating new apnea drill screens

---

### 2026-04-09 14:07 (UTC-6)

**Created: Apnea Drill Screen Guide Document**

- **What:** Wrote a comprehensive guide document at `plans/apnea_drill_screen_guide.md` (830 lines) explaining how to create a new dedicated apnea drill screen in the WAGS app.
- **Purpose:** Serves as a repeatable recipe/template for implementing Min Breath, Wonka: Till Contraction, Wonka: Endurance, or any future drill type. Based on the Progressive O₂ implementation.
- **Sections covered (12):**
  1. Overview — 3-screen flow (Setup → Active → Detail)
  2. Files to Create (6 per drill)
  3. Files to Modify (always 2: WagsNavGraph.kt + ApneaScreen.kt)
  4. State Machine Pattern — Phase enum, State data class, timer tick, round transitions
  5. ViewModel Pattern — DI, UI state, combined StateFlow, settings, Spotify, telemetry, audio/haptic
  6. Setup Screen Pattern — TopAppBar, settings banner, song picker, config inputs, past sessions
  7. Active Screen Pattern — Guards, auto-start, phase display, vitals, rounds list, stop button
  8. Detail Screen Pattern — Summary card, HR/SpO₂ charts, rounds breakdown
  9. Navigation Wiring — Routes, composable blocks, imports, ApneaScreen update
  10. Data Storage — 4 entities (ApneaSessionEntity, ApneaRecordEntity, TelemetryEntity, FreeHoldTelemetryEntity), tableParamsJson format, history integration
  11. Reusable Components — 10 existing composables to reuse
  12. Step-by-Step Checklist — 7 phases with ~50 checkbox items
- **References actual file paths** throughout so the reader can look at the real Progressive O₂ code
- **No code changes** — documentation only

---

### 2026-04-09 13:57 (UTC-6)

**Added: Clickable Settings Banner + Song Picker to ProgressiveO2Screen**

- **What changed:** The Progressive O₂ setup screen now has a clickable settings summary banner at the top (showing lung volume, prep type, time of day, posture, audio) that opens the same `FreeHoldSettingsDialog` popup used by `FreeHoldActiveScreen`. Also added a "Choose a Song" button with full Spotify song picker integration.
- **Settings banner:** `ApneaSettingsSummaryBanner` displayed at the top of the body column, before the explanation card. Tapping opens `FreeHoldSettingsDialog` with filter chips for all 5 settings. Changes are persisted to SharedPreferences immediately.
- **Song picker:** When audio setting is MUSIC, shows `SongPickerButton` (if Spotify connected) or `SpotifyConnectPrompt` (if not). `SelectedSongBanner` shown when a song is pre-selected. Full `SongPickerDialog` with song list, sort options, and pre-load into Spotify.
- **Monochrome check:** `FreeHoldSettingsDialog` already uses the app's greyscale palette (`SurfaceVariant`, `TextPrimary`, `TextSecondary`) — no changes needed.
- **ViewModel changes:** `ProgressiveO2ViewModel` now injects `SpotifyManager`, `SpotifyApiClient`, `SpotifyAuthManager`. Added settings state fields (`lungVolume`, `prepType`, `timeOfDay`, `posture`, `audio`, `isMusicMode`, `spotifyConnected`) and setter methods (`setLungVolume`, `setPrepType`, `setTimeOfDay`, `setPosture`, `setAudio`). Added song picker methods (`loadPreviousSongs`, `selectSong`, `clearSelectedSong`) following the same pattern as `AdvancedApneaViewModel`. `saveSession()` now reads settings from UI state instead of directly from SharedPreferences.
- Files modified:
  1. `ui/apnea/ProgressiveO2ViewModel.kt` — Added Spotify dependencies, settings state fields, settings setters, song picker methods
  2. `ui/apnea/ProgressiveO2Screen.kt` — Added settings banner, settings dialog, song picker UI

---

### 2026-04-09 13:30 (UTC-6)

**Implemented: Progressive O₂ Drill — Dedicated Screen with Endless Mode**

- **What changed:** The Progressive O₂ drill was completely revamped from an inline accordion card in ApneaScreen to a full 3-screen flow: Setup → Active → Detail.
- **New behavior:** The drill is now endless — starts at 15s hold, adds 15s each round (15→30→45→60→…), with a user-configurable breathing period between holds. Only ends when the user taps "Stop Drill".
- **Setup screen:** Breath period stepper (15–180s, ±5s), explanation card, past session history grouped by breath period showing max hold reached and total hold time.
- **Active screen:** Giant countdown timer, phase-colored display (HOLD=red, BREATHE=teal), live HR/SpO₂, completed rounds list, stop button.
- **Detail screen:** Post-session analytics with HR line chart, SpO₂ line chart, session summary (total hold time, max hold, rounds, duration), round-by-round breakdown.
- **Data storage:** Sessions saved as `ApneaSessionEntity` (tableType=PROGRESSIVE_O2, variant=ENDLESS) + `ApneaRecordEntity` (durationMs=longest completed hold) + `TelemetryEntity` (HR/SpO₂ samples). Per-round data in `tableParamsJson`.
- **History integration:** Progressive O₂ sessions appear in All Records, Stats, and Calendar tabs of ApneaHistoryScreen (already supported via existing `tableType` filtering).
- **No DB migration needed** — all data fits within existing schema.

Files created (7):
1. `domain/usecase/apnea/ProgressiveO2StateMachine.kt` — Endless state machine (IDLE→HOLD→BREATHING→COMPLETE)
2. `ui/apnea/ProgressiveO2ViewModel.kt` — Shared ViewModel for all 3 screens
3. `ui/apnea/ProgressiveO2Screen.kt` — Setup/landing screen
4. `ui/apnea/ProgressiveO2ActiveScreen.kt` — Active drill screen
5. `ui/apnea/ProgressiveO2DetailScreen.kt` — Post-session analytics
6. `ui/apnea/ProgressiveO2DetailViewModel.kt` — Detail screen ViewModel

Files modified (4):
1. `ui/navigation/WagsNavGraph.kt` — Added 3 routes + composable destinations
2. `ui/apnea/ApneaScreen.kt` — Replaced inline session with navigation button
3. `ui/apnea/ProgressiveO2Screen.kt` — Fixed route placeholder
4. `ui/apnea/ProgressiveO2ActiveScreen.kt` — Fixed route placeholder + popUpTo

## Current State

The project is in an **advanced implementation stage**. All major architecture phases from the plan have been implemented. The codebase has grown significantly beyond the original architecture plan with additional features.

## Recently Active Areas

- **Progressive O₂ Drill implementation (complete)** — All 3 screens + ViewModel + StateMachine created. Detail screen (`ProgressiveO2DetailScreen.kt` + `ProgressiveO2DetailViewModel.kt`) added for post-session analytics. Nav routes wired into `WagsNavGraph.kt` (3 routes: `PROGRESSIVE_O2`, `PROGRESSIVE_O2_ACTIVE`, `PROGRESSIVE_O2_DETAIL`). `ApneaScreen.kt` Progressive O₂ section replaced from inline `InlineAdvancedSessionContent` to a navigation card with description + "Open Progressive O₂" button. `ProgressiveO2Screen.kt` and `ProgressiveO2ActiveScreen.kt` updated to use `WagsRoutes` constants instead of hardcoded strings. Active→Detail navigation uses `popUpTo(inclusive=true)` so back from detail goes to setup, not active screen.
- **HRV pipeline** — Multiple HRV use case files open (RrPreFilter, Phase1/2, TimeDomain, PchipResampler)
- **Domain models** — Several model files open (RrInterval, HrvMetrics, ReadinessScore, ApneaRecord, etc.)
- **BLE layer** — BlePermissionManager, AccRespirationEngine open

## Features Implemented (based on file structure)

### Fully Built Out
- ✅ HRV processing pipeline (artifact correction, time-domain, frequency-domain)
- ✅ Morning readiness (basic HRV + orthostatic with stand detection)
- ✅ Resonance frequency breathing (pacer, assessment, history)
- ✅ Apnea training (free hold, O2/CO2 tables, advanced tables, personal bests, history, analytics)
- ✅ Meditation/NSDR (session, history, detail, audio)
- ✅ BLE layer (Polar manager, service, permissions, circular buffer, unified device manager)
- ✅ Garmin integration (manager, repository, free hold payload)
- ✅ Spotify integration (auth, API client, manager, notification listener)
- ✅ Dashboard
- ✅ Settings
- ✅ Navigation graph
- ✅ Room database with 17 entities and 17 DAOs
- ✅ Tail app IPC integration
- ✅ Data export/import
- ✅ Advice system (banner, dialog, section, viewmodel)

### Notable Additions Beyond Original Plan
- `AccRespirationEngine` — Accelerometer-based respiratory rate estimation
- `AccCalibrationEntity/Dao` — Accelerometer calibration storage
- `MorningReadiness*` — Full orthostatic readiness protocol (FSM, orchestrator, timer, stand detector, OHRR, 30:15 ratio)
- `AdvancedApnea*` — Extended apnea training with modality/length parameters
- `SongPickerComponents` — Spotify song selection UI
- `SessionExporter` — Export session data
- `HrSonificationEngine` — Heart rate sonification during sessions
- `UnifiedDeviceManager` — Abstraction over multiple BLE device types
- `DataExportImportRepository` — Full data backup/restore
- `AdviceRepository/ViewModel` — Context-aware advice system

## Current Focus / Open Questions

- No specific open questions

---

### 2026-04-09 11:39 (UTC-6)

**Fixed: Screen turning off during active sessions (persistent issue)**

- **Bug:** The phone screen would go black after ~10 minutes of inactivity during any active session (resonance breathing, apnea tables, apnea drills, meditation, etc.) even though `KeepScreenOn` was called in all session screens.
- **Root cause:** The `KeepScreenOn` composable in `ui/common/SessionGuards.kt` used `view.keepScreenOn = true` (View-level flag). The `onDispose` lambda **always** set `keepScreenOn = false` unconditionally — meaning every time the `DisposableEffect` re-ran (on `enabled` key change), the old effect's dispose cleared the flag. More critically, the View-level flag is less persistent than the Window-level flag and can be lost during navigation transitions or Activity lifecycle events.
- **Fix:** Replaced `view.keepScreenOn` with `Window.addFlags(FLAG_KEEP_SCREEN_ON)` / `Window.clearFlags(FLAG_KEEP_SCREEN_ON)` directly on the Activity window. The `onDispose` now only clears the flag when `enabled` was `true` (captured in the closure), preventing spurious flag clears. The Window-level flag is the most reliable mechanism — it persists across recompositions and navigation transitions.
- Files modified:
  1. `ui/common/SessionGuards.kt` — `KeepScreenOn()`: switched from `LocalView.current.keepScreenOn` to `(context as ComponentActivity).window.addFlags/clearFlags(FLAG_KEEP_SCREEN_ON)`; `onDispose` now guarded by `if (enabled)`

---

### 2026-04-07 14:35 (UTC-6)

**Fixed: "Skip Standing" button during morning readiness crashing with "Need at least 2 NN intervals"**

- **Bug:** Pressing "Skip Standing" during the STANDING phase of a morning readiness reading caused the entire session to fail with an error about needing at least 2 NN intervals. The standing data should have been ignored entirely, giving a result based only on the supine (laying) portion.
- **Root cause:** `MorningReadinessFsm.skipStanding()` cancelled the timer and jumped to QUESTIONNAIRE, but did NOT clear the `_standingBuffer`. Any RR intervals collected during the brief standing period remained in the buffer. The `MorningReadinessOrchestrator` checked `input.standingBuffer.isEmpty()` to determine if standing was skipped — but since the buffer had a few intervals (not empty), it tried to process them as a full standing session. With only 1-2 intervals, `TimeDomainHrvCalculator.calculate()` threw `IllegalArgumentException("Need at least 2 NN intervals")`.
- **Fix (primary):** `MorningReadinessFsm.skipStanding()` now clears `_standingBuffer`, resets `_peakStandHr` to 0, and nulls `_standTimestampMs` before entering QUESTIONNAIRE. This ensures the orchestrator sees an empty buffer = standing skipped.
- **Fix (safety net):** `MorningReadinessOrchestrator.compute()` now treats standing as skipped if the buffer has fewer than `MIN_STANDING_INTERVALS` (10) intervals, matching the artifact correction minimum. This prevents crashes even if some other code path sends a tiny standing buffer.
- Files modified:
  1. `domain/usecase/readiness/MorningReadinessFsm.kt` — `skipStanding()` now clears standing buffer, peak HR, and stand timestamp
  2. `domain/usecase/readiness/MorningReadinessOrchestrator.kt` — `standingSkipped` now checks `size < MIN_STANDING_INTERVALS` instead of `isEmpty()`; added companion object with `MIN_STANDING_INTERVALS = 10`

---

### 2026-04-06 17:55 (UTC-6)

**Fixed: Song picker showing selection checkmark that conflicted with completion checkmarks**

- **Bug:** In the "Choose a Song" popup (SongPickerDialog), selecting any song added a `✓` checkmark to the card. This was confusing because the dialog already uses checkmarks for two different completion statuses: bright `✓` for songs completed during any past hold, and grey `✓` for songs completed with current settings. The selection checkmark was visually identical and misleading.
- **Root cause:** The `SongCard` composable in `SongPickerComponents.kt` had a "selected indicator" block (lines 449-451) that rendered `Text("✓", ...)` when `isSelected` was true. This was redundant since selection was already visually indicated by border color/width and background color changes.
- **Fix:** Removed the selected indicator checkmark entirely. The card's existing visual differentiation (thicker border + different background color) is sufficient to show which song is selected.
- Files modified:
  1. `ui/apnea/SongPickerComponents.kt` — Removed the `if (isSelected && !isLoading)` checkmark block from `SongCard`

---

### 2026-04-06 07:33 (UTC-6)

**Fixed: Spotify song duration bug + missing completion checkmark + recalculate button**

- **Root cause (duration):** `SpotifyManager.startTracking()` correctly reset `startedAtMs` to `sessionStartMs` in `_sessionSongs`, but did NOT update `_currentSong.value`. When the song finished and `handleNewTrack()` closed the previous song, it read the stale `_currentSong.value` (with `startedAtMs` from when `selectSong()` pre-loaded the song) and overwrote the corrected entry in `_sessionSongs`. This caused the song's recorded play time to include the gap between song pre-load and hold start.
- **Root cause (checkmark):** The `loadPreviousSongs()` function skipped completion checks when `track.durationMs <= 0L` (Spotify API unavailable). Additionally, the SQL query only checked `(endedAtMs - startedAtMs) >= songDurationMs`, which could fail with stale `startedAtMs` values or when the API-provided duration was unavailable.
- **Fix (duration):** In `SpotifyManager.startTracking()`, now also updates `_currentSong.value` with the corrected `startedAtMs` so that `handleNewTrack()` uses the correct time when closing the song.
- **Fix (checkmark):** Updated SQL queries in `ApneaSongLogDao` to match by Spotify URI first (reliable across title differences between broadcast and API), falling back to title+artist. Added "next song exists" fallback for completion detection. Removed the `durationMs <= 0L` guard in `loadPreviousSongs()`. Added `normalizeText()` in repository for Unicode apostrophe normalization.
- **Fix (existing data):** Added "Recalculate" button in the song log section of the hold detail screen. Tapping it looks up each song's actual duration from the Spotify API and recalculates `startedAtMs = endedAtMs - realDurationMs`, fixing records with stale pre-load timestamps.
- Files modified:
  1. `data/spotify/SpotifyManager.kt` — `startTracking()`: update `_currentSong.value` alongside `_sessionSongs` (both immediate capture and polling branches)
  2. `data/db/dao/ApneaSongLogDao.kt` — `wasSongCompletedEver()` and `wasSongCompletedWithSettings()`: added `spotifyUri` param for URI-based matching; added "next song exists" fallback; added `updateStartedAt()`
  3. `ui/apnea/FreeHoldActiveScreen.kt` — `loadPreviousSongs()`: removed `durationMs <= 0L` guard; passes `spotifyUri` to completion checks
  4. `data/repository/ApneaRepository.kt` — added `normalizeText()` for Unicode apostrophe normalization; added `spotifyUri` param to completion methods; added `getSongLogEntitiesForRecord()` and `updateSongLogStartedAt()`
  5. `ui/apnea/ApneaRecordDetailViewModel.kt` — injected `SpotifyApiClient`; added `recalculateSongTimes()`
  6. `ui/apnea/ApneaRecordDetailScreen.kt` — added `onRecalculateSongs` callback; added "Recalculate" TextButton in song log header

---

### 2026-04-04 14:19 (UTC-6)

**Added: Clickable settings banner on FreeHoldActiveScreen with edit popup**

- The settings summary banner at the top of the free hold screen is now **clickable** (underlined text) when the hold is not active.
- Tapping it opens a `FreeHoldSettingsDialog` — an `AlertDialog` with filter chips for all 5 settings (lung volume, prep type, time of day, posture, audio).
- Changes are applied **immediately** to both the ViewModel's mutable properties (used when saving the hold record) and to SharedPreferences (so the main ApneaScreen stays in sync).
- The banner text updates in real-time as settings are changed in the dialog.
- During an active hold, the banner is not clickable (no underline, no onClick).
- Files created:
  1. `ui/apnea/FreeHoldSettingsDialog.kt` — New dialog composable with filter chips for all 5 settings
- Files modified:
  1. `ui/apnea/FreeHoldActiveScreen.kt` — ViewModel settings changed from `val` to `var` with `private set`; added `currentLungVolume`/`currentPrepType`/`currentTimeOfDay`/`currentPosture`/`currentAudio` to `FreeHoldActiveUiState`; added `updateLungVolume()`/`updatePrepType()`/`updateTimeOfDay()`/`updatePosture()`/`updateAudio()` methods; banner now reads from UI state and passes `onClick` when hold not active
  2. `ui/apnea/ApneaSettingsSummaryBanner.kt` — Added optional `onClick` parameter; text is underlined when clickable; display helpers renamed to `internal` with `Banner` suffix for reuse

---

### 2026-04-04 13:59 (UTC-6)

**Added: "Repeat This Hold" button on Apnea Record Detail Screen**

- New feature: At the bottom of the apnea hold detail screen (for free holds only, not table records), a "Repeat This Hold" button appears.
- Tapping it navigates **directly to the FreeHoldActiveScreen** (not the general ApneaScreen) with all settings from the record pre-filled via the navigation route parameters.
- Settings written to SharedPreferences: lung volume, prep type, posture, audio (so the ApneaScreen stays in sync on future visits).
- **Time of Day** uses `TimeOfDay.fromCurrentTime()` — always based on the current clock time, not the record's value.
- **Guided hyperventilation**: If the record had `guidedHyper=true`, the guided hyper checkbox is enabled and the phase durations (relaxed exhale, purge exhale, transition) are written to SharedPreferences so the FreeHoldActiveScreen picks them up.
- **Spotify song auto-load**: If the record used MUSIC audio and had a song log, the first song's Spotify URI/title/artist are stored as a "pending repeat song" in SharedPreferences. When the FreeHoldActiveViewModel initializes in MUSIC mode, it detects the pending song, auto-selects it via `selectSong()` (which pre-loads it into Spotify playback and pauses), then clears the pending keys. The user just needs to tap Start.
- Files modified:
  1. `ui/apnea/ApneaRecordDetailViewModel.kt` — Injected `@Named("apnea_prefs") SharedPreferences`; added `prepareRepeatHold()` and `repeatHoldRoute()` methods
  2. `ui/apnea/ApneaRecordDetailScreen.kt` — Added `onRepeatHold` callback to `RecordDetailContent`; added "Repeat This Hold" `Button` at bottom of detail content (free holds only); navigates directly to `FREE_HOLD_ACTIVE` route
  3. `ui/apnea/FreeHoldActiveScreen.kt` — Added `init` block to `FreeHoldActiveViewModel` that checks for pending repeat song in SharedPreferences and auto-selects it

---

### 2026-04-04 12:52 (UTC-6)

**Added: Personal Best Chart Screen (PB progress graph) + improvements**

- New feature: Tapping any setting label on the Personal Bests screen now navigates to a landscape-oriented line chart showing breath hold duration over time for that setting/combination.
- **Toggle**: A "PB only" switch filters to show only holds that were a new personal best at the time they happened, vs. all holds matching the selected settings.
- **Chart features**: Canvas-based line chart with pinch-to-zoom and pan, smart date labels on X-axis that adapt to zoom level (hours → days → months → years), Y-axis in seconds with nice tick spacing, dashed grid lines.
- **Clickable dots**: Tapping a dot on the chart navigates to that hold's detail screen (ApneaRecordDetailScreen).
- **Current Settings section**: A "Current Settings" section appears at the top of the Personal Bests screen (after Global, before Single Settings) showing the exact-settings entry for the user's current apnea settings for quick access.
- Route uses query parameters (not path segments) to handle empty setting values correctly.
- Files created:
  1. `ui/apnea/PbChartScreen.kt` — Landscape chart screen with zoom/pan Canvas, tap-to-navigate dots
  2. `ui/apnea/PbChartViewModel.kt` — ViewModel with `PbChartPoint` data class (includes recordId), PB-only computation
- Files modified:
  1. `data/db/dao/ApneaRecordDao.kt` — Added `getAllFreeHoldsFiltered()` query
  2. `data/repository/ApneaRepository.kt` — Added `getAllFreeHoldsForChart()` method; updated `entry()` helper to include filter values
  3. `domain/model/PersonalBestCategory.kt` — Added 5 filter fields to `PersonalBestEntry`
  4. `ui/apnea/PersonalBestsScreen.kt` — Made labels clickable; added "Current Settings" section at top
  5. `ui/apnea/PersonalBestsViewModel.kt` — Reads current settings from SharedPreferences, finds matching entry
  6. `ui/navigation/WagsNavGraph.kt` — Added `PB_CHART` route (query params), `pbChart()` helper, composable entry

---

### 2026-04-03 21:59 (UTC-6)

**Fixed: NowPlayingBanner showing during non-MUSIC free holds**

- Root cause: In `FreeHoldActiveScreen.kt`, the `uiState` combine block always passed `spotifyManager.currentSong` into `nowPlayingSong` regardless of the audio setting. If Spotify was playing in the background, the `NowPlayingBanner` would appear during any free hold — even when audio was set to SILENCE, MOVIE, etc.
- Fix: In the combine block (line 219), `nowPlayingSong` is now set to `if (isMusicMode) song else null`. This ensures the now-playing banner only appears when the audio setting is MUSIC.
- File modified: `ui/apnea/FreeHoldActiveScreen.kt` — single line change in the `combine` block

---

### 2026-04-03 09:41 (UTC-6)

**Apnea section minor improvements**

Three changes made:

1. **Guided hyperventilation back-button cancel** (`GuidedHyperCountdownDialog.kt`):
   - Added `onCancel: () -> Unit = {}` parameter
   - Changed `dismissOnBackPress = false` → `true`
   - `onDismissRequest` now calls `onCancel` instead of being a no-op

2. **Cancel guided countdown → go straight to hold** (`FreeHoldActiveScreen.kt`):
   - Added `onGuidedCountdownCancelled()` to `FreeHoldActiveViewModel`: marks countdown complete + immediately calls `startFreeHold()` so the hold begins right away with guided hyper specifics still recorded
   - Dialog call site now passes `onCancel = { viewModel.onGuidedCountdownCancelled() }`

3. **Edit guided hyperventilation specifics** (`FreeHoldActiveScreen.kt`):
   - `GuidedHyperSection` now shows a small edit pencil icon (always visible) next to the checkbox label
   - Tapping the icon opens a new `GuidedHyperEditSheet` (ModalBottomSheet) with three labeled rows for Relaxed Exhale / Purge Exhale / Transition seconds — same pattern as `EditRecordSheet` in the detail screen
   - Removed the old inline `CompactLabeledInput` fields from the section (replaced by the sheet)
   - A summary line (e.g. "Relaxed 30s · Purge 15s") is shown below the checkbox when enabled

4. **Resonance session sets apnea prep type** (`ResonanceSessionScreen.kt`):
   - When `BreathingSessionPhase.COMPLETE` is reached, writes `"RESONANCE"` to `"setting_prep_type"` in `"apnea_prefs"` SharedPreferences
   - `ApneaViewModel` reads this key on init, so the next time the Apnea screen is opened the prep type will already be set to Resonance

---

### 2026-04-03 09:17 (UTC-6)

**Fixed: Song chooser not visible on apnea session screens when Spotify disconnected**

- Root cause: The song picker button on all three apnea session screens (`FreeHoldActiveScreen`, `ApneaTableScreen`, `AdvancedApneaScreen`) was gated behind `spotifyConnected == true`. When Spotify auth tokens expire/get cleared, the entire song picker area disappears with no indication of why.
- Fix: Added a new `SpotifyConnectPrompt` composable to `SongPickerComponents.kt`. When MUSIC is selected but Spotify is not connected, this prompt is shown instead of the song picker button. It displays "Spotify not connected / Tap to connect in Settings" and navigates to the Settings screen on tap.
- Files modified:
  1. `ui/apnea/SongPickerComponents.kt` — Added `SpotifyConnectPrompt` composable
  2. `ui/apnea/FreeHoldActiveScreen.kt` — Added `WagsRoutes` import; replaced single `if (spotifyConnected)` with `if/else` showing prompt when disconnected
  3. `ui/apnea/ApneaTableScreen.kt` — Same pattern; added `WagsRoutes` import
  4. `ui/apnea/AdvancedApneaScreen.kt` — Same pattern; added `WagsRoutes` import

---

### 2026-04-01 19:39 (UTC-6)

**Fixed: Apnea Table "Start" button not working**

- Root cause: The "Start O2 Table" / "Start CO2 Table" buttons on the Table Training section were disabled (`enabled = personalBestMs > 0L`) because the Personal Best (PB) was never actually set — the text field auto-filled from `bestTimeForSettingsMs` but didn't call `setPersonalBest()`, leaving `personalBestMs` at 0.
- Fix applied in two places:
  1. `ApneaViewModel.kt`: Auto-set PB from best free hold time when it arrives from DB and no PB has been manually set yet.
  2. `ApneaScreen.kt` `TableTrainingConfigContent`: Auto-call `onSetPersonalBest` when `bestTimeForSettingsMs` auto-fills and `personalBestMs` is still 0. Also added a `LaunchedEffect` to keep the text field in sync when PB is set from elsewhere.
- The table flow: ApneaScreen → "Start O2/CO2 Table" navigates to ApneaTableScreen → "Start Session" button loads table and starts state machine countdown.

---

### 2026-04-01 20:25 (UTC-6)

**Added: "Movie" audio type for Apnea**

- Added `MOVIE` to the `AudioSetting` enum alongside `SILENCE` and `MUSIC`.
- `MOVIE` behaves identically to `SILENCE` (no Spotify integration) — it's just a distinct category for tracking personal bests and filtering history.
- No DB migration needed: the `audio` column in `apnea_records` is `TEXT` and stores enum names as strings. Adding a new enum value doesn't change the schema.
- Files modified:
  1. `domain/model/AudioSetting.kt` — Added `MOVIE` enum value with `displayName() = "Movie"`
  2. `ui/apnea/ApneaSettingsSummaryBanner.kt` — Added `"MOVIE" -> "Movie"` to `displayAudio()`
  3. `data/db/entity/ApneaRecordEntity.kt` — Updated comment to include `MOVIE`
- Automatically picked up everywhere else because:
  - All filter chips use `AudioSetting.entries.forEach` (ApneaScreen, ApneaHistoryScreen, AllApneaRecordsScreen, ApneaRecordDetailScreen)
  - Personal bests use `AudioSetting.entries.map { it.name }` (ApneaRepository)
  - Spotify logic only triggers on `== AudioSetting.MUSIC`, so `MOVIE` is safely ignored

---

### 2026-04-01 21:11 (UTC-6)

**Major Apnea Tables Overhaul**

Six changes to the Apnea Tables section:

1. **Removed warm-up/recovery phases** — Tables now go directly APNEA → VENTILATION → APNEA (no warm-up before first hold, no 30s recovery between rounds). State machine simplified: `ApneaStateMachine.kt` starts with `startApnea()` instead of `startVentilation()`, and `ApneaStateTransitionHandler.kt` transitions APNEA → VENTILATION → APNEA directly.

2. **Simplified to Hold + Breath only** — Labels changed from "Hold/Rest/Recovery" to just "Hold" and "Breath". UI in `ApneaTableScreen.kt` updated, help text updated.

3. **O2 table breath time fixed** — Changed from 120s (Easy/Medium) / 150s (Hard) to 60s for all difficulties in `ApneaTableGenerator.kt`.

4. **All table times editable** — Each step row in `ApneaTableScreen.kt` now has "edit" buttons for both Hold and Breath times when session is IDLE. Editing updates the table via `ApneaViewModel.updateTableStep()` which reloads the state machine.

5. **"First Contraction" button** — Large greyscale button appears during every APNEA phase. Disappears when tapped, shows confirmation with elapsed time. Reappears on next hold. Data saved per-round in `roundFirstContractions` map.

6. **Table completion saved to history** — `saveCompletedSession()` saves a SINGLE `ApneaRecordEntity` for the whole table (longest hold as duration) plus `ApneaSessionEntity` with per-round contraction data. Appears as one card in All Records.

Files modified:
- `domain/usecase/apnea/ApneaStateMachine.kt` — Removed recovery, start with hold
- `domain/usecase/apnea/ApneaStateTransitionHandler.kt` — Simplified transitions
- `domain/usecase/apnea/ApneaTableGenerator.kt` — O2 rest 120→60s
- `domain/model/TableConfig.kt` — Updated comment
- `ui/apnea/ApneaViewModel.kt` — New UI state fields, `logFirstContraction()`, `updateTableStep()`, save single record + session
- `ui/apnea/ApneaTableScreen.kt` — Full rewrite: editable times, first contraction button (greyscale), breath labels
- `ui/apnea/ContractionOverlay.kt` — Summary shows during VENTILATION (not just RECOVERY)
- `ui/apnea/ApneaHelpContent.kt` — Updated O2 help text (120-150s → 60s)

---

### 2026-04-01 21:28 (UTC-6)

**Apnea Tables: Unified Record + Detail Screen + Greyscale**

Follow-up fixes after user testing:

1. **Single unified record** — Changed `saveCompletedSession()` to save ONE `ApneaRecordEntity` per table session (not per-round). Duration = longest hold time. Links to `ApneaSessionEntity` via matching timestamp + tableType.

2. **Table session detail screen** — `ApneaRecordDetailViewModel` now injects `ApneaSessionRepository` and loads matching `ApneaSessionEntity` for table records. `RecordDetailContent` accepts `tableSession: ApneaSessionEntity?` and shows a "Table Session" card with size, difficulty, rounds, total duration, PB at session, longest hold, and per-round first contraction data parsed from `tableParamsJson`.

3. **All Records card** — `AllRecordsRow` now shows table type as primary text (e.g. "O₂ Table") with "Longest hold: Xm Xs" below for table records. Free holds still show duration as primary.

4. **First Contraction button greyscale** — Changed from orange (`0xFFFF6B35`) to grey (`0xFF555555`). Confirmation text changed from orange to light grey (`0xFFCCCCCC`).

5. **Type label improved** — Detail screen "Type" row now shows human-readable names (e.g. "O₂ Table" instead of "O2").

Files modified:
- `data/db/dao/ApneaSessionDao.kt` — Added `getById()` and `getByTimestampAndType()` queries
- `data/repository/ApneaSessionRepository.kt` — Added `getSessionById()` and `getSessionByTimestampAndType()`
- `ui/apnea/ApneaRecordDetailViewModel.kt` — Injects `ApneaSessionRepository`, loads matching session, added `tableSession` to UI state
- `ui/apnea/ApneaRecordDetailScreen.kt` — Added `ApneaSessionEntity` import, `tableSession` param to `RecordDetailContent`, table session detail card with per-round contractions, improved Type label
- `ui/apnea/AllApneaRecordsScreen.kt` — Table records show type as primary, "Longest hold" as secondary
- `ui/apnea/ApneaTableScreen.kt` — First Contraction button + confirmation changed to greyscale
- `ui/apnea/ApneaViewModel.kt` — `saveCompletedSession()` saves single record with longest hold as duration

---

### 2026-04-01 22:17 (UTC-6)

**Fix: HR/SpO₂ data not saved for table sessions**

Root cause: `startTableSession()` never started oximeter collection, and `saveCompletedSession()` hardcoded `minHrBpm = 0f`, `maxHrBpm = 0f`, `lowestSpO2 = null` with no telemetry rows.

Fix applied in `ui/apnea/ApneaViewModel.kt`:
- Added `tableSessionStartTime` field to track session start epoch
- `startTableSession()` now sets `oximeterIsPrimary`, clears `oximeterSamples`, starts `oximeterCollectionJob` (same pattern as `startFreeHold()`)
- `saveCompletedSession()` now snapshots RR buffer + oximeter data, computes HR/SpO₂ aggregates, saves `FreeHoldTelemetryEntity` rows linked to the record (same pattern as `saveFreeHoldRecord()`)
- `stopTableSession()` now cleans up oximeter collection job and samples
- `ApneaSessionEntity` also gets real `maxHrBpm` and `lowestSpO2` values

---

### 2026-04-02 07:43 (UTC-6)

**Meditation/NSDR: Tail Integration + Countdown Timer**

Two changes to the Meditation/NSDR section:

1. **Tail habit integration** — Added `Slot.MEDITATION` to `HabitIntegrationRepository.Slot` enum. Wired through `SettingsViewModel` (`meditationHabit` field in `HabitPartialState`, `SettingsUiState`, `buildInitialHabitState`, `uiState` combine), `SettingsScreen` (`TailAppIntegrationCard` signature + call site + slots list), and `MeditationViewModel` (injects `HabitIntegrationRepository`, calls `sendHabitIncrement(Slot.MEDITATION)` in `stopSession()`). Now appears in Settings → Tail App Integration as "Meditation / NSDR".

2. **Countdown timer** — Optional indication-only timer that plays a chime when time is up but does NOT stop the session. Added to `MeditationUiState`: `timerEnabled`, `timerHours`, `timerMinutes`, `timerSeconds`, `timerRemainingSeconds`, `timerChimeFired`. `MeditationViewModel` ticks the countdown each second in the session loop, fires `chime_end.mp3` via `MediaPlayer` when it reaches zero, marks `timerChimeFired = true`. UI: `TimerOptionRow` composable with a `Checkbox` + description text; when checked, shows three `OutlinedTextField` fields (hh / mm / ss) with numeric keyboard. During active session, a card shows the countdown (or 🔔 when chime has fired). Default: 0h 20m 0s.

Files modified:
- `data/ipc/HabitIntegrationRepository.kt` — Added `MEDITATION` slot
- `ui/settings/SettingsViewModel.kt` — Added `meditationHabit` throughout
- `ui/settings/SettingsScreen.kt` — Added `meditationHabit` param + slot to list
- `ui/meditation/MeditationViewModel.kt` — Injected `HabitIntegrationRepository`, added timer state + logic + chime playback
- `ui/meditation/MeditationScreen.kt` — Added `TimerOptionRow`, `TimerField` composables; updated `IdleContent` and `ActiveContent`

---

### 2026-04-02 07:50 (UTC-6)

**Fixed: PolarDeviceDisconnected crash in BLE streaming**

- **Crash**: `com.polar.sdk.api.errors.PolarDeviceDisconnected` on `DefaultDispatcher-worker-3` — app crashed when Polar device disconnected while ECG/ACC/PPI streams were active.
- **Root cause**: In `PolarBleManager.kt`, three streaming methods (`startEcgStream`, `startAccStream`, `startPpiStream`) launched coroutines that called `.collect {}` on Polar SDK RxJava→Flow bridges **without any try/catch**. When the device disconnected, the Polar SDK's internal `BlePMDClient.clearStreamObservers()` emitted a `PolarDeviceDisconnected` error through the RxJava chain, which propagated as an uncaught exception in the coroutine, crashing the app.
- **Why `startHrStream` didn't crash**: It already had proper try/catch + retry logic around its `.collect {}` call.
- **Fix**: Wrapped all three stream `.collect {}` calls in try/catch blocks that:
  1. Re-throw `CancellationException` (required for structured concurrency)
  2. Catch all other exceptions (including `PolarDeviceDisconnected`) and log them as warnings
- **File modified**: `data/ble/PolarBleManager.kt` — `startEcgStream()`, `startAccStream()`, `startPpiStream()`

---

### 2026-04-02 16:25 (UTC-6)

**Added: Rapid HR Change — 6th dashboard section**

New feature allowing users to time how quickly they can shift their heart rate between two thresholds (High→Low or Low→High).

**Architecture:**
- State machine: `IDLE` → `WAITING_FIRST` → `TRANSITIONING` → `COMPLETE`
- DB migration 24→25 adds two new tables: `rapid_hr_sessions` and `rapid_hr_telemetry`
- Per-second HR telemetry recorded during sessions for detail chart

**New files (12):**
- `data/db/entity/RapidHrSessionEntity.kt` — session record entity
- `data/db/entity/RapidHrTelemetryEntity.kt` — per-second HR telemetry entity
- `data/db/dao/RapidHrSessionDao.kt` — DAO with preset aggregation query
- `data/db/dao/RapidHrTelemetryDao.kt` — telemetry CRUD DAO
- `data/repository/RapidHrRepository.kt` — repository wrapping both DAOs
- `ui/rapidhr/RapidHrViewModel.kt` — state machine, HR polling, chime playback, PB detection
- `ui/rapidhr/RapidHrScreen.kt` — idle (direction toggle, threshold inputs, preset cards) + active (live HR, progress bar, timers) + complete (stats, PB banner)
- `ui/rapidhr/RapidHrHistoryViewModel.kt` — history data loading + chart computation
- `ui/rapidhr/RapidHrHistoryScreen.kt` — graphs tab (line chart, filter chips, session list) + calendar tab
- `ui/rapidhr/RapidHrDetailViewModel.kt` — loads session + telemetry
- `ui/rapidhr/RapidHrDetailScreen.kt` — full detail with HR chart + threshold lines

**Modified files (4):**
- `data/db/WagsDatabase.kt` — added entities, abstract DAOs, migration 24→25
- `di/DatabaseModule.kt` — added DAO providers + migration to list
- `ui/navigation/WagsNavGraph.kt` — added RAPID_HR, RAPID_HR_HISTORY, RAPID_HR_DETAIL routes
- `ui/dashboard/DashboardScreen.kt` — added 6th NavigationCard "Rapid HR Change"

**Key UX features:**
- Preset cards show previously-used settings with best time + attempt count (one-tap to reuse)
- HR progress bar shows real-time progress toward target threshold
- Chime plays at each threshold crossing
- Personal best detection per direction + settings combo
- History with graphs (transition time trend) + calendar view
- Detail screen shows HR chart with dashed threshold lines + phase background tint

---

### 2026-04-02 16:43 (UTC-6)

**Fixed: Rapid HR Change "Start" button not working**

- Root cause: `startSession()` in `RapidHrViewModel.kt` checked `_state.value.canStart`, but `canStart` depends on `hasHrMonitor` which is only set in the public `uiState` (via `combine` with `hrDataSource.isAnyHrDeviceConnected`). The internal `_state` never has `hasHrMonitor = true`, so `canStart` was always `false` and `startSession()` silently returned.
- Fix: Changed `startSession()` to check `hrDataSource.isAnyHrDeviceConnected.value` directly instead of relying on `_state.value.canStart`. Threshold validation is done inline.
- File modified: `ui/rapidhr/RapidHrViewModel.kt` (lines 193-199)

---

### 2026-04-02 18:17 (UTC-6)

**Added: Advice Notes Feature — tap any advice to write thoughts**

- Users can now tap any advice banner to open a "My Thoughts" popup dialog where they can write and save notes/thoughts about that specific piece of advice.
- Notes are persisted in the `advice` table via a new `notes` TEXT column (nullable, defaults to NULL).
- Notes are associated per-advice-item and remembered across sessions.
- Since advice and notes live in the Room database (`advice` table), they are automatically included in any backup/restore operations via `DataExportImportRepository` (which exports the entire `wags.db` file).
- DB migration v25 → v26 adds the `notes` column.
- Files modified:
  1. `data/db/entity/AdviceEntity.kt` — Added `notes: String?` field
  2. `data/db/dao/AdviceDao.kt` — Added `updateNotes()`, `getById()`, `getAll()` queries
  3. `data/db/WagsDatabase.kt` — Bumped version to 26, added `MIGRATION_25_26`
  4. `di/DatabaseModule.kt` — Registered `MIGRATION_25_26`
  5. `data/repository/AdviceRepository.kt` — Added `updateNotes()`, `getById()` methods
  6. `ui/common/AdviceViewModel.kt` — Added `saveNotes()` method
  7. `ui/common/AdviceBanner.kt` — Made banner clickable (tap opens note dialog, swipe still works)
  8. `ui/common/AdviceNoteDialog.kt` — **New file** — Popup dialog showing advice text + editable notes field

---

### 2026-04-02 20:12 (UTC-6)

**Removed H10 restriction from Morning Readiness**

- Previously, `MorningReadinessViewModel.startSession()` required a Polar H10 specifically (`connectedH10DeviceId()`). Now any connected HR device works (checks `hrDataSource.isAnyHrDeviceConnected`).
- ACC stream for stand detection is only started when an H10 is actually connected. Non-H10 devices skip ACC-based stand detection and rely on the FSM's fallback timestamp.
- The `onStandPromptReady` callback now conditionally arms the stand detector only when H10 is present.
- UI text updated: dialog says "connect a heart rate monitor" instead of "Polar H10", idle instructions say "Connect a heart rate monitor" instead of "Connect your Polar H10 heart rate monitor".
- H10 behavior is completely unchanged — when an H10 is connected, everything works exactly as before (ACC stream, stand detection, etc.).
- Files modified:
  1. `ui/morning/MorningReadinessViewModel.kt` — `startSession()` gate changed from H10-only to any-device; ACC stream conditional; `onStandPromptReady` conditional
  2. `ui/morning/MorningReadinessScreen.kt` — Dialog text and idle instructions updated

---

### 2026-04-09 15:32 (UTC-6)

**Implemented: Min Breath Drill — Full 2-screen flow (Setup → Active), using shared detail screen**

4 new files created:
1. `domain/usecase/apnea/MinBreathStateMachine.kt` (160 lines) — User-driven state machine with IDLE/HOLD/BREATHING/COMPLETE phases, 100ms tick loop, wall-clock timing
2. `ui/apnea/MinBreathViewModel.kt` (490 lines) — Shared ViewModel for Setup+Active screens, saves 4 entities (tableType="MIN_BREATH"), no audio/haptic cues except session complete
3. `ui/apnea/MinBreathScreen.kt` (~290 lines) — Setup screen with session duration stepper (60-600s, ±30s), settings banner, Spotify, past session history grouped by duration
4. `ui/apnea/MinBreathActiveScreen.kt` (351 lines) — Active screen with user-driven buttons: HOLD phase shows "First Contraction" + "Breath" side-by-side (or just "Breath" after contraction logged), BREATHING phase shows full-screen "HOLD" button

2 files modified:
5. `ui/navigation/WagsNavGraph.kt` — Added MIN_BREATH and MIN_BREATH_ACTIVE routes + composable blocks
6. `ui/apnea/ApneaScreen.kt` — Replaced inline InlineAdvancedSessionContent with navigation button to WagsRoutes.MIN_BREATH

Key design decisions:
- No custom detail screen — uses shared `ApneaRecordDetailScreen` (same pattern as Progressive O₂)
- No audio/haptic cues during session (user-driven, no timers to warn about) — only `announceSessionComplete()` when session ends
- State machine uses 100ms tick with wall-clock deltas for smooth count-up display
- `tableParamsJson` includes sessionDurationSec, totalHoldTimeMs, totalBreathTimeMs, holdPct, and holds array with contraction timestamps
- Past sessions grouped by session duration with best hold percentage per group

## Current State

The project is in an **advanced implementation stage**. All major architecture phases from the plan have been implemented. The apnea training section now has 5 drill types: Free Hold, O₂/CO₂ Tables, Advanced Tables, Progressive O₂, and Min Breath.

## Recently Active Areas

- **Min Breath Drill implementation (complete)** — 2-screen flow (Setup → Active) with shared detail screen. User-driven state machine (no countdown timers). Nav routes wired into `WagsNavGraph.kt` (2 routes: `MIN_BREATH`, `MIN_BREATH_ACTIVE`). `ApneaScreen.kt` Min Breath section replaced from inline content to a navigation card with "Open Min Breath" button.
- **Progressive O₂ Drill (complete)** — All screens + ViewModel + StateMachine created. Detail screen uses shared `ApneaRecordDetailScreen`.
- **HRV pipeline** — Multiple HRV use case files open (RrPreFilter, Phase1/2, TimeDomain, PchipResampler)
- **BLE layer** — BlePermissionManager, AccRespirationEngine open
