# WAGS — Progress

## Recent Changes (2026-04-18 09:31 UTC-4)
- **Trophy Chart**: New `TrophyChartViewModel.kt` + `TrophyChartScreen.kt` — full-screen landscape bar chart showing trophies per day, with Total/Max toggle and drill-include settings popup (Progressive O₂, Min Breath). Route `trophy_chart` added to `WagsNavGraph`. 📊 button added to `PersonalBestsScreen` top bar.
- **Forecast Auto-Update Fix**: Added `_forecastRefreshTrigger: MutableStateFlow<Int>` to `ApneaViewModel`; included in forecast `combine`; bumped after `saveFreeHoldRecord` completes. Forecast now auto-updates after breaking a record.
- **Total Trophies in Stats**: Added `totalTrophiesWon` to `ApneaHistoryUiState` + `ApneaHistoryViewModel` (summed across all drill types in `init`). Displayed at top of `ApneaStatsContent` in `ApneaHistoryScreen`.
- Build: SUCCESS, installed on 2 devices.


*Last updated: 2026-04-18 08:24 UTC-4*

## Recent Changes (2026-04-18 08:53)
- ✅ **Fix Spotify song picker UX — three issues fixed:**
  - **App loses focus**: `ensureSpotifyActive()` now brings our app back to foreground after launching Spotify (`FLAG_ACTIVITY_REORDER_TO_FRONT`)
  - **Song doesn't load on first click**: New `preloadTrack()` method detects fresh Spotify launch, sends media key "play" to wake player (registers Web API device), then retries `startPlayback()` up to 6 times with 1.5s delays
  - **7-8s delay opening picker**: Added `songPickerCache` (in-memory `StateFlow`) on `SpotifyManager` singleton. First open loads from API; subsequent opens show cached data instantly. Cache invalidated in `stopTracking()` when new songs may have been played.
  - Updated all 5 ViewModels' `selectSong()` to use `preloadTrack()` and `loadPreviousSongs()` to use cache
  - Modified: `SpotifyManager.kt`, `FreeHoldActiveScreen.kt`, `ApneaViewModel.kt`, `AdvancedApneaViewModel.kt`, `MinBreathViewModel.kt`, `ProgressiveO2ViewModel.kt`
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-17 09:19)
- ✅ **Record-breaking forecast feature (Tier C) for free holds:**
  - Log-linear OLS regression model predicting P(next hold > PB) for all 32 sub-combinations of 5 settings
  - 5 domain files in `domain/usecase/apnea/forecast/`: RecordForecast, NormalCdf, OlsRegression, FreeHoldFeatureExtractor, RecordForecastCalculator
  - Empirical-Bayes shrinkage for exact-cell predictions; 100% when no prior record exists
  - Forecast calibration DB table (forecast_calibration) for tracking prediction accuracy over time
  - DB migration v34→v35
  - One-line "Chance to beat PB: X%" in Free Hold card, tappable to open full 32-row popup
  - Popup sorted by probability descending with confidence pills (🟢🟡🔴)
  - Minimum 5 free holds to produce forecast; 150ms debounce on settings changes
  - Plan doc: `plans/record_forecast_plan.md`
  - Build: ✅ Successful

## Recent Changes (2026-04-15 14:27)
- ✅ **Live HR/SpO₂ feed on ALL screens + click-to-navigate-to-settings:**
  - Created `LiveSensorViewModel` (injects `HrDataSource`) so any screen can show live sensor data
  - Added `onClick` parameter to `LiveSensorActions` — clicking HR/SpO₂ navigates to Settings
  - Created `LiveSensorActionsNav` (NavController-based) and `LiveSensorActionsCallback` (callback-based) composables
  - Updated all 10 existing `LiveSensorActions` calls to include onClick navigation
  - Added `LiveSensorActionsNav` to 15+ screens with NavController
  - Added `LiveSensorActionsCallback` to 10+ callback-based screens (with `onNavigateToSettings` param)
  - Replaced DashboardScreen inline HR code with `LiveSensorActionsNav`
  - Added TopAppBar to `AllApneaRecordsScreen` (was missing)
  - Updated `WagsNavGraph` to pass `onNavigateToSettings` to all callback-based screens
  - Modified: `LiveSensorTopBar.kt`, `DashboardScreen.kt`, `WagsNavGraph.kt`, 25+ screen files
  - Build: ✅ Successful, installed on SM-S918U1

- ✅ **Countdown timer to next PB record + store newRecordIndication in DB:**
  - When "New Record Indication" is enabled during a free hold, a countdown is displayed below the trophy emojis showing time remaining to the next PB milestone (e.g. "🏆🏆 in 1m 23s")
  - Added `NextPbTarget` data class and `PbThresholds.nextPbTarget()` method to compute the closest unbroken category threshold
  - Added `newRecordIndication: Boolean` column to `apnea_records` table (DB migration v33→v34, default `0`)
  - Free hold save now captures `audioHapticEngine.pbIndicationEnabled` at save time
  - Apnea record detail screen shows "Record Indication: On" when the feature was enabled during that hold
  - Modified: `PersonalBestCategory.kt`, `FreeHoldActiveScreen.kt`, `ApneaRecordEntity.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`, `ApneaRecordDetailScreen.kt`
  - Build: ✅ Successful, installed on SM-S918U1

