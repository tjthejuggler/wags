# WAGS — Progress

*Last updated: 2026-04-09 20:31 UTC-6*

## Recent Changes (2026-04-09 20:31)
- ✅ **Trophy display on main ApneaScreen** for Progressive O₂ and Min Breath sections. When expanded, these sections now show trophies + best hold time inline (matching the free hold pattern). Clicking trophies navigates to Personal Bests for that drill type (across all param values). Added `DrillContext.PROGRESSIVE_O2_ANY` / `MIN_BREATH_ANY` constants, drill-aware `computeBroadestCurrentCategoryForDrill()` and `getDrillBestAndTrophy()` in repository, new state fields in `ApneaUiState`, and reusable `DrillSectionContent` composable.
  - Modified: `DrillContext.kt`, `ApneaRepository.kt`, `ApneaViewModel.kt`, `ApneaScreen.kt`

## Recent Changes (2026-04-09 20:23)
- ✅ Implemented: **Universal Trophy System** — generalized the free-hold-only trophy/PB system to work with any drill type via `DrillContext` abstraction. New `drillParamValue` column on `apnea_records` (DB v26→v27). Generalized DAO query builders, repository methods, PersonalBestsScreen, PbChartScreen, and navigation routes. Wired Progressive O₂ (PB per breath period) and Min Breath (PB per session duration) with full celebration dialogs, confetti, sounds, and "🏆 Personal Bests" buttons on setup screens. Zero regression for free holds — old methods are thin wrappers. Plan doc at `plans/universal_trophy_system_plan.md`.
  - New files: `DrillContext.kt`
  - Modified: `ApneaRecordEntity.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`, `ApneaRecordDao.kt`, `ApneaRepository.kt`, `PersonalBestsViewModel.kt`, `PersonalBestsScreen.kt`, `PbChartViewModel.kt`, `WagsNavGraph.kt`, `ProgressiveO2ViewModel.kt`, `ProgressiveO2ActiveScreen.kt`, `ProgressiveO2Screen.kt`, `MinBreathViewModel.kt`, `MinBreathActiveScreen.kt`, `MinBreathScreen.kt`, `ApneaScreen.kt`

## Recent Changes (2026-04-09 15:32)
- ✅ Implemented: **Min Breath Drill** — full 2-screen flow (Setup → Active) using shared `ApneaRecordDetailScreen` for post-session details. User-driven state machine (IDLE/HOLD/BREATHING/COMPLETE) with 100ms tick loop and wall-clock timing. No audio/haptic cues during session (user controls all transitions), only `announceSessionComplete()` at end. Setup screen has session duration stepper (60-600s, ±30s), settings banner, Spotify song picker, past session history grouped by duration with best hold percentage. Active screen has user-driven buttons: HOLD shows "First Contraction" + "Breath" side-by-side, BREATHING shows full-screen "HOLD" button. `tableParamsJson` stores sessionDurationSec, totalHoldTimeMs, totalBreathTimeMs, holdPct, and holds array with contraction timestamps.
  - New files: `MinBreathStateMachine.kt` (160 lines), `MinBreathViewModel.kt` (490 lines), `MinBreathScreen.kt` (~290 lines), `MinBreathActiveScreen.kt` (351 lines)
  - Modified: `WagsNavGraph.kt` (added MIN_BREATH + MIN_BREATH_ACTIVE routes), `ApneaScreen.kt` (replaced inline content with nav button)

## Recent Changes (2026-04-09 15:00)
- ✅ Fixed: Progressive O₂ "View Details" now navigates to existing `ApneaRecordDetailScreen` instead of custom `ProgressiveO2DetailScreen`. Changed `completedSessionId` → `completedRecordId`, `saveSession()` returns `recordId`. Removed `PROGRESSIVE_O2_DETAIL` route from `WagsNavGraph.kt`.
- ✅ Replaced: Past sessions list on Progressive O₂ setup screen with breath period history. New `BreathPeriodHistory` data class (breathPeriodSec, maxHoldReachedSec, sessionCount). Clickable rows set current breath period. Currently selected period highlighted.
- ✅ Fixed: Breath period stepper now perfectly centered — Column with `CenterHorizontally`, Row with `Arrangement.Center`, fixed `widthIn(min=80.dp)` on value text, 24dp spacers between buttons.

