# WAGS — Active Context

*Last updated: 2026-04-18 08:24 UTC-4*

### 2026-04-18 08:30 (UTC-4)
**Task:** Fix Spotify song picker UX — app loses focus + song doesn't load on first click when Spotify is not active

**What was done:**
1. Modified [`SpotifyManager.ensureSpotifyActive()`](app/src/main/java/com/example/wags/data/spotify/SpotifyManager.kt:417) — after launching Spotify, immediately brings our app back to foreground via `FLAG_ACTIVITY_REORDER_TO_FRONT` so user stays in our app
2. Added [`SpotifyManager.preloadTrack()`](app/src/main/java/com/example/wags/data/spotify/SpotifyManager.kt:470) — new method that handles the full pre-load sequence: ensure Spotify active → wake player via media key (if just launched) → retry `startPlayback()` up to 6 times → pause & rewind
3. Added [`SpotifyManager.bringAppToForeground()`](app/src/main/java/com/example/wags/data/spotify/SpotifyManager.kt:508) — private helper using launch intent with `FLAG_ACTIVITY_REORDER_TO_FRONT`
4. Updated all 5 ViewModels' `selectSong()` to use `spotifyManager.preloadTrack()` instead of manually calling `ensureSpotifyActive()` + `startPlayback()` + delay + pause:
   - `FreeHoldActiveScreen.kt` (line 900)
   - `ApneaViewModel.kt` (line 1479)
   - `AdvancedApneaViewModel.kt` (line 309)
   - `MinBreathViewModel.kt` (line 342)
   - `ProgressiveO2ViewModel.kt` (line 325)

**Key design decisions:**
- `ensureSpotifyActive()` now calls `bringAppToForeground()` after launching Spotify (500ms delay to let Spotify render first)
- `preloadTrack()` detects when Spotify was just launched (`wasActive` flag) and sends a media key "play" to wake Spotify's player — this triggers Spotify to register itself as a Web API device on its servers, which is required before `startPlayback()` can work
- After waking the player, waits 2s then pauses before retrying `startPlayback()` with the chosen track
- Retries `startPlayback()` up to 6 times with 1.5s delay between attempts — handles the common case where Spotify's Web API returns 404 right after Spotify was launched
- `bringAppToForeground()` uses `FLAG_ACTIVITY_REORDER_TO_FRONT` to avoid recreating the activity

**Build:** Successful, installed on SM-S918U1

### 2026-04-17 09:18 (UTC-4)
**Task:** Implement record-breaking forecast feature (Tier C) for free holds

**What was done:**
1. Created 5 domain model files in `domain/usecase/apnea/forecast/`:
   - `RecordForecast.kt` — data classes: RecordForecast, CategoryForecast, ForecastConfidence, ForecastStatus, ForecastSettings
   - `NormalCdf.kt` — standard-normal CDF approximation (Abramowitz-Stegun 26.2.17)
   - `OlsRegression.kt` — OLS with ridge penalty, Gaussian elimination solver, predict method
   - `FreeHoldFeatureExtractor.kt` — dummy-codes 5 settings + training-trend term into 12-feature vector
   - `RecordForecastCalculator.kt` — orchestrator: fits model, computes P(next > PB) for all 32 sub-combinations, empirical-Bayes shrinkage for exact cell, 100% when no prior record exists
2. Created `ForecastCalibrationEntity` + `ForecastCalibrationDao` — DB table tracking predictions vs. actual outcomes for future calibration analysis
3. DB migration v34→v35: CREATE TABLE forecast_calibration
4. Added `getAllFreeHoldsOnce()` to `ApneaRepository`
5. Wired `ApneaViewModel`: added `recordForecast` to UI state, debounced (150ms) recompute on settings change, calibration logging at hold start + actual outcome at hold stop
6. Created `RecordForecastDialog.kt` — popup showing all 32 categories sorted by probability descending, with confidence pills and record times
7. Created `RecordForecastSummary.kt` — one-line row in Free Hold card showing exact-combo %, tappable to open dialog
8. Integrated into `ApneaScreen.kt` — added `recordForecast` parameter to `FreeHoldContent`, passed from state

**Key design decisions:**
- Minimum 5 free holds to produce a forecast (user preference, lower than default 20)
- Log-linear regression on 5 settings (dummy-coded) + days_since_first_hold trend
- Popup sorted by probability descending (most-likely-to-break first)
- Calibration log table for future model accuracy analysis (Tier D/E hook)
- No cross-drill data (Tier C only uses free holds)

**Files created:**
- `domain/usecase/apnea/forecast/RecordForecast.kt`
- `domain/usecase/apnea/forecast/NormalCdf.kt`
- `domain/usecase/apnea/forecast/OlsRegression.kt`
- `domain/usecase/apnea/forecast/FreeHoldFeatureExtractor.kt`
- `domain/usecase/apnea/forecast/RecordForecastCalculator.kt`
- `data/db/entity/ForecastCalibrationEntity.kt`
- `data/db/dao/ForecastCalibrationDao.kt`
- `ui/apnea/forecast/RecordForecastDialog.kt`
- `ui/apnea/forecast/RecordForecastSummary.kt`

**Files modified:**
- `data/db/WagsDatabase.kt` — added entity, DAO, MIGRATION_34_35, bumped to v35
- `di/DatabaseModule.kt` — added MIGRATION_34_35, ForecastCalibrationDao provider
- `data/repository/ApneaRepository.kt` — added getAllFreeHoldsOnce()
- `ui/apnea/ApneaViewModel.kt` — added forecast state, debounced recompute, calibration logging
- `ui/apnea/ApneaScreen.kt` — added recordForecast param to FreeHoldContent, RecordForecastSummary

**Build:** Successful (assembleDebug)

### 2026-04-16 17:55 (UTC-4)
**Task:** Change splash screen background from white to black

**What was done:**
1. Added `android:windowBackground` set to `@color/black` in `Theme.Wags` style in `themes.xml`. The parent theme `android:Theme.Material.Light.NoActionBar` has a white window background by default, which caused the bright white splash screen before Compose rendered.

**Files modified:**
- `app/src/main/res/values/themes.xml` — Added `<item name="android:windowBackground">@color/black</item>` to `Theme.Wags`

**Build:** Compiled successfully. Install failed — no device connected.

### 2026-04-15 14:27 (UTC-4)
**Task:** Add live HR/SpO₂ feed to top bar on ALL screens + click-to-navigate-to-settings

**What was done:**

1. **Created `LiveSensorViewModel`** — lightweight HiltViewModel that injects `HrDataSource` so any screen can get live HR/SpO₂ without modifying its own ViewModel
2. **Added `onClick` parameter to `LiveSensorActions`** — the entire HR/SpO₂ row is now clickable
3. **Created `LiveSensorActionsNav`** — self-contained composable for screens with `NavController`; pulls data from `LiveSensorViewModel` and navigates to Settings on click
4. **Created `LiveSensorActionsCallback`** — callback-based version for screens that use `onNavigateBack`-style navigation instead of `NavController`
5. **Updated all 10 existing `LiveSensorActions` calls** to include `onClick = { navController.navigate(WagsRoutes.SETTINGS) }`
6. **Added `LiveSensorActionsNav` to 15 screens with `navController`**: SettingsScreen, MinBreathActiveScreen, ProgressiveO2ActiveScreen, AdvancedApneaScreen, ApneaTableScreen, ApneaHistoryScreen, AllApneaRecordsScreen (added TopAppBar), GarminScreen, MeditationHistoryScreen, MeditationSessionDetailScreen, RapidHrDetailScreen, RapidHrHistoryScreen, ProgressiveO2DetailScreen, PersonalBestsScreen, SessionAnalyticsScreen (both), PbChartScreen, ApneaRecordDetailScreen
7. **Added `LiveSensorActionsCallback` to 10 callback-based screens**: RateRecommendationScreen, AssessmentPickerScreen, AssessmentRunScreen, AssessmentResultScreen, RfAssessmentHistoryScreen, ResonanceSessionDetailScreen, ResonanceSessionScreen, HrvReadinessDetailScreen, HrvReadinessHistoryScreen, MorningReadinessHistoryScreen, MorningReadinessDetailScreen, MorningReadinessScreen, CrashLogScreen, AboutScreen, TimeChartScreen
8. **Replaced DashboardScreen inline HR code** with `LiveSensorActionsNav`
9. **Updated `WagsNavGraph`** to pass `onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }` to all callback-based screens

**Files modified:**
- `LiveSensorTopBar.kt` — Added `LiveSensorViewModel`, `onClick` param, `LiveSensorActionsNav`, `LiveSensorActionsCallback`
- `DashboardScreen.kt` — Replaced inline HR code with `LiveSensorActionsNav`
- `WagsNavGraph.kt` — Added `onNavigateToSettings` to all callback-based screen calls
- 25+ screen files — Added `LiveSensorActionsNav` or `LiveSensorActionsCallback` to TopAppBar actions

