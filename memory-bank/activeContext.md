# WAGS — Active Context

*Last updated: 2026-03-28*

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