*Last updated: 2026-04-14 20:02 UTC-4*

## Recent Changes (2026-04-14 20:02)
- ✅ **Fix black flash during swipe transitions on all session detail screens:**
  - **Root cause**: `navigateToIndex()` in all 6 detail ViewModels set `isLoading = true` immediately, causing the pager page to render a loading spinner on a dark background (black flash) mid-swipe
  - **Fix**: Removed `isLoading = true` (and the corresponding `isLoading = false`) from `navigateToIndex()` in all 6 ViewModels. Previous content stays visible while SQLite loads the next record, then swaps in seamlessly.
  - Modified: `ApneaRecordDetailViewModel`, `MeditationSessionDetailViewModel`, `ResonanceSessionDetailViewModel`, `HrvReadinessDetailViewModel`, `RapidHrDetailViewModel`, `MorningReadinessDetailViewModel`
  - Build: ✅ Successful, installed on SM-S918U1

*Last updated: 2026-04-14 23:44 UTC-4*

## Recent Changes (2026-04-14 23:44)
- ✅ **Fix swipe direction + delete behavior on all session detail screens:**
  - **Swipe direction**: Changed to oldest-first ordering so swipe-right = newer, swipe-left = older (matches natural timeline expectation)
  - **Delete behavior**: Deleting a session now stays on the detail screen and shows the adjacent session. Only pops back to history when the last session is deleted.
  - Added `LaunchedEffect(state.currentIndex)` to all 6 screens to sync ViewModel→pager after delete
  - Modified all 6 ViewModels and all 6 screens
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-14 23:33)
- ✅ **Fix app back button bug on meditation detail screen + swipe navigation on ALL session detail screens:**
  - **Back button fix**: `MeditationHistoryScreen` now calls `viewModel.clearSelection()` after navigating to detail, preventing `LaunchedEffect(selectedDaySessions)` from re-firing on return
  - **Swipe navigation**: Added `HorizontalPager` to all 6 session detail screens. Swiping left/right navigates through session history (newest-first). Top bar shows "X / Y" counter when multiple sessions exist.
  - **DAOs**: Added `getAll()` to `DailyReadingDao`, `RapidHrSessionDao`, `ResonanceSessionDao`, `MorningReadinessDao`
  - **Repositories**: Added `getAll()` to `ReadinessRepository`, `RapidHrRepository` (`getAllSessions()`), `ResonanceSessionRepository`, `MorningReadinessRepository`
  - **ViewModels updated**: `MeditationSessionDetailViewModel`, `HrvReadinessDetailViewModel`, `ResonanceSessionDetailViewModel`, `RapidHrDetailViewModel`, `MorningReadinessDetailViewModel`, `ApneaRecordDetailViewModel`
  - **Screens updated**: `MeditationHistoryScreen` (back fix), `MeditationSessionDetailScreen`, `HrvReadinessDetailScreen`, `ResonanceSessionDetailScreen`, `RapidHrDetailScreen`, `MorningReadinessDetailScreen`, `ApneaRecordDetailScreen`
  - Key notes: `MorningReadinessEntity` PK is `id` (not `readingId`); `RapidHrSessionEntity` PK is `id`; `ApneaRepository.getAllRecordsOnce()` returns ASC so reversed for newest-first
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-14 17:37)
- ✅ **Real-time personal best indication during apnea free holds:**
  - New "New Record Indication" feature: as a free hold progresses, sound and vibration indicate when PB thresholds are broken
  - Uses the same 6-level trophy system (EXACT → GLOBAL) and same sound files (apnea_pb1–pb6.mp3) as the end-of-hold celebration
  - Settings: master toggle "New Record Indication" + sub-toggles "Sound" and "Vibration" (only visible when master is on)
  - All settings persisted in SharedPreferences via `ApneaAudioHapticEngine`
  - `PbThresholds` data class pre-computes all PB thresholds at hold-start (one DB query), then `broadestBroken()` does pure in-memory checks every second
  - Vibration duration scales with trophy count: 1 trophy = 100ms, 6 trophies = 600ms
  - Trophy emojis displayed below timer during hold when PB is broken
  - Only affects free holds during active hold — no changes to other drill types or post-hold PB celebration
  - Modified: `PersonalBestCategory.kt`, `ApneaRepository.kt`, `ApneaAudioHapticEngine.kt`, `FreeHoldActiveScreen.kt`
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-14 14:35)
- ✅ **Fix morning readiness / HRV readiness allowing sessions when connected but no HR data streaming:**
  - **Bug**: Both Morning Readiness and HRV Readiness sessions could start when a BLE device was connected but not streaming HR data (e.g. dry strap, sensor glitch)
  - **Root cause**: Session guards only checked `isAnyHrDeviceConnected` (BLE connection state), not `liveHr` (actual data flowing). ReadinessViewModel had NO device check at all.
  - **Fix**: Added `hrDataSource.liveHr.value == null` guard in both ViewModels' `startSession()` methods
  - Replaced `noHrmDialogVisible: Boolean` with `hrDialogMessage: String?` in `MorningReadinessUiState` — handles both "no device" and "no data" cases with distinct messages
  - Added `hrDialogMessage: String?` to `ReadinessUiState` + both connection and data-streaming guards (previously had NO device check)
  - Added `dismissHrDialog()` to both ViewModels
  - Updated both screens to show the contextual dialog
  - Modified: `MorningReadinessViewModel.kt`, `MorningReadinessScreen.kt`, `ReadinessViewModel.kt`, `ReadinessScreen.kt`
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-14 14:04)
- ✅ **Apnea Min Breath drill fixes — Spotify music, tail increments, back arrow cancel:**
  - **Spotify music auto-trigger**: Added `spotifyManager.startTracking()` + `sendPlayCommand()` in `MinBreathViewModel.startSession()` when audio=MUSIC; stop/tracking/save in `stopSession()`, `cancelSession()`, init-block COMPLETE observer, and `onCleared()`
  - **Tail increment double-fire fix**: Root cause was `stopSession()` calling `stateMachine.stop()` which set phase=COMPLETE, then the init-block observer also saw COMPLETE and called `saveSession()` again. Fix: set `isSessionActive = false` BEFORE `stateMachine.stop()` so the init-block guard prevents double-save. Same fix in ProgressiveO2ViewModel.
  - **Back arrow cancels without saving**: Added `cancelSession()` to MinBreathViewModel, ProgressiveO2ViewModel, AdvancedApneaViewModel; added `cancelTableSession()` to ApneaViewModel. All active screens now call cancel from both SessionBackHandler and navigation icon. Fixed FreeHoldActiveScreen SessionBackHandler to call `cancelFreeHold()`.
  - Modified: `MinBreathViewModel.kt`, `MinBreathActiveScreen.kt`, `ProgressiveO2ViewModel.kt`, `ProgressiveO2ActiveScreen.kt`, `AdvancedApneaViewModel.kt`, `AdvancedApneaScreen.kt`, `ApneaViewModel.kt`, `ApneaTableScreen.kt`, `FreeHoldActiveScreen.kt`
  - Build: ✅ Compiled successfully. No device connected for install.