## Recent Changes (2026-04-09 evening)
- ✅ Fixed: Progressive O₂ past session cards navigating to wrong screen — changed `WagsRoutes.sessionAnalytics()` → `WagsRoutes.progressiveO2Detail()` in `ProgressiveO2Screen.kt`
- ✅ Fixed: Progressive O₂ active screen using colored text (red HOLD, teal BREATHE) — replaced all `Color(...)` with greyscale theme colors (`TextPrimary`, `TextSecondary`) in `ProgressiveO2ActiveScreen.kt`. Removed `HoldColor`/`BreatheColor` constants.
- ✅ Fixed: Progressive O₂ detail screen HR chart line color from red → greyscale (`#D0D0D0`), HR stat labels from red → `TextPrimary` in `ProgressiveO2DetailScreen.kt`

- ✅ Created: Apnea Drill Screen Guide (`plans/apnea_drill_screen_guide.md`, 830 lines) — comprehensive recipe for creating new dedicated apnea drill screens. Covers all 12 sections: overview, files to create/modify, state machine pattern, ViewModel pattern, setup/active/detail screen patterns, navigation wiring, data storage, reusable components, and step-by-step checklist. Based on Progressive O₂ implementation with actual file path references throughout.

- ✅ Added: Clickable settings banner + song picker to ProgressiveO2Screen — `ApneaSettingsSummaryBanner` at top of setup screen opens `FreeHoldSettingsDialog` popup. Song picker with `SongPickerButton`, `SongPickerDialog`, `SelectedSongBanner`, `SpotifyConnectPrompt` when MUSIC mode. `ProgressiveO2ViewModel` now injects Spotify dependencies and has settings setters + song picker methods matching `AdvancedApneaViewModel` pattern. Settings dialog already monochrome (no changes needed).

## Recent Changes (2026-04-09 afternoon)
- ✅ Implemented: Progressive O₂ Drill — full 3-screen flow (Setup → Active → Detail) replacing the old inline accordion card. Endless mode starting at 15s hold, +15s each round, user-configurable breathing period. Post-session detail screen with HR/SpO₂ charts, total hold time, round-by-round breakdown. Sessions saved to existing DB schema (no migration). Integrated with ApneaHistoryScreen via existing tableType filtering.
- ✅ Added: Clickable settings banner + monochrome popup to Progressive O₂ setup screen (ApneaSettingsSummaryBanner + FreeHoldSettingsDialog)
- ✅ Added: Spotify song picker to Progressive O₂ setup screen (Choose a Song button, SongPickerDialog, SpotifyConnectPrompt)
- ✅ Created: Comprehensive guide document for creating new apnea drill screens (`plans/apnea_drill_screen_guide.md`) — 12 sections, ~830 lines, step-by-step checklist

