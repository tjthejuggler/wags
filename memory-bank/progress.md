# WAGS — Progress

*Last updated: 2026-04-02 16:26 UTC*

## Recent Changes (2026-04-02)
- ✅ Removed "New Session" button from `MorningReadinessResultScreen` (result screen after completing test)
- ✅ Added standing-completeness guard to `MorningReadinessDetailScreen`: if standing beats < 50% of total telemetry beats, all standing-related sections (Standing HRV, Orthostatic Response, OrthostasisStatsCard, stand marker on charts) are hidden as if standing never occurred

## What Works

### Core Infrastructure
- ✅ Gradle build configuration with all dependencies
- ✅ Hilt DI setup (5 modules: App, Database, BLE, Dispatcher, Garmin)
- ✅ Room database with 17 entities and 17 DAOs
- ✅ 10 repository classes
- ✅ Navigation graph with 25+ routes
- ✅ BLE foreground service with wake lock

### BLE / Sensor Layer
- ✅ Polar BLE SDK integration (H10 + Verity Sense)
- ✅ RxJava3 → Kotlin Flow bridge
- ✅ CircularBuffer for RR, ECG, PPI data
- ✅ BLE permission management
- ✅ Unified device manager abstraction
- ✅ Accelerometer-based respiration engine

### HRV Processing
- ✅ RR pre-filter
- ✅ Lipponen & Tarvainen 2019 artifact correction (3-phase)
- ✅ Time-domain HRV (RMSSD, lnRMSSD, SDNN, pNN50)
- ✅ PCHIP resampling for frequency domain
- ✅ FFT processing
- ✅ PSD band integration (VLF, LF, HF)
- ✅ Frequency-domain calculator

### Features
- ✅ Dashboard with overview
- ✅ HRV Readiness scoring (Z-score based, 14-day rolling)
- ✅ HRV Readiness history + detail views
- ✅ Morning Readiness (orthostatic protocol with FSM)
- ✅ Morning Readiness history + detail views
- ✅ Resonance Frequency Breathing (pacer + coherence)
- ✅ RF Assessment (discovery mode with multiple protocols)
- ✅ RF Assessment history
- ✅ Apnea free hold (with contraction tracking)
- ✅ Apnea O2/CO2 tables
- ✅ Advanced apnea (modality + length variants)
- ✅ Apnea history + record detail
- ✅ Apnea personal bests
- ✅ Apnea session analytics
- ✅ Meditation/NSDR sessions
- ✅ Meditation history + session detail
- ✅ Garmin watch integration
- ✅ Spotify integration (OAuth PKCE + playback)
- ✅ Settings screen
- ✅ Data export/import
- ✅ Tail app habit integration (IPC)
- ✅ Advice system
- ✅ HR sonification engine

### UI Components
- ✅ Custom Canvas ECG chart
- ✅ Custom Canvas tachogram
- ✅ Breathing pacer circle
- ✅ RR strip chart
- ✅ Live sensor top bar
- ✅ Confetti overlay (PB celebrations)
- ✅ Contraction overlay
- ✅ Markdown text renderer
- ✅ Info/help bubbles
- ✅ Grayscale emoji component

## What's Left / Known Issues

- No specific known issues documented at this time
- Memory bank just initialized — will be updated as work continues

### 2026-04-01 19:39 (UTC-6)
- ✅ Fixed: Apnea table "Start O2/CO2 Table" buttons were disabled because PB auto-filled text field but didn't call `setPersonalBest()`. Now auto-sets PB from best free hold time in both ViewModel and UI.

### 2026-04-01 20:25 (UTC-6)
- ✅ Added: "Movie" audio type for Apnea — new `MOVIE` value in `AudioSetting` enum. Appears in all filter chips, settings summaries, personal bests, and history screens. Behaves like Silence (no Spotify). No DB migration needed.

### 2026-04-01 21:11 (UTC-6)
- ✅ Major Apnea Tables overhaul:
  - Removed warm-up and recovery phases — tables now go Hold → Breath → Hold directly
  - Simplified labels to "Hold" and "Breath" (removed "Rest" and "Recovery")
  - Fixed O2 table breath time from 120s to 60s for all difficulties
  - Made all table step times (hold + breath) editable before session starts
  - Added large "First Contraction" button during every hold phase (disappears when tapped, reappears next hold)
  - Table completions save single unified ApneaRecordEntity + ApneaSessionEntity
  - First contraction data saved per round in session entity for detail screen

### 2026-04-01 21:28 (UTC-6)
- ✅ Apnea Tables: Unified record + detail screen + greyscale fixes:
  - Changed from per-round records to single unified record per table session (longest hold as duration)
  - Added table session detail card in record detail screen (size, difficulty, rounds, total duration, PB, per-round contractions)
  - All Records row shows table type as primary text with "Longest hold" subtitle for table records
  - First Contraction button changed from orange to greyscale (whole app is greyscale)
  - Detail screen Type label shows human-readable names (O₂ Table, CO₂ Table, etc.)
  - ApneaRecordDetailViewModel now loads matching ApneaSessionEntity via timestamp+type lookup

### 2026-04-01 22:17 (UTC-6)
- ✅ Fixed: HR/SpO₂ data not saved for table sessions — `startTableSession()` now starts oximeter collection, `saveCompletedSession()` snapshots RR + oximeter data, computes aggregates, saves telemetry rows. `stopTableSession()` cleans up oximeter collection.

### 2026-04-02 07:43 (UTC-6)
- ✅ Meditation/NSDR: Added Tail habit integration (`Slot.MEDITATION`) — wired through `HabitIntegrationRepository`, `SettingsViewModel`, `SettingsScreen` (TailIntegrationCard), and `MeditationViewModel` (`stopSession()` fires increment).
- ✅ Meditation/NSDR: Added optional countdown timer — checkbox + hh:mm:ss fields in IdleContent; ticks down during active session; plays `chime_end.mp3` when zero; shows countdown card (or 🔔) in ActiveContent. Session is NOT stopped by the timer.

### 2026-04-02 07:50 (UTC-6)
- ✅ Fixed: `PolarDeviceDisconnected` crash — `startEcgStream()`, `startAccStream()`, `startPpiStream()` in `PolarBleManager.kt` had no try/catch around `.collect {}`, so device disconnection errors propagated as uncaught coroutine exceptions. Added try/catch with `CancellationException` re-throw + warning log for all other exceptions. `startHrStream()` already had proper error handling.

## Architecture Decisions Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-03-09 | Custom Canvas for real-time charts | No commercial license for SciChart/LightningChart |
| 2026-03-09 | Lipponen & Tarvainen 2019 for artifact correction | Gold-standard clinical algorithm |
| 2026-03-09 | Custom 2-thread math dispatcher | Isolate FFT/HRV from IO and Main pools |
| 2026-03-09 | CircularBuffer with AtomicInteger | Prevent GC pressure during 130Hz ECG ingestion |
| 2026-03-09 | Vico for historical charts | Native Compose API, no View interop |
| 2026-03-28 | Memory bank initialized | Persistent context across sessions |