## Recent Changes (2026-04-12 04:29)
- ✅ **Tap-to-inspect popup on ALL detail screen graphs:**
  - Extended the meditation detail screen's tap-to-inspect chart feature to every detail screen with graphs
  - Modified 6 files: `ApneaRecordDetailScreen.kt`, `ProgressiveO2DetailScreen.kt`, `MorningReadinessDetailScreen.kt`, `ResonanceSessionDetailScreen.kt`, `RapidHrDetailScreen.kt`, `MinBreathDetailContent.kt`
  - Each chart now wraps Canvas in Box, adds pointerInput tap detection, draws crosshair+dot indicator, shows Popup with value/time
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-11 12:25)
- ✅ **Per-guided-MP3 hyper settings + "Start MP3 with Hyper" checkbox:**
  - **Per-MP3 settings storage**: `GuidedAudioManager` now stores per-MP3 hyper settings (relaxedExhaleSec, purgeExhaleSec, transitionSec, startMp3WithHyper) in SharedPreferences keyed by audioId
  - **Auto-restore on selection**: `selectGuidedAudio()` restores per-MP3 hyper settings when selecting a guided MP3 that has previously saved settings
  - **Auto-save on change**: Hyper phase duration setters now also save per-MP3 when in guided mode
  - **"Start MP3 with Hyper" checkbox**: New checkbox in `GuidedHyperSection` (visible when audio=GUIDED + prep=HYPER + guided hyper enabled). When checked, guided MP3 starts playing at the beginning of the hyper countdown
  - **Playback logic**: `showGuidedCountdown()` starts audio if startMp3WithHyper=true; `startFreeHold()` skips if already playing; `onGuidedCountdownCancelled()` stops audio if started with hyper
  - Modified: `GuidedAudioManager.kt`, `FreeHoldActiveScreen.kt`
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-11 11:42)
- ✅ **Fix guided audio not playing during free hold:**
  - Root cause: `startPlayback()` called `stopPlayback()` first, which cleared `_cachedUri = null` before it could be read
  - Fix: Save `_cachedUri` to local var before calling `stopPlayback()`
  - Modified: `GuidedAudioManager.kt`