**Build:** Successful, installed on SM-S918U1.

**Task:** Add countdown timer to next PB record during free hold + store newRecordIndication in DB

**What was done:**

1. **Countdown timer to next PB record** — When "New Record Indication" is enabled during a free hold, a countdown is now displayed below the trophy emojis showing how far away the user is from the next PB milestone that would earn more trophies. Format: "🏆🏆 in 1m 23s" (trophy count matches the next category level).

2. **Store `newRecordIndication` in DB** — Added `newRecordIndication: Boolean` column to `apnea_records` table (DB migration v33→v34). The setting is captured at save time from `ApneaAudioHapticEngine.pbIndicationEnabled`. The apnea record detail screen now shows "Record Indication: On" when the feature was enabled during that hold.

**Files modified:**
- `PersonalBestCategory.kt` — Added `NextPbTarget` data class and `PbThresholds.nextPbTarget()` method
- `FreeHoldActiveScreen.kt` — Added `nextPbTarget` to `FreeHoldActiveUiState`, updated PB monitor loop to compute it, added countdown UI, added `formatCountdownMs()` helper, stored `newRecordIndication` in `saveFreeHoldRecord()`
- `ApneaRecordEntity.kt` — Added `newRecordIndication` column with default `0`
- `WagsDatabase.kt` — Bumped version to 34, added `MIGRATION_33_34`
- `DatabaseModule.kt` — Registered `MIGRATION_33_34`
- `ApneaRecordDetailScreen.kt` — Added "Record Indication: On" row in Summary card

**Build:** Successful, installed on SM-S918U1.

*Last updated: 2026-04-14 20:02 UTC-4*

### 2026-04-14 20:02 (UTC-4)
**Task:** Fix black flash during swipe transitions on all session detail screens

**Root cause:** In all 6 detail ViewModels, `navigateToIndex()` immediately set `isLoading = true` before the DB query ran. This caused the `HorizontalPager` page to render a `CircularProgressIndicator` on a dark background — appearing as a black flash mid-swipe.

**Fix:** Removed `isLoading = true` from `navigateToIndex()` in all 6 ViewModels. The previous record's content stays visible while the SQLite query completes (milliseconds), then swaps in — no black flash. `isLoading` is still set correctly during the initial screen load.

**Files modified:**
- `ApneaRecordDetailViewModel.kt` — removed `isLoading = true` + `isLoading = false` from `navigateToIndex()`
- `MeditationSessionDetailViewModel.kt` — same
- `ResonanceSessionDetailViewModel.kt` — same
- `HrvReadinessDetailViewModel.kt` — same
- `RapidHrDetailViewModel.kt` — same
- `MorningReadinessDetailViewModel.kt` — same

**Build:** Successful, installed on SM-S918U1.

*Last updated: 2026-04-14 23:44 UTC-4*

### 2026-04-14 23:44 (UTC-4)
**Task:** Fix swipe direction + fix delete behavior on all session detail screens

**Swipe direction fix:**
- Changed all 6 ViewModels from newest-first to **oldest-first** ordering
- `HorizontalPager`: swipe LEFT = higher index = older; swipe RIGHT = lower index = newer
- With oldest-first: swipe right = newer session, swipe left = older session ✓
- `ApneaRecordDetailViewModel`: `getAllRecordsOnce()` already returns ASC (oldest-first) — removed the `.reversed()` call
- All others: `repository.getAll().reversed()` → now just `repository.getAll()` (DAO returns DESC, so reversed = oldest-first)

**Delete behavior fix:**
- All 6 ViewModels now handle delete without popping back to history
- After deleting: removes ID from `allSessionIds`, navigates to adjacent session at same index (clamped to last)
- Only pops back to history screen when the last session is deleted
- All 6 screens: added `LaunchedEffect(state.currentIndex)` to sync ViewModel→pager after delete moves to adjacent session

**Files modified:**
- `MeditationSessionDetailViewModel.kt`, `HrvReadinessDetailViewModel.kt`, `ResonanceSessionDetailViewModel.kt`, `RapidHrDetailViewModel.kt`, `MorningReadinessDetailViewModel.kt`, `ApneaRecordDetailViewModel.kt`
- `MeditationSessionDetailScreen.kt`, `HrvReadinessDetailScreen.kt`, `ResonanceSessionDetailScreen.kt`, `RapidHrDetailScreen.kt`, `MorningReadinessDetailScreen.kt`, `ApneaRecordDetailScreen.kt`

**Build:** Successful, installed on SM-S918U1.

*Last updated: 2026-04-14 23:33 UTC-4*

### 2026-04-14 23:33 (UTC-4)
**Task:** Fix app back button bug on meditation detail screen + add swipe left/right navigation through session history on ALL session detail screens

**What was done:**

**Back button fix:**
- Root cause: `LaunchedEffect(selectedDaySessions)` in `MeditationHistoryScreen` re-fired when screen resumed because `SharingStarted.WhileSubscribed(5_000)` restarted the flow, emitting a new list with the same 1-item content. The `lastAutoNavigatedId` guard using `remember` was broken because `remember` resets on recomposition after navigation.
- Fix: Call `viewModel.clearSelection()` after navigating in `MeditationHistoryScreen`, matching the pattern already used by `ApneaHistoryScreen`.

**Swipe navigation (HorizontalPager) added to ALL session detail screens:**
- Pattern: each ViewModel loads all session IDs ordered newest-first, tracks `currentIndex`, has `navigateToIndex()`. Screen uses `HorizontalPager` with `LaunchedEffect(pagerState.currentPage)` to sync pager → ViewModel. Top bar shows "X / Y" counter when multiple sessions exist.
- `@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)` on all updated screens.

**DAOs updated (added `getAll()`):**
- `DailyReadingDao.kt`, `RapidHrSessionDao.kt`, `ResonanceSessionDao.kt`, `MorningReadinessDao.kt`

**Repositories updated (added `getAll()`):**
- `ReadinessRepository.kt`, `RapidHrRepository.kt` (`getAllSessions()`), `ResonanceSessionRepository.kt`, `MorningReadinessRepository.kt`

**ViewModels updated with swipe navigation:**
- `MeditationSessionDetailViewModel.kt` — `allSessionIds`, `currentIndex`, `navigateToIndex()`
- `HrvReadinessDetailViewModel.kt` — `allReadingIds`, `currentIndex`, `navigateToIndex()`
- `ResonanceSessionDetailViewModel.kt` — `allSessionIds`, `currentIndex`, `navigateToIndex()`
- `RapidHrDetailViewModel.kt` — `allSessionIds`, `currentIndex`, `navigateToIndex()` (uses `it.id` for `RapidHrSessionEntity`)
- `MorningReadinessDetailViewModel.kt` — `allReadingIds`, `currentIndex`, `navigateToIndex()` (entity PK is `id`, not `readingId`)
- `ApneaRecordDetailViewModel.kt` — `allRecordIds`, `currentIndex`, `navigateToIndex()` (uses `getAllRecordsOnce().reversed()` for newest-first)

**Screens updated with HorizontalPager:**
- `MeditationHistoryScreen.kt` — back button fix (clearSelection after navigate)
- `MeditationSessionDetailScreen.kt` — HorizontalPager
- `HrvReadinessDetailScreen.kt` — HorizontalPager
- `ResonanceSessionDetailScreen.kt` — HorizontalPager
- `RapidHrDetailScreen.kt` — HorizontalPager
- `MorningReadinessDetailScreen.kt` — HorizontalPager
- `ApneaRecordDetailScreen.kt` — HorizontalPager (also fixed missing `}` for Scaffold lambda)

**Build:** Successful, installed on SM-S918U1.

*Last updated: 2026-04-14 17:37 UTC-4*

### 2026-04-14 17:37 (UTC-4)
**Task:** Add real-time personal best indication during apnea free holds

**What was done:**

Added a "New Record Indication" feature for apnea free holds that provides real-time sound and vibration feedback as the user breaks personal best records during a hold. The feature uses the same trophy system (6 levels: EXACT → GLOBAL) and same sound effects (apnea_pb1.mp3 → apnea_pb6.mp3) as the end-of-hold PB celebration.

**Settings:**
- Master toggle: "New Record Indication" checkbox (off by default)
- Sub-settings (only visible when master is on): "Sound" and "Vibration" checkboxes
- All three settings persisted in SharedPreferences via `ApneaAudioHapticEngine`
- Settings only shown on the FreeHoldActiveScreen before the hold starts

**Real-time PB monitoring:**
- At hold-start, `ApneaRepository.getPbThresholds()` pre-computes the best duration for each PB category level (exact, 4-settings, 3-settings, 2-settings, 1-setting, global) using the same DB queries as `checkBroaderPersonalBest()`
- A 1-second monitoring loop checks `PbThresholds.broadestBroken(elapsedMs)` — a pure in-memory comparison, no DB queries during the hold
- Only fires indication when a **broader** category is broken (tracks `lastIndicatedCategory` to avoid re-firing)
- Sound: same `MediaPlayer`-based playback as `ApneaPbSoundPlayer`, with `activePbPlayers` set for GC safety
- Vibration: duration scales with trophy count (1 trophy = 100ms, 6 trophies = 600ms)

