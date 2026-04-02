# WAGS — Active Context

*Last updated: 2026-04-02 22:25 UTC*

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

- Memory bank just initialized — no specific active task beyond this setup

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