- ✅ **Fix guided hyperventilation back button auto-starting hold:**
  - Root cause: `onGuidedCountdownCancelled()` called `startFreeHold()` after dismissing dialog
  - Fix: Removed `startFreeHold()` call — back button now just dismisses dialog and shows Start button
  - Modified: `FreeHoldActiveScreen.kt`
- Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-11 11:14)
- ✅ **Progressive O₂ — Spotify music integration + active session UI fixes:**
  - **Music auto-start**: `startSession()` now calls `spotifyManager.startTracking()` + `sendPlayCommand()` when audio=MUSIC
  - **Music auto-stop**: `stopSession()` now calls `spotifyManager.stopTracking()` + `sendPauseAndRewindCommand()` when audio=MUSIC
  - **Song names recorded**: Tracked songs saved to `apnea_song_log` DB table via `apneaRepository.saveSongLog()` + SharedPreferences via `persistSongHistory()`
  - **First Contraction button**: Large 120dp button during HOLD phase replaces CompletedRoundsList; shows "✓ Contraction Logged" after tap; target/round info at top, stop button at bottom
  - **State machine**: Added `firstContractionMs: Long?` to `ProgressiveO2State`, `signalFirstContraction()` to `ProgressiveO2StateMachine`
  - **ViewModel**: Added `logFirstContraction()` with haptic feedback, `persistSongHistory()`, Spotify cleanup in `onCleared()`
  - Modified: `ProgressiveO2ViewModel.kt`, `ProgressiveO2ActiveScreen.kt`, `ProgressiveO2StateMachine.kt`
  - Build: ✅ Successful, installed on SM-S918U1

## Recent Changes (2026-04-11 10:11)
- Implemented two-checkmark completion system for Guided Audio Picker dialog
- Added `GuidedCompletionStatus` data class to `GuidedAudioPicker.kt`
- Added `wasGuidedAudioUsedEver()` and `wasGuidedAudioUsedWithSettings()` to `ApneaRecordDao.kt` and `ApneaRepository.kt`
- Added `guidedCompletionStatuses` to UiState in all 5 guided-audio ViewModels
- Added `loadGuidedCompletionStatuses()` to all 5 ViewModels
- Updated `GuidedAudioPickerDialog` with two-checkmark visual system matching Spotify song picker
- Updated all 5 screen call sites to pass completion statuses
- Build successful, installed on device

## Recent Changes (2026-04-11 10:08)
- ✅ **Guided Audio Picker — Two-Checkmark Completion System:**
  - Added `GuidedCompletionStatus` data class (completedEver, completedWithCurrentSettings)
  - Added `wasGuidedAudioUsedEver()` and `wasGuidedAudioUsedWithSettings()` DAO queries on `apnea_records.guidedAudioName`
  - Added corresponding repository methods in `ApneaRepository.kt`
  - Updated `GuidedAudioPickerDialog` to accept `completionStatuses` parameter
  - Updated `GuidedAudioCard` to show two checkmarks (bright = used ever, grey = used with current settings) instead of single ✓ for selected
  - Added `guidedCompletionStatuses` to all 5 UiStates: `FreeHoldActiveUiState`, `AdvancedApneaScreenUiState`, `ProgressiveO2UiState`, `MinBreathUiState`, `ApneaUiState`
  - Added `loadGuidedCompletionStatuses()` to all 5 ViewModels
  - Updated all 5 call sites with `LaunchedEffect` trigger + `completionStatuses` parameter
  - Modified: `ApneaRecordDao.kt`, `ApneaRepository.kt`, `GuidedAudioPicker.kt`, `FreeHoldActiveScreen.kt`, `AdvancedApneaViewModel.kt`, `AdvancedApneaScreen.kt`, `ProgressiveO2ViewModel.kt`, `ProgressiveO2Screen.kt`, `MinBreathViewModel.kt`, `MinBreathScreen.kt`, `ApneaViewModel.kt`, `ApneaTableScreen.kt`

## Recent Changes (2026-04-11 09:37)
- ✅ **Guided Audio — new audio type for apnea section (complete rework):**
  - `GUIDED` as 4th `AudioSetting` enum entry
  - `guidedAudioName: String?` column on `ApneaRecordEntity` (DB v30→v31)
  - `guided_audios` DB table with `audioId`, `fileName`, `uri`, `sourceUrl` (DB v31→v32)
  - `GuidedAudioDao` — Room DAO with Flow-based observeAll(), CRUD
  - `GuidedAudioManager` — @Singleton, DB-backed library + SharedPreferences selected ID + MediaPlayer (looping)
  - `GuidedAudioPickerButton` — "Choose a guided MP3" button (like SongPickerButton)
  - `GuidedAudioPickerDialog` — 3-view dialog (LIST/ADD_NEW/DETAIL) with file picker, long-press, delete, copy URL
  - `SelectedGuidedAudioBanner` — shows current selection name
  - Wired into ALL drill screens: FreeHoldActiveScreen, ApneaTableScreen, ProgressiveO2Screen, MinBreathScreen, AdvancedApneaScreen
  - NOT shown on main ApneaScreen settings section (only on drill setup screens, like Spotify)
  - Guided audio name shown in AllApneaRecordsScreen rows + ApneaRecordDetailScreen summary
  - GUIDED in all filter chips via `AudioSetting.entries`
  - New files: `GuidedAudioEntity.kt`, `GuidedAudioDao.kt`, `GuidedAudioManager.kt`, `GuidedAudioPicker.kt`
  - Modified: 19 existing files