## Recent Changes (2026-04-09)
- ✅ Wired: Progressive O₂ navigation routes into `WagsNavGraph.kt` — added 3 route constants (`PROGRESSIVE_O2`, `PROGRESSIVE_O2_ACTIVE`, `PROGRESSIVE_O2_DETAIL`) + `progressiveO2Detail(sessionId)` helper + 3 `composable()` blocks. Modified `ApneaScreen.kt` to replace inline `InlineAdvancedSessionContent` with navigation card (description text + "Open Progressive O₂" button). Updated `ProgressiveO2Screen.kt` to use `WagsRoutes.PROGRESSIVE_O2_ACTIVE` instead of hardcoded string. Updated `ProgressiveO2ActiveScreen.kt` to use `WagsRoutes.progressiveO2Detail(sessionId)` with `popUpTo(inclusive=true)` for proper back-stack behavior.
- ✅ Created: `ProgressiveO2DetailViewModel.kt` (~147 lines) — HiltViewModel for post-session detail screen. Loads `ApneaSessionEntity` by `sessionId` from `SavedStateHandle`, parses `tableParamsJson` to extract `breathPeriodSec` and per-round `RoundDisplayData` (roundNumber, targetSec, actualSec, completed). Computes HR stats (min/max/avg from telemetry, filtered 20–250 bpm) and SpO₂ stats (lowest, filtered > 0). UI state includes session summary metrics: totalHoldTimeSec, maxHoldReachedSec, roundsCompleted, totalRoundsAttempted, sessionDurationSec.
- ✅ Created: `ProgressiveO2DetailScreen.kt` (~317 lines) — Post-session analytics screen. Scaffold with TopAppBar ("Progressive O₂ Detail", back arrow). Scrollable body with 4 sections: (1) Session Summary Card (date, breath period, rounds, max hold, total hold time, session duration, device, HR min/avg/max, lowest SpO₂), (2) HR Chart (Canvas line chart, red `#FF6B6B`, 200dp tall, Y-axis labels, X-axis minute markers), (3) SpO₂ Chart (grey `#B0B0B0`, Y-axis clamped 80–100%), (4) Rounds Breakdown (table with alternating row colors, ✓/✗ completion indicators). Chart follows `ApneaRecordDetailScreen.LineChart` pattern exactly. Reusable `ChartCard` composable wraps both HR and SpO₂ charts.
- ✅ Created: `ProgressiveO2ActiveScreen.kt` (~310 lines) — Active drill screen for Progressive O₂. Auto-starts session via `LaunchedEffect(Unit)`. Phase-dependent layout: HOLD (red `#FF6B6B`) shows target hold + giant countdown + round indicator; BREATHE (teal `#4ECDC4`) shows next hold duration + countdown. Shared elements: total hold time, live HR/SpO₂ vitals, scrollable completed rounds list (LazyColumn, max 200dp, most recent first, ✓/✗ icons), red-bordered Stop Drill button. COMPLETE phase shows summary card (rounds completed, max hold, total hold, session time, SpO₂, HR) + completed rounds list + View Details button (navigates to `"progressive_o2_detail/{sessionId}"`) + Done button. Uses `SessionBackHandler` + `KeepScreenOn` from `SessionGuards.kt`. Uses `hiltViewModel()` for own ViewModel instance (reads breath period from SharedPreferences).
- ✅ Created: `ProgressiveO2Screen.kt` (~300 lines) — Setup/landing screen for Progressive O₂ drill. Scaffold with TopAppBar (title "Progressive O₂", back arrow, LiveSensorActions). Body: explanation card (SurfaceVariant tint), breath period stepper (±5s, 15–180s range, FilledTonalButtons), Start button (navigates to `"progressive_o2_active"`), past sessions section grouped by breath period with count badge. Each session card shows date, breath period, rounds, max hold, total hold, SpO₂ min. Cards clickable → sessionAnalytics detail.
- ✅ Created: `ProgressiveO2StateMachine.kt` (~140 lines) — Endless drill state machine with IDLE→HOLD→BREATHING→COMPLETE cycle. Hold durations = round × 15s. Tracks per-round results (target, actual, completed), cumulative hold time. `@Singleton` with `@Inject constructor()` matching `AdvancedApneaStateMachine` pattern. Timer ticks every 1000ms.
- ✅ Created: `ProgressiveO2ViewModel.kt` (~290 lines) — HiltViewModel driving all 3 Progressive O₂ screens. Handles: session start/stop, telemetry collection (HR + SpO₂ every 1s), session saving (ApneaSessionEntity + ApneaRecordEntity + FreeHoldTelemetryEntity + TelemetryEntity), past session loading/parsing from `tableParamsJson`, breath period persistence to SharedPreferences, audio/haptic cues via `ApneaAudioHapticEngine`, live HR/SpO₂ from `HrDataSource`. No Spotify integration.
- ✅ Added: Vibration haptics for apnea table sessions — `ApneaAudioHapticEngine` now has `vibrateBreathingCountdownTick()` (single 80ms pulse) replacing the old `vibrateFinalCountdown()`. When a hold ends (APNEA→VENTILATION), a single 500ms `vibrateHoldEnd()` fires. During the last 10 seconds of each breathing (VENTILATION) phase, a quick 80ms tick fires every second to warn the user the next hold is approaching. `onWarning` in `ApneaViewModel` reads phase directly from `stateMachine.state.value` (StateFlow, always current) to avoid race conditions.
- 📋 Designed: Progressive O₂ Drill architecture plan (`plans/progressive_o2_plan.md`). Revamping from inline accordion to dedicated 3-screen flow (Setup → Active → Detail). Key decisions: new `ProgressiveO2StateMachine` (not reusing AdvancedApneaStateMachine), endless drill starting at 15s +15s/round, user-configurable breath period, no DB migration needed (reuses existing tables), 7 new files + 6 modified files. Ready for implementation.