**UI during hold:**
- Trophy emojis displayed below the timer when a PB is broken (using `grayscale()` modifier, same as PB dialog)
- `currentPbCategory` in `FreeHoldActiveUiState` tracks the broadest broken category

**Cleanup:**
- PB monitoring job cancelled and players released in `cancelFreeHold()`, `stopFreeHold()`, and `onCleared()`
- `currentPbCategory` reset to null when hold ends

**Files modified:**
- `PersonalBestCategory.kt` — Added `PbThresholds` data class with `broadestBroken()` method
- `ApneaRepository.kt` — Added `getPbThresholds()` method + import for `PbThresholds`
- `ApneaAudioHapticEngine.kt` — Added `pbIndicationEnabled/Sound/Vibration` settings, `playPbIndicationSound()`, `vibratePbIndication()`, `releasePbIndicationPlayers()`, `soundResId()` extension, `activePbPlayers` set
- `FreeHoldActiveScreen.kt` — Added `pbIndicationEnabled/Sound/Vibration` + `currentPbCategory` to `FreeHoldActiveUiState`, added `setPbIndicationEnabled/Sound/Vibration()` to ViewModel, added PB monitoring in `startFreeHold()`, cleanup in `cancelFreeHold()`/`stopFreeHold()`, added `PbIndicationSection` composable, added trophy display during hold, added imports

**Scope:** Only affects apnea free holds during the active hold. No changes to other drill types, table sessions, or post-hold PB celebration.

**Current state:** Build successful, installed on SM-S918U1.

### 2026-04-14 14:34 (UTC-4)
**Task:** Fix morning readiness / HRV readiness allowing sessions when connected but no HR data streaming

**What was done:**

**Bug:** Both Morning Readiness and HRV Readiness sessions could start when a BLE device (e.g. H10) was connected but not actually streaming HR data. This resulted in useless sessions with no data.

**Root cause:** The session start guards only checked `isAnyHrDeviceConnected` (BLE connection state), not whether `liveHr` was non-null (actual data flowing). The ReadinessViewModel had NO guard at all.

**Fix — MorningReadinessViewModel:**
- Replaced `noHrmDialogVisible: Boolean` with `hrDialogMessage: String?` in `MorningReadinessUiState` — handles both "no device" and "no data" cases with distinct messages
- Added `hrDataSource.liveHr.value == null` check in `startSession()` — blocks session when connected but no data, with message: "Heart rate monitor is connected but not receiving data. Make sure the strap is snug and moist, then try again."
- Renamed `dismissNoHrmDialog()` → `dismissHrDialog()`

**Fix — ReadinessViewModel:**
- Added `hrDialogMessage: String?` to `ReadinessUiState`
- Added both `isAnyHrDeviceConnected` and `liveHr == null` guards in `startSession()` (previously had NO device check at all)
- Added `dismissHrDialog()` method

**Fix — Screens:**
- `MorningReadinessScreen.kt` — Updated dialog to use `hrDialogMessage` instead of `noHrmDialogVisible`
- `ReadinessScreen.kt` — Added HR dialog (previously had none)

**Files modified:**
- `MorningReadinessViewModel.kt` — `hrDialogMessage` field, `liveHr == null` guard, `dismissHrDialog()`
- `MorningReadinessScreen.kt` — Updated dialog to use `hrDialogMessage`
- `ReadinessViewModel.kt` — `hrDialogMessage` field, both guards in `startSession()`, `dismissHrDialog()`
- `ReadinessScreen.kt` — Added HR dialog

**Current state:** Build successful, installed on SM-S918U1.

### 2026-04-14 14:03 (UTC-4)
**Task:** Fix apnea min breath drill — Spotify music, tail increments, back arrow cancel

**What was done:**

**Fix 1 — Spotify music auto-trigger in Min Breath drill:**
- Added `spotifyManager.startTracking()` + `spotifyManager.sendPlayCommand()` in `MinBreathViewModel.startSession()` when audio=MUSIC
- Added `spotifyManager.stopTracking()` + `spotifyManager.sendPauseAndRewindCommand()` in `stopSession()` and `cancelSession()` when audio=MUSIC
- Added Spotify stop + song capture in the init-block COMPLETE observer (natural session end)
- Added `trackedSongs` field, `persistSongHistory()`, and `saveSongLog()` in `saveSession()` — matching ProgressiveO2 pattern
- Added Spotify cleanup in `onCleared()`

**Fix 2 — Tail increment double-fire (only 1 increment per min breath session):**
- Root cause: `stopSession()` called `stateMachine.stop()` which set phase to COMPLETE, then the init-block observer also saw COMPLETE and called `saveSession()` again — causing multiple `habitRepo.sendHabitIncrement(Slot.MIN_BREATH)` calls
- Fix: Set `isSessionActive = false` BEFORE calling `stateMachine.stop()` in `stopSession()`, so the init-block observer's guard (`if (_uiState.value.isSessionActive)`) prevents the double-save
- Same fix applied to `ProgressiveO2ViewModel.stopSession()`

**Fix 3 — Back arrow during drill cancels without saving:**
- Added `cancelSession()` to `MinBreathViewModel` — stops state machine + Spotify/guided audio but does NOT save or fire tail increments
- Added `cancelSession()` to `ProgressiveO2ViewModel` — same pattern
- Added `cancelSession()` to `AdvancedApneaViewModel` — uses `sessionCancelled` flag to prevent init-block auto-save
- Added `cancelTableSession()` to `ApneaViewModel` — uses `tableSessionCancelled` flag to prevent `onStateChanged` auto-save
- Updated all 4 active screens to call `cancelSession()`/`cancelTableSession()` from both `SessionBackHandler` and navigation icon back arrow
- Fixed `FreeHoldActiveScreen` `SessionBackHandler` to call `viewModel.cancelFreeHold()` (was missing)

**Files modified:**
- `MinBreathViewModel.kt` — Spotify start/stop/tracking/save, cancelSession(), double-save fix, persistSongHistory(), onCleared()
- `MinBreathActiveScreen.kt` — Back arrow uses cancelSession()
- `ProgressiveO2ViewModel.kt` — cancelSession(), double-save fix
- `ProgressiveO2ActiveScreen.kt` — Back arrow uses cancelSession()
- `AdvancedApneaViewModel.kt` — cancelSession(), sessionCancelled flag
- `AdvancedApneaScreen.kt` — Back arrow uses cancelSession()
- `ApneaViewModel.kt` — cancelTableSession(), tableSessionCancelled flag
- `ApneaTableScreen.kt` — Back arrow uses cancelTableSession()
- `FreeHoldActiveScreen.kt` — SessionBackHandler now calls cancelFreeHold()

**Current state:** Build compiled successfully. No device connected for install — user needs to connect device/emulator.

### 2026-04-12 04:29 (UTC-6)
**Task:** Add tap-to-inspect popup to all detail screen graphs

**What was done:**
- Extended the tap-to-inspect chart popup feature (originally only in MeditationSessionDetailScreen) to ALL detail screens with graphs across the app
- Each chart now shows a crosshair + dot on tap, with a small popup displaying the value and time at that point
- Tapping the same point again dismisses the popup; tapping a different point moves it