## Recent Changes (2026-04-11 09:35)
- ✅ **Rework Part C: Wire New Guided Audio UI into All ViewModels + Screens:**
  - All apnea ViewModels updated: replaced old `guidedAudioName`/`guidedAudioYoutubeUrl` state with `guidedAudios`/`guidedSelectedId`/`guidedSelectedName`
  - All ViewModels now collect `guidedAudioManager.allAudios` Flow in init + async `getSelectedName()` load
  - Old methods (`onGuidedFilePicked`, `onGuidedYoutubeUrlChanged`, `clearGuidedAudio`) replaced with `selectGuidedAudio`, `addGuidedAudio`, `deleteGuidedAudio`
  - All `startPlayback()` calls now preceded by `preparePlayback()` in coroutine
  - All record saves use `_uiState.value.guidedSelectedName` instead of `guidedAudioManager.selectedName`
  - All screens: replaced `GuidedAudioPicker` with `SelectedGuidedAudioBanner` + `GuidedAudioPickerButton` + `GuidedAudioPickerDialog`
  - `ApneaScreen.kt`: Removed guided audio picker from `ApneaSettingsContent` entirely
  - Fixed `MIGRATION_31_32` nesting bug in `WagsDatabase.kt` (was nested inside `MIGRATION_30_31`)
  - Modified: `ApneaViewModel.kt`, `ApneaScreen.kt`, `FreeHoldActiveScreen.kt`, `ProgressiveO2ViewModel.kt`, `ProgressiveO2Screen.kt`, `MinBreathViewModel.kt`, `MinBreathScreen.kt`, `AdvancedApneaViewModel.kt`, `AdvancedApneaScreen.kt`, `ApneaTableScreen.kt`, `WagsDatabase.kt`

## Recent Changes (2026-04-11 08:40)
- ✅ **Rework Part B: New Guided Audio UI Components — Button + Popup Dialog:**
  - Replaced entire `GuidedAudioPicker.kt` with 3 new composables:
    - `GuidedAudioPickerButton` — Surface button styled like `SongPickerButton`, "🎧 Choose a guided MP3"
    - `SelectedGuidedAudioBanner` — Shows selected audio name with 🎧 icon, SurfaceVariant background
    - `GuidedAudioPickerDialog` — AlertDialog with LIST/ADD_NEW/DETAIL views, LazyColumn library list, file picker, source URL, copy URL, delete
  - Private `GuidedAudioCard` composable for library list items (tap to select, long-press for detail, checkmark for selected)
  - `resolveDisplayName` helper kept (changed to `internal` visibility)
  - Old `GuidedAudioPicker` composable removed
  - Modified: `GuidedAudioPicker.kt`

## Recent Changes (2026-04-11 08:35)
- ✅ **Rework Part A: DB-backed Guided Audio Library + Manager Update:**
  - New `GuidedAudioEntity.kt` — Room entity for `guided_audios` table (audioId, fileName, uri, sourceUrl)
  - New `GuidedAudioDao.kt` — DAO with observeAll (Flow), getAll, getById, insert, delete, deleteById
  - `WagsDatabase.kt` — Added entity + DAO + `MIGRATION_31_32` (CREATE TABLE guided_audios), version 31→32
  - `DatabaseModule.kt` — Registered `MIGRATION_31_32`, added `provideGuidedAudioDao()`
  - `GuidedAudioManager.kt` — Complete rewrite: injects `GuidedAudioDao`, stores only selected ID in SharedPreferences, library CRUD via DB, suspend functions for `getSelectedAudio()`/`getSelectedName()`, `preparePlayback()` caches URI before playback
  - Old SharedPreferences keys removed (no migration needed)
  - UI/ViewModel files NOT updated yet (Part B)

## Recent Changes (2026-04-10 17:00)
- ✅ **Guided Audio — new audio type for apnea section:**
  - Added `GUIDED` as 4th `AudioSetting` enum entry (Silence, Music, Movie, Guided)
  - `guidedAudioName: String?` column on `ApneaRecordEntity` (DB v30→v31)
  - `GuidedAudioManager` singleton: SharedPreferences-backed file selection + MediaPlayer playback (looping)
  - `GuidedAudioPicker` composable: SAF `OpenDocument` file picker + YouTube URL field
  - Wired into ALL apnea screens: ApneaScreen, FreeHoldActiveScreen, ApneaTableScreen, ProgressiveO2Screen, MinBreathScreen, AdvancedApneaScreen
  - Wired into ALL apnea ViewModels: ApneaViewModel, FreeHoldActiveViewModel, ProgressiveO2ViewModel, MinBreathViewModel, AdvancedApneaViewModel
  - Guided audio name shown in AllApneaRecordsScreen record rows and ApneaRecordDetailScreen summary
  - GUIDED appears in all filter chips (ApneaHistoryScreen stats filter, AllApneaRecordsScreen filter, ApneaRecordDetailScreen edit sheet, FreeHoldSettingsDialog) via `AudioSetting.entries`
  - New files: `GuidedAudioManager.kt`, `GuidedAudioPicker.kt`
  - Modified: 17 existing files