## Recent Changes (2026-04-07)
- ✅ Fixed: "Skip Standing" button during morning readiness crashing with "Need at least 2 NN intervals" — `MorningReadinessFsm.skipStanding()` now clears the standing buffer, peak HR, and stand timestamp when skipping during STANDING phase. `MorningReadinessOrchestrator` now treats standing as skipped if buffer has < 10 intervals (safety net).

## Recent Changes (2026-04-06)
- ✅ Fixed: Song picker selection checkmark conflicting with completion checkmarks — removed the redundant `✓` "selected indicator" from `SongCard` in `SongPickerComponents.kt`. Selection is already shown via border/background color changes. The completion checkmarks (bright = completed ever, grey = completed with current settings) remain unchanged.
- ✅ Fixed: Spotify song duration showing inflated time in hold detail song card — `SpotifyManager.startTracking()` now updates `_currentSong.value` alongside `_sessionSongs` so that `handleNewTrack()` uses the correct `startedAtMs` (hold start time) instead of the stale pre-load time from `selectSong()`.
- ✅ Fixed: Missing completion checkmark in song picker — SQL queries now match by Spotify URI first (reliable across title differences between broadcast and API), falling back to title+artist. Added "next song exists" fallback for completion detection. Removed `durationMs <= 0L` guard. Added `normalizeText()` for Unicode apostrophe normalization.
- ✅ Added: "Recalculate" button in hold detail song log section — looks up actual song durations from Spotify API and fixes `startedAtMs` for existing records with stale pre-load timestamps.

## Recent Changes (2026-04-04)
- ✅ Added: Clickable settings banner on FreeHoldActiveScreen — tapping the settings summary text at the top of the free hold screen opens a popup dialog with filter chips for all 5 settings (lung volume, prep type, time of day, posture, audio). Changes are applied immediately and persisted to SharedPreferences so the main ApneaScreen stays in sync. Only clickable when hold is not active.
- ✅ Added: "Repeat This Hold" button on apnea record detail screen — navigates directly to the FreeHoldActiveScreen (not the general ApneaScreen) with all settings pre-filled from the record. Time of Day uses current clock time. Guided hyper settings (checkbox + phase durations) are also restored when the record had guided hyperventilation. If the record used MUSIC audio with a song, the song is auto-loaded into Spotify so the user just needs to tap Start.
- ✅ Added: Personal Best Chart Screen — tapping any setting label on the Personal Bests screen opens a landscape line chart of breath hold durations over time. Supports pinch-to-zoom, pan, smart date labels, and a "PB only" toggle to filter to holds that were new personal bests at the time. Works for all settings, single settings, and every combination of 2/3/4/5 settings.
- ✅ Fixed: NowPlayingBanner showing during non-MUSIC free holds — `nowPlayingSong` in the combine block now gated behind `isMusicMode` so the music card only appears when audio setting is MUSIC.

## Recent Changes (2026-04-03)
- ✅ Apnea section improvements:
  - Guided hyperventilation countdown can now be cancelled with the system back button — cancelling skips the countdown and goes straight into the hold (guided hyper specifics still recorded)
  - Added edit pencil icon next to "Guided Hyperventilation" checkbox that opens a `GuidedHyperEditSheet` (ModalBottomSheet) to configure Relaxed Exhale / Purge Exhale / Transition durations
  - After a resonance breathing session completes, the apnea prep type is automatically set to `RESONANCE` in SharedPreferences so the next free hold is tagged correctly
