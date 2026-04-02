# WAGS — Progress

*Last updated: 2026-03-28*

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

## Architecture Decisions Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-03-09 | Custom Canvas for real-time charts | No commercial license for SciChart/LightningChart |
| 2026-03-09 | Lipponen & Tarvainen 2019 for artifact correction | Gold-standard clinical algorithm |
| 2026-03-09 | Custom 2-thread math dispatcher | Isolate FFT/HRV from IO and Main pools |
| 2026-03-09 | CircularBuffer with AtomicInteger | Prevent GC pressure during 130Hz ECG ingestion |
| 2026-03-09 | Vico for historical charts | Native Compose API, no View interop |
| 2026-03-28 | Memory bank initialized | Persistent context across sessions |