## Recent Changes (2026-04-10 16:58)
- ✅ **Phase 3c: Guided Audio — History/Stats/AllRecords/Detail screen filters + display:**
  - `AllApneaRecordsScreen.kt` — Added guided audio name display ("🎧 name") in `AllRecordsRow` for both table and free hold records (with TextOverflow.Ellipsis)
  - `ApneaRecordDetailScreen.kt` — Added "Guided Audio" row in Summary card after Audio row (with TextOverflow.Ellipsis, widthIn max 200.dp)
  - Verified: `ApneaHistoryScreen.kt` stats filter already uses `AudioSetting.entries` ✅
  - Verified: `AllApneaRecordsViewModel.kt` filter summary uses `AudioSetting.valueOf()` ✅
  - Verified: `ApneaRecordDetailViewModel.kt` edit audio uses `AudioSetting` type ✅
  - Verified: All filter chip sections use `AudioSetting.entries.forEach` — GUIDED appears automatically ✅
  - Modified: `AllApneaRecordsScreen.kt`, `ApneaRecordDetailScreen.kt`

## Recent Changes (2026-04-10 16:53)
- ✅ **Phase 3b: Guided Audio — Remaining drill ViewModels + screens:**
  - `ProgressiveO2ViewModel.kt` — Injected `GuidedAudioManager`, added `isGuidedMode`/`guidedAudioName`/`guidedAudioYoutubeUrl` to UI state, init, `setAudio()`, guided methods, start/stop playback, `guidedAudioName` in record entity, `onCleared()` cleanup
  - `ProgressiveO2Screen.kt` — Added `GuidedAudioPicker` block (visible when `isGuidedMode`)
  - `MinBreathViewModel.kt` — Same pattern: injected manager, guided state, methods, playback, record entity, cleanup
  - `MinBreathScreen.kt` — Added `GuidedAudioPicker` block (visible when `isGuidedMode`)
  - `AdvancedApneaViewModel.kt` — Injected manager, guided state, methods, playback in start/stop, cleanup
  - `AdvancedApneaScreen.kt` — Added `GuidedAudioPicker` block (visible when `isGuidedMode` + IDLE)
  - `ApneaTableScreen.kt` — Added `GuidedAudioPicker` block (visible when `GUIDED` + IDLE), uses existing `ApneaViewModel` methods from Phase 3a
  - Modified: `ProgressiveO2ViewModel.kt`, `ProgressiveO2Screen.kt`, `MinBreathViewModel.kt`, `MinBreathScreen.kt`, `AdvancedApneaViewModel.kt`, `AdvancedApneaScreen.kt`, `ApneaTableScreen.kt`

## Recent Changes (2026-04-10 16:47)
- ✅ **Phase 3a: Guided Audio — ViewModel + Screen wiring:**
  - `ApneaViewModel.kt` — Injected `GuidedAudioManager`, added `isGuidedMode`/`guidedAudioName`/`guidedAudioYoutubeUrl` to `ApneaUiState`, restored guided state in init, updated `setAudio()`, added guided methods (`onGuidedFilePicked`, `onGuidedYoutubeUrlChanged`, `clearGuidedAudio`), added `guidedAudioName` to all `ApneaRecordEntity` constructions, added start/stop playback in free hold/table/advanced session lifecycle, cleanup in `onCleared()`
  - `ApneaScreen.kt` — Added guided audio params to `ApneaSettingsContent`, threaded callbacks from ViewModel, added `GuidedAudioPicker` below Audio chips (visible when GUIDED selected)
  - `FreeHoldActiveScreen.kt` — Injected `GuidedAudioManager` in ViewModel, added guided state to `FreeHoldActiveUiState`, initialized `isGuidedMode`, added guided methods, updated `updateAudio()`/`startFreeHold()`/`cancelFreeHold()`/`stopFreeHold()`, added `guidedAudioName` to record entity, added `GuidedAudioPicker` to screen (visible when GUIDED + hold not active)
  - `FreeHoldSettingsDialog.kt` — No changes needed (already uses `AudioSetting.entries`)
  - Modified: `ApneaViewModel.kt`, `ApneaScreen.kt`, `FreeHoldActiveScreen.kt`