- ✅ Fixed: Song chooser not visible on apnea session screens when Spotify disconnected — added `SpotifyConnectPrompt` composable shown in place of song picker when MUSIC is selected but Spotify auth tokens are missing. Tapping navigates to Settings. Applied to `FreeHoldActiveScreen`, `ApneaTableScreen`, and `AdvancedApneaScreen`.

## Recent Changes (2026-04-02)
- ✅ Removed "New Session" button from `MorningReadinessResultScreen` (result screen after completing test)
- ✅ Added standing-completeness guard to `MorningReadinessDetailScreen`: if standing beats < 50% of total telemetry beats, all standing-related sections (Standing HRV, Orthostatic Response, OrthostasisStatsCard, stand marker on charts) are hidden as if standing never occurred
- ✅ HRV Readiness: added `PREPARING` state with 20-second countdown before recording begins
- ✅ HRV Readiness: added `HrvDuration` enum (SHORT=2min, MEDIUM=3min, LONG=5min) with toggle buttons on idle screen
- ✅ HRV Readiness: removed "New Session" button from result screen (CompleteContent)

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

### 2026-04-02 16:26 (UTC-6)
- ✅ Added: **Rapid HR Change** — 6th dashboard section. Full feature implementation:
  - DB migration 24→25: two new tables (`rapid_hr_sessions`, `rapid_hr_telemetry`)
  - 2 entities, 2 DAOs, 1 repository
  - `RapidHrViewModel` — state machine (IDLE→WAITING_FIRST→TRANSITIONING→COMPLETE), 500ms HR polling, chime on threshold crossings, PB detection, per-second telemetry recording
  - `RapidHrScreen` — idle (direction toggle, threshold inputs, preset cards with best times), active (live HR circle, progress bar, timers), complete (stats, PB banner, action buttons)
  - `RapidHrHistoryScreen` + `RapidHrHistoryViewModel` — graphs tab (transition time line chart, filter chips, session list) + calendar tab
  - `RapidHrDetailScreen` + `RapidHrDetailViewModel` — full session detail with HR chart + dashed threshold lines
  - Navigation: 3 new routes (RAPID_HR, RAPID_HR_HISTORY, RAPID_HR_DETAIL)
  - Dashboard: 6th NavigationCard added
  - Build: ✅ Installed on SM-S918U1 (Android 16)

## Architecture Decisions Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-03-09 | Custom Canvas for real-time charts | No commercial license for SciChart/LightningChart |
| 2026-03-09 | Lipponen & Tarvainen 2019 for artifact correction | Gold-standard clinical algorithm |
| 2026-03-09 | Custom 2-thread math dispatcher | Isolate FFT/HRV from IO and Main pools |
| 2026-03-09 | CircularBuffer with AtomicInteger | Prevent GC pressure during 130Hz ECG ingestion |
| 2026-03-09 | Vico for historical charts | Native Compose API, no View interop |
| 2026-03-28 | Memory bank initialized | Persistent context across sessions |

### 2026-04-02 16:43 (UTC-6)
- ✅ Fixed: Rapid HR Change "Start" button not working — `startSession()` checked `_state.value.canStart` but `hasHrMonitor` was only set in the combined `uiState`, never in `_state`. Fixed by checking `hrDataSource.isAnyHrDeviceConnected.value` directly.

### 2026-04-02 18:17 (UTC-6)
- ✅ Added: Advice Notes feature — tap any advice banner to open a "My Thoughts" popup dialog. Notes are saved per-advice-item in the `advice` table (`notes` column). DB version bumped to 26 with migration. Notes are included in backup/restore since they're in the Room DB. New file: `ui/common/AdviceNoteDialog.kt`. Modified: AdviceEntity, AdviceDao, WagsDatabase, DatabaseModule, AdviceRepository, AdviceViewModel, AdviceBanner.

### 2026-04-02 20:12 (UTC-6)
- ✅ Removed H10 restriction from Morning Readiness: any connected HR device (Polar H10, Verity Sense, oximeter, generic HR strap) can now start the test. ACC-based stand detection only activates when H10 is connected; other devices use FSM fallback timestamp. H10 behavior unchanged. Modified: `MorningReadinessViewModel.kt`, `MorningReadinessScreen.kt`.
