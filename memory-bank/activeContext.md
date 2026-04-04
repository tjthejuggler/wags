# WAGS — Active Context

*Last updated: 2026-04-04 20:19 UTC*

## Current State

The project is in an **advanced implementation stage**. All major architecture phases from the plan have been implemented. The codebase has grown significantly beyond the original architecture plan with additional features.

## Recently Active Areas

Based on open tabs and visible files:
- **Meditation screen** (`ui/meditation/MeditationScreen.kt`) — actively being worked on
- **Garmin DataTransmitter** (`garmin/source/DataTransmitter.mc`) — visible but per rules, not to be modified unless instructed
- **HRV pipeline** — Multiple HRV use case files open (RrPreFilter, Phase1/2, TimeDomain, PchipResampler)
- **Domain models** — Several model files open (RrInterval, HrvMetrics, ReadinessScore, ApneaRecord, etc.)
- **BLE layer** — BlePermissionManager, AccRespirationEngine open
- **DI** — DispatcherModule open

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