## Recent Changes (2026-04-10 16:38)
- ✅ **Phase 2: GuidedAudioPicker + GuidedAudioManager:**
  - New `GuidedAudioManager.kt` — `@Singleton` managing guided audio URI/name/YouTube URL in SharedPreferences + MediaPlayer playback (play/stop/loop)
  - New `GuidedAudioPicker.kt` — Reusable composable with MP3 file picker (OpenDocument + persistable URI permissions), selected file display, YouTube URL field, Clear button
  - No existing files modified — these are standalone new files not yet wired into screens

## Recent Changes (2026-04-10 16:35)
- ✅ **Phase 1: GUIDED audio type foundation:**
  - Added `GUIDED` to `AudioSetting` enum with `displayName()` support
  - Added `guidedAudioName: String?` column to `ApneaRecordEntity` (nullable, default NULL)
  - DB migration v30→v31: `ALTER TABLE apnea_records ADD COLUMN guidedAudioName TEXT DEFAULT NULL`
  - Registered `MIGRATION_30_31` in `DatabaseModule.kt`
  - Updated `displayAudioBanner()` in `ApneaSettingsSummaryBanner.kt` and `displaySettingLabel()` in `ApneaHistoryScreen.kt`
  - Modified: `AudioSetting.kt`, `ApneaRecordEntity.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`, `ApneaSettingsSummaryBanner.kt`, `ApneaHistoryScreen.kt`

## Recent Changes (2026-04-10 15:41)
- ✅ **Fix O2/CO2 table duration display bug:**
  - Root cause: `durationMs` on `ApneaRecordEntity` stored longest single hold instead of total hold time for O2/CO2 tables
  - `ApneaViewModel.saveCompletedSession()` — now stores `sumOf { apneaDurationMs }` instead of `maxOfOrNull { apneaDurationMs }`
  - DB Migration v29→v30 backfills existing records: CO2 = `durationMs * totalRounds`, O2 = `totalSessionDurationMs - (totalRounds * 60000)`
  - `AllApneaRecordsScreen` — O2/CO2 cards show "Total hold time" label
  - `AllApneaRecordsViewModel` — chart Y-axis label updated
  - `ApneaRecordDetailScreen` — Summary hides Duration for O2/CO2; Table Session shows "Total Hold Time" + "Total Session Time"; HR/SpO2 charts use `totalSessionDurationMs` for X-axis
  - Stats tab and TimeChartViewModel automatically correct (they sum `durationMs` which is now correct)
  - Modified: `ApneaViewModel.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`, `AllApneaRecordsScreen.kt`, `AllApneaRecordsViewModel.kt`, `ApneaRecordDetailScreen.kt`

## Recent Changes (2026-04-10 14:46)
- ✅ **Clickable Time Charts for Total Times section:**
  - New `TimeChartScreen.kt` — landscape-forced screen with Daily bar chart / Cumulative line chart toggle
  - New `TimeChartViewModel.kt` — loads all records/sessions, computes per-day aggregation for hold or session time by drill type
  - All Total Times rows (per-drill-type + Total) now clickable, navigating to the chart screen
  - `WagsNavGraph.kt` — added TIME_CHART route, timeChart() helper, composable block
  - `ApneaHistoryScreen.kt` — onTimeChartClick callback threaded through, HistoryStatsRow gets optional onClick
  - `ApneaRecordDao.kt` / `ApneaSessionDao.kt` — getAllOnce() queries
  - `ApneaRepository.kt` / `ApneaSessionRepository.kt` — getAllRecordsOnce() / getAllSessionsOnce() methods

## Recent Changes (2026-04-10 08:11)
- ✅ **Apnea Stats: Total Times section + "All" filter option:**
  - New "Total Times" section on Stats tab showing per-drill-type Total Hold Time and Total Session Time with bold totals
  - "All" chip added to each setting in the Stats filter dialog (Lung Volume, Prep, Time of Day, Posture, Audio)
  - DAO queries updated with `(:param = 'ALL' OR column = :param)` pattern for flexible filtering
  - ViewModel refactored from typed enums to String-based settings with `FILTER_ALL = "ALL"` sentinel
  - New SUM queries in ApneaRecordDao and ApneaSessionDao for total hold/session times
  - ApneaStats model expanded with 10 new total time fields + 2 computed properties
  - ApneaRepository expanded with sessionDao injection and total time flow groups
  - Modified: `ApneaStats.kt`, `ApneaRecordDao.kt`, `ApneaSessionDao.kt`, `FreeHoldTelemetryDao.kt`, `ApneaRepository.kt`, `ApneaHistoryViewModel.kt`, `ApneaHistoryScreen.kt`