**Screens modified:**
- `ApneaRecordDetailScreen.kt` — `LineChart` composable (HR + SpO₂ charts): added `unit` param, `Box` wrapper, `pointerInput` tap detection, crosshair indicator, `Popup` with value+time
- `ProgressiveO2DetailScreen.kt` — `TelemetryLineChart` composable (HR + SpO₂ charts): added `unit` param, `Box` wrapper, tap detection, crosshair, popup
- `MorningReadinessDetailScreen.kt` — `TelemetryLineChart` composable (HR + RMSSD charts): added `unit` param, `Box` wrapper, tap detection, crosshair, popup (value only, no time since it's phase-based)
- `ResonanceSessionDetailScreen.kt` — `CoherenceHistoryChart` composable: added `Box` wrapper, tap detection, crosshair, popup showing coherence value (2 decimal places)
- `RapidHrDetailScreen.kt` — `HrSessionChart` composable: added `Box` wrapper, tap detection, crosshair, popup showing HR bpm
- `MinBreathDetailContent.kt` — `MinBreathSessionChart` composable: added `Box` wrapper, tap detection using timestamp-based positioning, crosshair, popup with value+time

**Pattern used (same as meditation):**
1. Wrap `Canvas` in a `Box(modifier = modifier)`
2. Add `pointerInput` with `detectTapGestures` to the Canvas
3. Track `tappedIndex` + `chartWidthPx` state
4. Draw vertical crosshair + dot indicator on Canvas for tapped point
5. Show `Popup` with value/time info, positioned near the tapped point

**Current state:** All detail screen graphs now have tap-to-inspect. Build successful, installed on SM-S918U1.

### 2026-04-11 12:25 (UTC-6)
**Task:** Per-guided-MP3 hyper settings + "Start MP3 with Hyper" checkbox

**What was done:**
- **Per-MP3 hyper settings storage:** Added `PerMp3HyperSettings` data class and per-MP3 SharedPreferences storage to `GuidedAudioManager` — each guided MP3 now remembers its own relaxedExhaleSec, purgeExhaleSec, transitionSec, and startMp3WithHyper flag (keyed by audioId).
- **Auto-restore on MP3 selection:** When selecting a guided MP3 in the picker, if that MP3 has previously saved hyper settings, they are automatically restored to the UI (overriding the global defaults). This includes the three phase durations and the startMp3WithHyper checkbox.
- **Auto-save on change:** When the user changes any hyper phase duration while in guided mode, the new value is saved both globally (SharedPreferences) and per-MP3 (keyed by audioId).
- **"Start MP3 with Hyper" checkbox:** New checkbox shown in the GuidedHyperSection when audio=GUIDED and prep=HYPER and guided hyper is enabled. When checked, the guided MP3 starts playing at the beginning of the hyper countdown (not at hold start).
- **Playback logic:** `showGuidedCountdown()` now starts guided audio if `startMp3WithHyper` is true. `startFreeHold()` skips starting guided audio if it's already playing (from the hyper countdown). `onGuidedCountdownCancelled()` stops the audio if it was started with hyper.

**Files modified:**
- `GuidedAudioManager.kt` — Added `PerMp3HyperSettings` data class, per-MP3 pref key constants, getter/setter/has methods for per-MP3 settings
- `FreeHoldActiveScreen.kt` — Added `startMp3WithHyper` to `FreeHoldActiveUiState`, updated ViewModel init to restore per-MP3 settings, updated `selectGuidedAudio()` to restore per-MP3 settings, updated hyper setters to save per-MP3, added `setStartMp3WithHyper()`, updated `showGuidedCountdown()` to start audio, updated `onGuidedCountdownCancelled()` to stop audio, updated `startFreeHold()` to skip if already playing, updated `GuidedHyperSection` composable with new checkbox

**Current state:** Both features implemented. Build successful, installed on SM-S918U1.

### 2026-04-11 11:42 (UTC-6)
**Task:** Fix guided audio not playing during free hold + guided hyperventilation back button behavior

**What was done:**
- **Bug 1 — Guided audio not playing:** In `GuidedAudioManager.startPlayback()`, the method called `stopPlayback()` first (to clean up any prior player), but `stopPlayback()` also cleared `_cachedUri = null`. This meant the cached URI from `preparePlayback()` was wiped before it could be used, causing `startPlayback()` to return early with no audio. **Fix:** Save `_cachedUri` to a local variable before calling `stopPlayback()`, so the URI survives the cleanup.
- **Bug 2 — Guided hyperventilation back button auto-starts hold:** `onGuidedCountdownCancelled()` was calling `startFreeHold()` after dismissing the countdown dialog. The user expected the back button to just dismiss the guided hyperventilation and show the Start button, not auto-start the hold. **Fix:** Removed the `startFreeHold()` call from `onGuidedCountdownCancelled()` — now it only marks the countdown complete and shows the Start button.

**Files modified:**
- `GuidedAudioManager.kt` — Reordered `startPlayback()` to save `_cachedUri` before calling `stopPlayback()`
- `FreeHoldActiveScreen.kt` — Removed `startFreeHold()` from `onGuidedCountdownCancelled()`, updated doc comment

**Current state:** Both bugs fixed. Build successful, installed on SM-S918U1.

### 2026-04-11 11:14 (UTC-6)
**Task:** Fix Progressive O₂ — Spotify music integration + active session UI

**What was done:**
- **Spotify music auto-start**: Added `spotifyManager.startTracking()` + `spotifyManager.sendPlayCommand()` in `startSession()` when audio=MUSIC
- **Spotify music auto-stop**: Added `spotifyManager.stopTracking()` + `spotifyManager.sendPauseAndRewindCommand()` in `stopSession()` when audio=MUSIC
- **Song names recorded**: Captured tracked songs from `spotifyManager.stopTracking()`, saved to `apnea_song_log` DB table via `apneaRepository.saveSongLog()`, and persisted to SharedPreferences via new `persistSongHistory()` method
- **First Contraction button**: Replaced `CompletedRoundsList` during HOLD phase with a large 120dp "First Contraction" button. Target/round info stays at top, stop button stays small at bottom
- **State machine**: Added `firstContractionMs: Long?` field to `ProgressiveO2State` and `signalFirstContraction()` method to `ProgressiveO2StateMachine`
- **ViewModel**: Added `logFirstContraction()` method that delegates to state machine + haptic feedback
- **onCleared cleanup**: Added Spotify stop in `onCleared()` to prevent music continuing if ViewModel is destroyed

**Files modified:**
- `ProgressiveO2ViewModel.kt` — Spotify start/stop/tracking, song saving, persistSongHistory, logFirstContraction, onCleared
- `ProgressiveO2ActiveScreen.kt` — Large "First Contraction" button during HOLD, removed CompletedRoundsList from active view
- `ProgressiveO2StateMachine.kt` — firstContractionMs field, signalFirstContraction() method

**Current state:** All Progressive O₂ Spotify/music bugs fixed + First Contraction button added. Build successful, installed on SM-S918U1.

### 2026-04-11 10:11 (UTC-6)
**Task:** Implement two-checkmark completion system for Guided Audio Picker dialog

**What was done:**
- Added `GuidedCompletionStatus` data class to `GuidedAudioPicker.kt` with `completedWithAnySettings` and `completedWithCurrentSettings` fields
- Added `wasGuidedAudioUsedEver(guidedAudioName: String): Boolean` and `wasGuidedAudioUsedWithSettings(guidedAudioName, lungVolume, prepType, timeOfDay, posture, audio): Boolean` queries to `ApneaRecordDao.kt`
- Added corresponding repository methods to `ApneaRepository.kt`
- Added `guidedCompletionStatuses: Map<Long, GuidedCompletionStatus>` to UiState in: `FreeHoldActiveScreen.kt`, `AdvancedApneaViewModel.kt`, `ProgressiveO2ViewModel.kt`, `MinBreathViewModel.kt`, `ApneaViewModel.kt`
- Added `loadGuidedCompletionStatuses()` method to all 5 ViewModels
- Updated `GuidedAudioPickerDialog` to accept `completionStatuses` parameter and display two-checkmark system (bright ✓ = used with any settings, grey ✓ = used with current settings) — removed simple selected checkmark
- Updated all 5 call sites (`FreeHoldActiveScreen`, `AdvancedApneaScreen`, `ProgressiveO2Screen`, `MinBreathScreen`, `ApneaTableScreen`) with `LaunchedEffect` trigger + `completionStatuses` parameter
- Build successful, installed on SM-S918U1

**Current state:** Guided audio two-checkmark system fully implemented and deployed.

### 2026-04-11 10:08 (UTC-6)

**Guided Audio Picker — Two-Checkmark Completion System**

Added the same two-checkmark completion system used in the Spotify "Choose a Song" popup to the Guided Audio Picker dialog. Each guided audio in the library now shows:
- **Bright checkmark** (✓ in `TextPrimary`) — audio was used during any past apnea session
- **Grey checkmark** (✓ in `Color(0xFF888888)`) — audio was used with the current 5-setting combination (lungVolume, prepType, timeOfDay, posture, audio)
- **No checkmark** — never used

**Changes:**
- `ApneaRecordDao.kt` — Added `wasGuidedAudioUsedEver()` and `wasGuidedAudioUsedWithSettings()` queries on `apnea_records.guidedAudioName`
- `ApneaRepository.kt` — Added `wasGuidedAudioUsedEver()` and `wasGuidedAudioUsedWithSettings()` repository methods
- `GuidedAudioPicker.kt` — Added `GuidedCompletionStatus` data class, updated `GuidedAudioPickerDialog` to accept `completionStatuses: Map<Long, GuidedCompletionStatus>`, updated `GuidedAudioCard` to show two checkmarks (replacing the old single ✓ for selected item)
- All 5 UiStates updated with `guidedCompletionStatuses: Map<Long, GuidedCompletionStatus>`:
  - `FreeHoldActiveUiState`, `AdvancedApneaScreenUiState`, `ProgressiveO2UiState`, `MinBreathUiState`, `ApneaUiState`
- All 5 ViewModels updated with `loadGuidedCompletionStatuses()`:
  - `FreeHoldActiveViewModel`, `AdvancedApneaViewModel`, `ProgressiveO2ViewModel`, `MinBreathViewModel`, `ApneaViewModel`
- All 5 call sites updated to pass `completionStatuses` and trigger `LaunchedEffect` load:
  - `FreeHoldActiveScreen.kt`, `AdvancedApneaScreen.kt`, `ProgressiveO2Screen.kt`, `MinBreathScreen.kt`, `ApneaTableScreen.kt`

Build: ✅ Successful, installed on SM-S918U1

### 2026-04-11 09:37 (UTC-6)

**Guided Audio — complete rework with DB-backed library + Spotify-style picker**

Added "Guided" as a 4th audio option (Silence, Music, Movie, Guided) across the entire apnea section. The guided audio system uses a persistent DB-backed library where users can add MP3 files from their device, optionally associate a YouTube/source URL, and select one for playback during any apnea drill.

**UX Flow:**
1. User selects "Guided" in audio settings (on main ApneaScreen or any drill's settings dialog)
2. On any drill setup screen (Free Hold, O₂/CO₂ Table, Progressive O₂, Min Breath, Advanced), a "Choose a guided MP3" button appears (like Spotify's "Choose a Song")
3. Tapping opens a popup dialog showing the guided audio library
4. User can add new MP3s (file browser + optional source URL), tap to select, long-press for details (delete, copy URL)
5. Selected audio name shown in a banner above the button
6. Audio plays (looping) during the session, stops when session ends
7. Guided audio name saved to `ApneaRecordEntity.guidedAudioName` and shown in history/detail screens

**Architecture:**
- `guided_audios` DB table (v31→v32) with `audioId`, `fileName`, `uri`, `sourceUrl`
- `GuidedAudioDao` — Room DAO with Flow-based `observeAll()`, CRUD operations
- `GuidedAudioManager` — @Singleton, DB-backed library + SharedPreferences for selected ID + MediaPlayer playback
- `GuidedAudioPickerButton` — styled like `SongPickerButton`
- `GuidedAudioPickerDialog` — 3-view dialog (LIST, ADD_NEW, DETAIL) with file picker, long-press, delete, copy URL
- `SelectedGuidedAudioBanner` — shows current selection
- NOT shown on main ApneaScreen settings (only on drill setup screens, like Spotify)

New files (4):
- `GuidedAudioEntity.kt`, `GuidedAudioDao.kt`, `GuidedAudioManager.kt`, `GuidedAudioPicker.kt`

Modified files (19):
- `AudioSetting.kt` — GUIDED enum entry
- `ApneaRecordEntity.kt` — `guidedAudioName` column
- `WagsDatabase.kt` — MIGRATION_30_31 (guidedAudioName column) + MIGRATION_31_32 (guided_audios table), version 30→32
- `DatabaseModule.kt` — registered both migrations + GuidedAudioDao provider
- `ApneaSettingsSummaryBanner.kt` — GUIDED display helper
- `ApneaHistoryScreen.kt` — GUIDED display label
- `ApneaViewModel.kt` — guided state/methods, playback, record saves
- `ApneaScreen.kt` — guided picker removed from settings (only on drill screens)
- `FreeHoldActiveScreen.kt` — guided picker button+dialog+banner
- `ProgressiveO2ViewModel.kt` + `ProgressiveO2Screen.kt` — guided integration
- `MinBreathViewModel.kt` + `MinBreathScreen.kt` — guided integration
- `AdvancedApneaViewModel.kt` + `AdvancedApneaScreen.kt` — guided integration
- `ApneaTableScreen.kt` — guided picker button+dialog+banner
- `AllApneaRecordsScreen.kt` — guided audio name in record rows
- `ApneaRecordDetailScreen.kt` — guided audio name in detail view

Build: ✅ Successful, installed on SM-S918U1

### 2026-04-11 09:35 (UTC-6)

**Rework Part C: Wire New Guided Audio UI into All ViewModels + Screens**

Updated all apnea ViewModels and screens to use the new DB-backed `GuidedAudioManager` API (from Part A) and the new `GuidedAudioPickerButton`/`SelectedGuidedAudioBanner`/`GuidedAudioPickerDialog` UI components (from Part B).

Key changes across all ViewModels:
- **UI State**: Replaced `guidedAudioName: String` + `guidedAudioYoutubeUrl: String` with `guidedAudios: List<GuidedAudioEntity>` + `guidedSelectedId: Long` + `guidedSelectedName: String`
- **Init block**: Added `guidedAudioManager.allAudios` Flow collection + async `getSelectedName()` load
- **Methods**: Replaced `onGuidedFilePicked(uri, name)` / `onGuidedYoutubeUrlChanged(url)` / `clearGuidedAudio()` with `selectGuidedAudio(audio)` / `addGuidedAudio(uri, fileName, sourceUrl)` / `deleteGuidedAudio(audio)`
- **setAudio()**: Now uses async `getSelectedName()` instead of synchronous `guidedAudioManager.selectedName`
- **Playback**: All `guidedAudioManager.startPlayback()` calls now preceded by `guidedAudioManager.preparePlayback()` in a coroutine
- **Record saves**: `guidedAudioName` field now reads from `_uiState.value.guidedSelectedName` instead of `guidedAudioManager.selectedName`

Key changes across all screens:
- Replaced `GuidedAudioPicker(selectedName, youtubeUrl, onFilePicked, onYoutubeUrlChanged, onClear)` with:
  - `SelectedGuidedAudioBanner(name)` — shows selected audio name
  - `GuidedAudioPickerButton(onClick)` — opens the picker dialog
  - `GuidedAudioPickerDialog(audios, selectedId, onSelect, onAddNew, onDelete, onDismiss)` — full library dialog
- `ApneaScreen.kt`: Removed guided audio picker entirely from `ApneaSettingsContent` (settings section)

Also fixed: `MIGRATION_31_32` was accidentally nested inside `MIGRATION_30_31` in `WagsDatabase.kt` — moved it to be a sibling in the companion object.

Modified files (9):
- `ApneaViewModel.kt` — New guided state fields, new methods, preparePlayback(), async name loading
- `ApneaScreen.kt` — Removed guided params from `ApneaSettingsContent`, removed `GuidedAudioPicker` block
- `FreeHoldActiveScreen.kt` — ViewModel: new state/methods/preparePlayback; Screen: button+dialog pattern
- `ProgressiveO2ViewModel.kt` — New guided state/methods, preparePlayback, async name
- `ProgressiveO2Screen.kt` — Button+dialog pattern replacing old `GuidedAudioPicker`
- `MinBreathViewModel.kt` — New guided state/methods, preparePlayback, async name
- `MinBreathScreen.kt` — Button+dialog pattern replacing old `GuidedAudioPicker`
- `AdvancedApneaViewModel.kt` — New guided state/methods, preparePlayback, async name
- `AdvancedApneaScreen.kt` — Button+dialog pattern replacing old `GuidedAudioPicker`
- `ApneaTableScreen.kt` — Button+dialog pattern replacing old `GuidedAudioPicker`
- `WagsDatabase.kt` — Fixed `MIGRATION_31_32` nesting bug

---

### 2026-04-11 08:40 (UTC-6)

**Rework Part B: New Guided Audio UI Components — Button + Popup Dialog**

Replaced the entire contents of `GuidedAudioPicker.kt` with 3 new composables mirroring the Spotify song picker pattern from `SongPickerComponents.kt`:

1. **`GuidedAudioPickerButton`** — `Surface` + `Button` styled like `SongPickerButton`, says "🎧 Choose a guided MP3", uses `SurfaceDark`/`SurfaceVariant` colors
2. **`SelectedGuidedAudioBanner`** — Shows "🎧 {name}" with `SurfaceVariant` background, `TextPrimary` color
3. **`GuidedAudioPickerDialog`** — `AlertDialog` with 3 internal views managed by `GuidedDialogView` enum:
   - **LIST**: scrollable `LazyColumn` of `GuidedAudioCard`s with "➕ Add New MP3" button, tap-to-select (dismisses dialog), long-press for detail, selected item has checkmark + highlighted border
   - **ADD_NEW**: file picker via `ActivityResultContracts.OpenDocument` with `audio/*`, optional source URL `OutlinedTextField`, Save/Cancel buttons
   - **DETAIL**: shows file name, source URL with "Copy URL" button (via `LocalClipboardManager`), Delete + Back buttons

Also kept `resolveDisplayName` helper (changed from `private` to `internal` visibility).

Removed: old `GuidedAudioPicker` composable (inline file browser + YouTube URL field).

Modified files (1): `GuidedAudioPicker.kt`

**Note:** UI screens and ViewModels still reference the old `GuidedAudioPicker` composable — they need to be updated in a subsequent part to use the new `GuidedAudioPickerButton`/`SelectedGuidedAudioBanner`/`GuidedAudioPickerDialog` components.

---

### 2026-04-11 08:35 (UTC-6)

**Rework Part A: DB-backed Guided Audio Library + Manager Update**

Reworked the guided audio system from a single-selection SharedPreferences approach to a persistent DB-backed library (like meditation's `meditation_audios` table). This is Part A of the rework — DB layer + manager only, no UI/ViewModel changes.

Key changes:
- New `guided_audios` Room table stores ALL guided MP3s the user has ever added (fileName, uri, sourceUrl)
- `GuidedAudioManager` now reads/writes from DB via `GuidedAudioDao`, only keeps selected audio ID in SharedPreferences
- Old SharedPreferences keys (`guided_audio_uri`, `guided_audio_name`, `guided_audio_youtube_url`) removed — no migration needed, they'll just be ignored
- `selectedName` and `selectedUri` replaced by suspend functions (`getSelectedName()`, `getSelectedAudio()`) since they now need DB access
- New `preparePlayback()` suspend function caches URI before `startPlayback()` (called from ViewModel coroutine)
- Library management methods: `addAudio()`, `deleteAudio()`, `selectAudio()`, `clearSelection()`
- `allAudios: Flow<List<GuidedAudioEntity>>` for observing the full library

New files (2):
- `GuidedAudioEntity.kt` — Room entity for `guided_audios` table
- `GuidedAudioDao.kt` — DAO with observeAll, getAll, getById, insert, delete, deleteById

Modified files (3):
- `WagsDatabase.kt` — Added `GuidedAudioEntity` to entities, `guidedAudioDao()` abstract fun, `MIGRATION_31_32`, version 31→32
- `DatabaseModule.kt` — Registered `MIGRATION_31_32`, added `provideGuidedAudioDao()`
- `GuidedAudioManager.kt` — Complete rewrite: DB-backed library with `GuidedAudioDao` injection, selected ID in prefs, suspend functions for DB access, `preparePlayback()` pattern

**Note:** UI files and ViewModels are NOT updated yet — they still reference the old `selectedName`/`selectedUri`/`youtubeUrl` properties and `selectAudio(uri, name)`/`setYoutubeUrl()` methods. Part B will update those.

---

### 2026-04-10 17:00 (UTC-6)

**Guided Audio — new audio type for apnea section**

Added "Guided" as a 4th audio option (alongside Silence, Music, Movie) across the entire apnea section. When GUIDED is selected, the user can browse to and select an MP3 file on their device and optionally provide a YouTube URL. The guided audio name is stored with each apnea record and displayed in history/detail screens.

Key design decisions:
- No separate DB table for guided audio files (unlike meditation's `MeditationAudioEntity`) — just SharedPreferences for the current selection + `guidedAudioName` column on `ApneaRecordEntity`
- `GuidedAudioManager` singleton handles file selection persistence + MediaPlayer playback (looping)
- `GuidedAudioPicker` reusable composable with SAF file picker + YouTube URL field
- Pattern mirrors MUSIC/Spotify: `isGuidedMode` flag controls picker visibility, playback starts/stops with session

New files (2):
- `GuidedAudioManager.kt` — @Singleton, SharedPreferences-backed, MediaPlayer playback
- `GuidedAudioPicker.kt` — Reusable composable with OpenDocument file picker + YouTube URL field

Modified files (17):
- `AudioSetting.kt` — Added GUIDED enum entry
- `ApneaRecordEntity.kt` — Added `guidedAudioName: String?` column
- `WagsDatabase.kt` — MIGRATION_30_31, version 30→31
- `DatabaseModule.kt` — Registered MIGRATION_30_31
- `ApneaSettingsSummaryBanner.kt` — GUIDED display helper
- `ApneaHistoryScreen.kt` — GUIDED display label
- `ApneaViewModel.kt` — GuidedAudioManager injection, guided state/methods, playback start/stop, guidedAudioName in record saves
- `ApneaScreen.kt` — GuidedAudioPicker in settings section
- `FreeHoldActiveScreen.kt` — GuidedAudioManager in ViewModel, guided picker on screen, playback start/stop
- `ProgressiveO2ViewModel.kt` — GuidedAudioManager injection, guided state/methods
- `ProgressiveO2Screen.kt` — GuidedAudioPicker on setup screen
- `MinBreathViewModel.kt` — GuidedAudioManager injection, guided state/methods
- `MinBreathScreen.kt` — GuidedAudioPicker on setup screen
- `AdvancedApneaViewModel.kt` — GuidedAudioManager injection, guided state/methods
- `AdvancedApneaScreen.kt` — GuidedAudioPicker on setup screen
- `ApneaTableScreen.kt` — GuidedAudioPicker on setup screen
- `AllApneaRecordsScreen.kt` — Guided audio name in record rows
- `ApneaRecordDetailScreen.kt` — Guided audio name in detail view

---

### 2026-04-10 16:58 (UTC-6)

**Phase 3c: Guided Audio — History/Stats/AllRecords/Detail screen filters + display**

Ensured GUIDED appears correctly in all filter/display screens. Most filter chips already iterate `AudioSetting.entries`, so GUIDED appeared automatically. The main new work was showing `guidedAudioName` in record list rows and the detail screen.

Changes to 2 files:

1. **`AllApneaRecordsScreen.kt`** — Added `TextOverflow` import. In `AllRecordsRow`, added "🎧 {guidedAudioName}" text line for both table records (after duration line) and free hold records (after duration, before date). Only shown when `record.audio == "GUIDED"` and `guidedAudioName` is not null/blank. Uses `labelSmall` style, `TextSecondary` color, `maxLines = 1`, `TextOverflow.Ellipsis`.

2. **`ApneaRecordDetailScreen.kt`** — Added `TextOverflow` import. In `RecordDetailContent` Summary card, added a "Guided Audio" row after the "Audio" row. Shows `record.guidedAudioName` with `bodySmall` style, `TextPrimary` color, `maxLines = 1`, `TextOverflow.Ellipsis`, `widthIn(max = 200.dp)`. Only shown when `record.audio == "GUIDED"` and `guidedAudioName` is not null/blank.

Verified (no changes needed):
- **`ApneaHistoryScreen.kt`** — Stats filter dialog (line 297) already uses `AudioSetting.entries.forEach` ✅
- **`AllApneaRecordsViewModel.kt`** — String-based `filterAudio` with `AudioSetting.valueOf()` handles GUIDED automatically ✅
- **`ApneaRecordDetailViewModel.kt`** — `editAudio: AudioSetting` type handles GUIDED automatically ✅
- **`AllApneaRecordsScreen.kt` filter section** — Audio filter (line 304) already uses `AudioSetting.entries.forEach` ✅
- **`buildFilterSummary()`** — Uses `AudioSetting.valueOf()` which handles GUIDED automatically ✅
- **`EditRecordSheet`** — Audio chips (line 356) already use `AudioSetting.entries.forEach` ✅

---

### 2026-04-10 16:53 (UTC-6)

**Phase 3b: Guided Audio — Remaining 4 drill ViewModels + screens wired**

Wired `GuidedAudioManager` and `GuidedAudioPicker` into the 4 remaining drill ViewModels and their setup/active screens. The pattern is identical to Phase 3a (ApneaViewModel/ApneaScreen/FreeHoldActiveScreen).

Changes to 7 files:

1. **`ProgressiveO2ViewModel.kt`** — Injected `GuidedAudioManager`. Added `isGuidedMode`, `guidedAudioName`, `guidedAudioYoutubeUrl` to `ProgressiveO2UiState`. Initialized guided state in init. Updated `setAudio()` to track guided mode. Added `onGuidedFilePicked()`, `onGuidedYoutubeUrlChanged()`, `clearGuidedAudio()`. Added `guidedAudioManager.startPlayback()` in `startSession()`, `stopPlayback()` in `stopSession()` and `onCleared()`. Added `guidedAudioName` to `ApneaRecordEntity` construction.

2. **`ProgressiveO2Screen.kt`** — Added `GuidedAudioPicker` block after song picker section, visible when `state.isGuidedMode`.

3. **`MinBreathViewModel.kt`** — Same pattern as ProgressiveO2ViewModel. Injected `GuidedAudioManager`, added guided state fields, init, methods, start/stop playback, `guidedAudioName` to record entity, cleanup in `onCleared()`.

4. **`MinBreathScreen.kt`** — Added `GuidedAudioPicker` block after song picker section, visible when `state.isGuidedMode`.

5. **`AdvancedApneaViewModel.kt`** — Injected `GuidedAudioManager`. Added guided state to `AdvancedApneaScreenUiState`. Initialized from `audioSetting` local variable. Added guided methods. Start/stop playback in `startSession()`/`stopSession()`. Cleanup in `onCleared()`. (No record entity — advanced sessions save `ApneaSessionEntity` only.)

6. **`AdvancedApneaScreen.kt`** — Added `GuidedAudioPicker` block after song picker section, visible when `uiState.isGuidedMode && state.phase == AdvancedApneaPhase.IDLE`.

7. **`ApneaTableScreen.kt`** — Added `GuidedAudioPicker` block after song picker section, visible when `state.audio == AudioSetting.GUIDED && state.apneaState == ApneaState.IDLE`. Uses `ApneaViewModel` methods (`onGuidedFilePicked`, `onGuidedYoutubeUrlChanged`, `clearGuidedAudio`) which were already added in Phase 3a.

---

### 2026-04-10 16:47 (UTC-6)

**Phase 3a: Guided Audio — ViewModel + Screen wiring**

Wired the `GuidedAudioManager` and `GuidedAudioPicker` (created in Phase 2) into the main apnea ViewModel and 3 key screens. The pattern mirrors exactly how MUSIC/Spotify is handled.

Changes to 3 files:

1. **`ApneaViewModel.kt`** — Injected `GuidedAudioManager` in constructor. Added `isGuidedMode`, `guidedAudioName`, `guidedAudioYoutubeUrl` to `ApneaUiState`. Restored guided state in init block. Updated `setAudio()` to track guided mode. Added `onGuidedFilePicked()`, `onGuidedYoutubeUrlChanged()`, `clearGuidedAudio()` methods. Added `guidedAudioName` to all 3 `ApneaRecordEntity` constructions (free hold, table session, advanced session — though advanced doesn't create a record entity directly). Added `guidedAudioManager.startPlayback()` / `stopPlayback()` calls in free hold start/stop, table session start/stop, advanced session start/stop, and cancel methods. Added `guidedAudioManager.stopPlayback()` in `onCleared()`.

2. **`ApneaScreen.kt`** — Added guided audio parameters (`guidedAudioName`, `guidedAudioYoutubeUrl`, `onGuidedFilePicked`, `onGuidedYoutubeUrlChanged`, `onClearGuidedAudio`) to `ApneaSettingsContent` composable. Threaded callbacks from ViewModel through the caller. Added `GuidedAudioPicker` below the Audio filter chips, visible only when `audio == AudioSetting.GUIDED`.

3. **`FreeHoldActiveScreen.kt`** — Injected `GuidedAudioManager` in `FreeHoldActiveViewModel` constructor. Added `isGuidedMode`, `guidedAudioName`, `guidedAudioYoutubeUrl` to `FreeHoldActiveUiState`. Initialized `isGuidedMode` from audio setting. Added guided audio methods. Updated `updateAudio()` to track guided mode. Added start/stop playback calls in `startFreeHold()`, `cancelFreeHold()`, `stopFreeHold()`. Added `guidedAudioName` to `ApneaRecordEntity` construction. Added `GuidedAudioPicker` to screen composable (visible when GUIDED mode + hold not active).

4. **`FreeHoldSettingsDialog.kt`** — No changes needed (already uses `AudioSetting.entries` so GUIDED appears automatically).

---

### 2026-04-10 16:38 (UTC-6)

**Phase 2: GuidedAudioPicker composable + GuidedAudioManager**

Created the reusable UI component and audio playback manager for the "Guided" audio feature in the apnea section.

New files (2):
1. **`GuidedAudioManager.kt`** (`domain/usecase/apnea/`) — `@Singleton` with `@Inject constructor` that manages guided audio selection (persisted in `@Named("apnea_prefs") SharedPreferences`) and `MediaPlayer` playback. Keys: `guided_audio_uri`, `guided_audio_name`, `guided_audio_youtube_url`. Methods: `selectAudio()`, `setYoutubeUrl()`, `clearSelection()`, `startPlayback()`, `stopPlayback()`. Properties: `selectedName`, `selectedUri`, `youtubeUrl`, `hasSelection`, `isPlaying`.
2. **`GuidedAudioPicker.kt`** (`ui/apnea/`) — Reusable `@Composable` that shows a "Browse MP3" button (using `ActivityResultContracts.OpenDocument()` with persistable URI permissions), selected file name display, YouTube URL text field, and "Clear" button. Accepts callbacks (`onFilePicked`, `onYoutubeUrlChanged`, `onClear`) — does NOT directly access SharedPreferences or GuidedAudioManager.

No existing files modified. Not wired into any screens yet (Phase 3 will do that).

---

### 2026-04-10 16:35 (UTC-6)

**Phase 1: Add GUIDED audio type to apnea section — Foundation layer**

Added the 4th audio setting `GUIDED` to the apnea section. This is Phase 1 (data layer only — no UI screens or ViewModels modified).

Changes:
1. **`AudioSetting.kt`** — Added `GUIDED` enum entry + `displayName()` case
2. **`ApneaRecordEntity.kt`** — Added nullable `guidedAudioName: String?` column with `@ColumnInfo(defaultValue = "NULL")`
3. **`WagsDatabase.kt`** — Bumped version 30→31, added `MIGRATION_30_31` (ALTER TABLE adds `guidedAudioName TEXT DEFAULT NULL`)
4. **`DatabaseModule.kt`** — Registered `MIGRATION_30_31` in `.addMigrations()`
5. **`ApneaSettingsSummaryBanner.kt`** — Added `"GUIDED" -> "Guided"` case to `displayAudioBanner()`
6. **`ApneaHistoryScreen.kt`** — Added `"GUIDED" -> "Guided"` case to `displaySettingLabel()`

Build: ✅ Compiled and installed on device successfully

---

### 2026-04-10 15:41 (UTC-6)

**Fix O2/CO2 table duration display bug — total hold time & session time**

Bug: O2/CO2 table records stored the **longest single hold** in `durationMs` instead of the **total hold time** (sum of all hold durations). This caused incorrect values in:
- All Records list cards (showed "Longest hold" with wrong value)
- Detail screen Summary section (showed single hold as "Duration")
- Stats tab total hold time sums (summed longest holds instead of total holds)
- Time chart hold time data (plotted longest holds)

Fixes applied:

1. **`ApneaViewModel.saveCompletedSession()`** — Changed `durationMs` from `table.steps.maxOfOrNull { it.apneaDurationMs }` (longest hold) to `table.steps.sumOf { it.apneaDurationMs }` (total hold time).

2. **DB Migration v29→v30** — Backfills existing O2/CO2 records:
   - CO2: `durationMs = durationMs * totalRounds` (all holds are identical)
   - O2: `durationMs = totalSessionDurationMs - (totalRounds * 60000)` (breathing is always 60s/round)

3. **`AllApneaRecordsScreen`** — O2/CO2 cards now show "Total hold time" label (matching MIN_BREATH) instead of "Longest hold". Chart Y-axis label updated to "Total hold time".

4. **`ApneaRecordDetailScreen`** — Summary section hides "Duration" for O2/CO2 (like MIN_BREATH). Table Session section shows "Total Hold Time" + "Total Session Time" instead of "Longest Hold" + "Total Duration". HR/SpO2 charts use `totalSessionDurationMs` for X-axis (not `durationMs`).

5. **Stats tab** — No code changes needed; DAO queries sum `durationMs` which now correctly contains total hold time after migration.

6. **TimeChartViewModel** — No code changes needed; `loadHoldTimeData()` sums `durationMs` which is now correct.

Modified files (6):
- `ApneaViewModel.kt` — `saveCompletedSession()` stores total hold time
- `WagsDatabase.kt` — MIGRATION_29_30, version 29→30
- `DatabaseModule.kt` — registered MIGRATION_29_30
- `AllApneaRecordsScreen.kt` — "Total hold time" label for O2/CO2
- `AllApneaRecordsViewModel.kt` — chart Y-axis label "Total hold time"
- `ApneaRecordDetailScreen.kt` — Summary/Table Session sections, chart X-axis fix

Build: ✅ Compiled successfully (device connectivity issue prevented install)

---

### 2026-04-10 14:46 (UTC-6)

**Clickable Time Charts for Total Times section**

1. **Time Chart screen** — New landscape-forced screen (`TimeChartScreen.kt`) that shows time data over time with a toggle between:
   - **Daily bar chart** — bars showing the amount of time per day
   - **Cumulative line chart** — running total that only stays same or goes higher

2. **TimeChartViewModel** — Loads all records/sessions once, computes per-day aggregation in-memory. Supports both "hold" and "session" metric types, and per-drill-type filtering (FREE_HOLD, O2, CO2, PROGRESSIVE_O2, MIN_BREATH, TOTAL).

3. **Navigation wiring** — `WagsRoutes.TIME_CHART` route with query params `metricType`, `drillType`, `title`. Helper function `timeChart()` URL-encodes the title. Composable block registered in NavHost.

4. **Clickable rows** — All rows in the Total Times section (both Hold Time and Session Time sub-sections, including per-drill-type rows and the bold Total row) are now clickable. Tapping navigates to the TimeChartScreen with the appropriate metric type, drill type, and title.

5. **HistoryStatsRow updated** — Added optional `onClick` parameter. When provided, the row becomes clickable.

6. **DAO support** — `getAllOnce()` suspend queries added to both `ApneaRecordDao` and `ApneaSessionDao` for one-shot loading of all records/sessions. Repository methods `getAllRecordsOnce()` and `getAllSessionsOnce()` wrap these.

New files (2):
- `TimeChartViewModel.kt` — ViewModel with per-day aggregation logic
- `TimeChartScreen.kt` — Landscape chart screen with bar/line toggle

Modified files (3):
- `WagsNavGraph.kt` — Added TimeChartScreen import, timeChart() helper, composable block
- `ApneaHistoryScreen.kt` — onTimeChartClick callback threaded through StatsTabContent → ApneaStatsContent, Total Times rows now clickable with Triple-based data, HistoryStatsRow gets optional onClick
- `ApneaRecordDao.kt` — getAllOnce() query
- `ApneaSessionDao.kt` — getAllOnce() query
- `ApneaRepository.kt` — getAllRecordsOnce() method
- `ApneaSessionRepository.kt` — getAllSessionsOnce() method

Build: ✅ Successful, installed on device

---

### 2026-04-10 08:11 (UTC-6)

**Apnea Stats: Total Times section + "All" filter option for each setting**

1. **"Total Times" section on Stats tab** — New section between Activity Counts and Overall Session Extremes. Shows per-drill-type Total Hold Time and Total Session Time (Free Hold, O₂ Table, CO₂ Table, Progressive O₂, Min Breath) plus a bold "Total" row summing all drill types. Only drill types with >0 time are shown. Duration formatted as `Xh Xm Xs`.

2. **"All" filter chip for each setting** — Each setting row in the Stats filter dialog (Lung Volume, Prep, Time of Day, Posture, Audio) now has an "All" chip as the first option. Selecting "All" for a setting means "don't filter on this column" — the SQL uses `(:param = 'ALL' OR column = :param)` pattern so the WHERE clause is effectively skipped for that setting.

3. **ViewModel refactored to String-based settings** — `ApneaHistoryUiState` now stores all 5 settings as `String` (was typed enums for prepType, timeOfDay, posture, audio). `FILTER_ALL = "ALL"` sentinel constant. ViewModel setters all accept `String`. This allows mixing specific values with "All" per-setting.

4. **DAO queries updated for "ALL" support** — All filtered stats queries in `ApneaRecordDao`, `FreeHoldTelemetryDao`, and `ApneaSessionDao` now use `(:param = 'ALL' OR column = :param)` pattern instead of `column = :param`. Backward-compatible — passing a specific value works exactly as before.

5. **New DAO queries for total times** — `ApneaRecordDao`: `sumFreeHoldDuration()`, `sumHoldDurationByTableType()`, `sumFreeHoldDurationAll()`, `sumHoldDurationByTableTypeAll()`. `ApneaSessionDao`: `sumSessionDuration()` (joins with records for settings filter), `sumSessionDurationAll()`.

6. **`ApneaStats` model expanded** — 10 new fields: `freeHoldTotalHoldMs`, `o2TableTotalHoldMs`, `co2TableTotalHoldMs`, `progressiveO2TotalHoldMs`, `minBreathTotalHoldMs`, `freeHoldTotalSessionMs`, `o2TableTotalSessionMs`, `co2TableTotalSessionMs`, `progressiveO2TotalSessionMs`, `minBreathTotalSessionMs`. Plus computed `totalHoldMs` and `totalSessionMs` properties.

7. **`ApneaRepository` expanded** — Added `sessionDao: ApneaSessionDao` to constructor. Both `getStats()` and `getStatsAll()` now include groupG (hold time sums) and groupH (session time sums) in their combine chains.

Files modified (8):
- `ApneaStats.kt` — 10 new fields + 2 computed properties
- `ApneaRecordDao.kt` — 4 new SUM queries + all filtered queries updated for ALL support
- `ApneaSessionDao.kt` — 2 new SUM queries + filtered query updated for ALL support
- `FreeHoldTelemetryDao.kt` — all filtered queries updated for ALL support
- `ApneaRepository.kt` — added sessionDao, expanded getStats() and getStatsAll()
- `ApneaHistoryViewModel.kt` — all settings now String-based, FILTER_ALL constant
- `ApneaHistoryScreen.kt` — Total Times section, All chips in dialog, displaySettingLabel(), formatStatsDuration()

Build: ✅ Successful, installed on SM-S918U1

---

### 2026-04-10 07:39 (UTC-6)

**Vibration/Voice indication system standardized for apnea drills**

1. **Extra-long final countdown vibration** — `vibrateBreathingCountdownTick(isLastTick)` now accepts a boolean parameter. When `isLastTick=true` (the last of the 10 countdown vibrations, at 1s remaining), fires a 400ms high-amplitude pulse instead of the normal 80ms medium pulse. This signals "hold starts NOW". Updated in both `ApneaViewModel.onWarning()` (O₂/CO₂ tables) and `ProgressiveO2ViewModel.handlePhaseTransition()`.

2. **Persisted voice/vibration settings** — `ApneaAudioHapticEngine` now reads/writes `apnea_voice_enabled` and `apnea_vibration_enabled` booleans from `@Named("apnea_prefs") SharedPreferences`. All TTS calls gated behind `voiceEnabled`, all vibration calls gated behind `vibrationEnabled`. Safety abort calls (`vibrateAbort()`, `announceAbort()`) always fire regardless of settings.

3. **Checkmark toggles on setup screens** — New `VoiceVibrationToggles` composable (in `VoiceVibrationToggles.kt`) shows two checkboxes (🔊 Voice, 📳 Vibration) next to the Start button. Added to:
   - `ProgressiveO2Screen` — above the Start button
   - `ApneaTableScreen` — above the Start/Restart button (hidden during active session)
   - NOT added to `MinBreathScreen` (user-driven, no countdowns)

4. **Voice cutoff fix** — `announceHoldBegin()` and `announceBreath()` now use `speakWithSilencePrefix()` which queues a 500ms silent utterance before the actual word, preventing the audio system from clipping the beginning of "Hold" and "Breathe".

5. **Settings shared across all screens** — Since `ApneaAudioHapticEngine` is `@Singleton` and reads/writes directly from SharedPreferences, the voice/vibration state is consistent across Progressive O₂, O₂/CO₂ tables, and any future drill that uses the engine. Each ViewModel exposes the current values and provides setters that update both the engine and the UI state.

Files modified (7):
- `ApneaAudioHapticEngine.kt` — added `voiceEnabled`/`vibrationEnabled` properties, `isLastTick` param, `speakWithSilencePrefix()`, gating on all methods
- `ProgressiveO2ViewModel.kt` — added `voiceEnabled`/`vibrationEnabled` to UI state + setters, `isLastTick` in countdown
- `ProgressiveO2Screen.kt` — added `VoiceVibrationToggles` above Start button
- `ApneaViewModel.kt` — added `voiceEnabled`/`vibrationEnabled` to UI state + setters, `isLastTick` in `onWarning()`
- `ApneaTableScreen.kt` — added `VoiceVibrationToggles` above Start button

New file (1):
- `VoiceVibrationToggles.kt` — reusable composable with voice + vibration checkboxes

Build: ✅ Successful, installed on SM-S918U1

---

### 2026-04-10 07:11 (UTC-6)

**Tail habits integration expanded — APNEA_NEW_RECORD + Progressive O₂ + Min Breath**

1. **`APNEA_NEW_RECORD` now fires for all drill types** — Previously only `FreeHoldActiveScreen` fired `Slot.APNEA_NEW_RECORD` when a personal best was set. Now `ProgressiveO2ViewModel` and `MinBreathViewModel` also fire it whenever `checkBroaderPersonalBest()` returns a non-null result (i.e. any trophy/PB is set in any drill type).

2. **New `Slot.PROGRESSIVE_O2`** — Added to `HabitIntegrationRepository.Slot` enum. `ProgressiveO2ViewModel.saveSession()` fires it on every completed session (regardless of PB). User can map it to a Tail habit in Settings.

3. **New `Slot.MIN_BREATH`** — Added to `HabitIntegrationRepository.Slot` enum. `MinBreathViewModel.saveSession()` fires it on every completed session. User can map it to a Tail habit in Settings.

4. **Settings wired end-to-end** — `SettingsViewModel` has `progressiveO2Habit` and `minBreathHabit` fields in both `SettingsUiState` and `HabitPartialState`, with `copySlot()` cases for the new slots. `SettingsScreen.TailAppIntegrationCard` shows the two new rows (placed after Table Training, before Morning Readiness).

Files modified (5):
- `HabitIntegrationRepository.kt` — added `PROGRESSIVE_O2` and `MIN_BREATH` slots
- `ProgressiveO2ViewModel.kt` — injected `habitRepo`, fires `APNEA_NEW_RECORD` on PB + `PROGRESSIVE_O2` on every session
- `MinBreathViewModel.kt` — injected `habitRepo`, fires `APNEA_NEW_RECORD` on PB + `MIN_BREATH` on every session
- `SettingsViewModel.kt` — added `progressiveO2Habit`/`minBreathHabit` fields + `copySlot()` cases
- `SettingsScreen.kt` — added two new slots to `TailAppIntegrationCard` params and slot list

Build: ✅ Successful, installed on SM-S918U1

---


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