## Recent Changes (2026-04-10 07:39)
- ✅ **Vibration/Voice indication system standardized:**
  - Final countdown vibration (last of 10) is now extra-long (400ms high-amplitude) to signal "hold starts NOW"
  - Voice and vibration independently toggleable via persisted SharedPreferences settings
  - New `VoiceVibrationToggles` composable with checkboxes shown on Progressive O₂ and O₂/CO₂ table setup screens
  - Min Breath does NOT get toggles (user-driven, no countdowns)
  - Voice announcements ("Hold", "Breathe") now have 500ms silence prefix to prevent audio clipping
  - Settings shared across all screens via `@Singleton` `ApneaAudioHapticEngine`
  - Safety abort calls always fire regardless of settings
  - New file: `VoiceVibrationToggles.kt`
  - Modified: `ApneaAudioHapticEngine.kt`, `ProgressiveO2ViewModel.kt`, `ProgressiveO2Screen.kt`, `ApneaViewModel.kt`, `ApneaTableScreen.kt`

## Recent Changes (2026-04-10 07:11)
- ✅ **Tail habits integration expanded:**
  - `APNEA_NEW_RECORD` now fires for **all drill types** (Progressive O₂ and Min Breath), not just free holds — fires whenever `checkBroaderPersonalBest()` returns a non-null result
  - New `Slot.PROGRESSIVE_O2` — fires on every completed Progressive O₂ session
  - New `Slot.MIN_BREATH` — fires on every completed Min Breath session
  - Both new slots wired end-to-end: `HabitIntegrationRepository` → `ProgressiveO2ViewModel` / `MinBreathViewModel` → `SettingsViewModel` → `SettingsScreen.TailAppIntegrationCard`
  - Modified: `HabitIntegrationRepository.kt`, `ProgressiveO2ViewModel.kt`, `MinBreathViewModel.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`

## Recent Changes (2026-04-10 06:43)
- ✅ **Min Breath detail screen follow-up fixes:**
  - Removed redundant "Total Hold Time" from Summary section (already in Min Breath Session section)
  - Header changed from "Hold Detail" to "Session Details" with "Min Breath" subtitle
  - Hold Breakdown now shows breathing time after each hold (comma-separated) instead of ⚡ contraction time
  - Per-breath-period durations tracked in ViewModel and stored in `tableParamsJson` as `breathDurationMs`
  - DB migration v28→v29 backfills old MIN_BREATH records' `durationMs` with `totalHoldTimeMs` from session JSON
  - Modified: `ApneaRecordDetailScreen.kt`, `MinBreathDetailContent.kt`, `MinBreathViewModel.kt`, `WagsDatabase.kt`, `DatabaseModule.kt`

## Recent Changes (2026-04-10 06:10)
- ✅ **Min Breath drill — 7 fixes** across detail screen, active screen, All Records, and trophy system:
  - Detail screen chart now shows HR/SpO₂ across entire session with breathing-period shading and first-contraction dashed lines (`MinBreathSessionChart`)
  - Button colors changed from teal/orange to greyscale monochrome (`SurfaceVariant`, `ButtonPrimary`, `TextPrimary`)
  - First contraction data now correctly parsed and displayed from `tableParamsJson` holds array format
  - "Table Session" renamed to "Min Breath Session" with Min Breath-specific layout (total hold time, breath time, hold %, per-hold breakdown)
  - `durationMs` on `ApneaRecordEntity` now stores `totalHoldTimeMs` (not longest single hold) for Min Breath records
  - All Records card shows "Total hold time" instead of "Longest hold" for Min Breath
  - Trophy/PB system now correctly based on total hold time (automatic since `durationMs` changed)
  - New file: `MinBreathDetailContent.kt`
  - Modified: `MinBreathActiveScreen.kt`, `MinBreathViewModel.kt`, `ApneaRecordDetailScreen.kt`, `AllApneaRecordsScreen.kt`, `AllApneaRecordsViewModel.kt`

## Recent Changes (2026-04-09 21:04)
- ✅ **Backfill drillParamValue for old records** — DB migration v27→v28 uses `json_extract()` to populate `drillParamValue` from `apnea_sessions.tableParamsJson` for old Progressive O₂ (`breathPeriodSec`) and Min Breath (`sessionDurationSec`) records that had `drillParamValue IS NULL`.
  - Modified: `WagsDatabase.kt` (version 27→28, added `MIGRATION_27_28`), `DatabaseModule.kt` (registered migration)

## Recent Changes (2026-04-09 20:57)
- ✅ **Per-param-value trophy display** on main ApneaScreen. Trophies now show PBs for the currently selected breath period (Progressive O₂) or session duration (Min Breath). Param label shown above trophies. `refreshDrillParams()` re-reads from SharedPreferences on ON_RESUME so trophies update after changing param in drill setup screen. Trophy click navigates with specific `drillParamValue`.
  - Modified: `ApneaViewModel.kt`, `ApneaScreen.kt`

## Recent Changes (2026-04-09 20:31)
- ✅ **Trophy display on main ApneaScreen** for Progressive O₂ and Min Breath sections. Added `DrillSectionContent` composable, `getDrillBestAndTrophy()`, `computeBroadestCurrentCategoryForDrill()`, `PROGRESSIVE_O2_ANY` / `MIN_BREATH_ANY` DrillContext constants.
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
