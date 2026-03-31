# Wags — Professional Freediving Training Ecosystem

---

## Changelog


### 2026-03-31 — Free Hold UX improvements: clickable "last" time & song completion checkmarks

**New: "last" time is clickable in the Free Hold collapsible section** ([`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt))
- The "last: X:XX" text shown beneath the personal best in the Free Hold card on the main Apnea screen is now clickable. Tapping it navigates to the record detail screen for that hold.
- Added `getLastFreeHoldRecordId` DAO query and repository method to fetch the record ID of the most recent free hold for the current 5-setting combination.
- Added `lastFreeHoldForSettingsRecordId` to the ViewModel state with a reactive subscription that updates whenever settings change.

**New: song completion checkmarks in the Free Hold song picker** ([`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt))
- Each song card in the Free Hold song picker now shows completion checkmarks:
  - **Bright checkmark (✓)**: The user has completed a free hold whose duration ≥ the song's duration while that song was playing (any settings).
  - **Lighter grey checkmark (✓)**: The same, but specifically with the current 5-setting combination.
  - A card can show 0, 1, or 2 checkmarks depending on completion history.
- Added `wasSongCompletedEver` and `wasSongCompletedWithSettings` DAO queries that join `apnea_song_log` with `apnea_records` and check the song's actual play time (`endedAtMs - startedAtMs >= songDurationMs`). Songs still playing when the hold ended (`endedAtMs IS NULL`) are not considered completed.
- Completion status is computed when songs are loaded and stored in a `songCompletionStatus` map in the UI state.

#### Files Changed
- **Modified**: [`ApneaRecordDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaRecordDao.kt) — Added `getLastFreeHoldRecordId` Flow query
- **Modified**: [`ApneaSongLogDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaSongLogDao.kt) — Added `wasSongCompletedEver` and `wasSongCompletedWithSettings` queries
- **Modified**: [`ApneaRepository.kt`](app/src/main/java/com/example/wags/data/repository/ApneaRepository.kt) — Added `getLastFreeHoldRecordId`, `wasSongCompletedEver`, `wasSongCompletedWithSettings` methods
- **Modified**: [`ApneaViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaViewModel.kt) — Added `lastFreeHoldForSettingsRecordId` state + reactive subscription
- **Modified**: [`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt) — `FreeHoldContent` now accepts `lastTimeRecordId` + `onLastTimeClick`; "last" text is clickable
- **Modified**: [`FreeHoldActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt) — Added `SongCompletionStatus` data class, `songCompletionStatus` in UI state, completion computation in `loadPreviousSongs()`
- **Modified**: [`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt) — `SongPickerDialog` accepts `songCompletionStatus`; `SongCard` shows completion checkmarks

---

### 2026-03-30 — Apnea settings summary banner on active session screens

**New: settings summary banner on all apnea active screens** ([`ApneaSettingsSummaryBanner.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaSettingsSummaryBanner.kt))
- Added a small, centered text line just below the top app bar on every apnea active session screen showing the current settings: Lung Volume · Prep Type · Time of Day · Posture · Audio.
- Displayed on the **Free Hold** active screen, **O₂/CO₂ Table** screen, and **Advanced Apnea** screen (Progressive O₂, Min Breath, Wonka).
- Uses `labelSmall` typography in `TextSecondary` color so it's visible but unobtrusive.

#### Files Changed
- **New**: [`ApneaSettingsSummaryBanner.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaSettingsSummaryBanner.kt) — Reusable composable that formats raw setting keys into a human-readable one-liner
- **Modified**: [`FreeHoldActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt) — Added banner below top bar (settings from nav args)
- **Modified**: [`ApneaTableScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaTableScreen.kt) — Added banner as first LazyColumn item (settings from shared ViewModel)
- **Modified**: [`AdvancedApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/AdvancedApneaScreen.kt) — Added banner below top bar (settings from ViewModel)
- **Modified**: [`AdvancedApneaViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/AdvancedApneaViewModel.kt) — Exposed persisted apnea settings in `AdvancedApneaScreenUiState` for the banner

---

### 2026-03-30 — Overhaul rate recommendation scoring

**Changed: use composite score for assessments, weight assessments 3× over sessions** ([`ResonanceRateRecommender.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/ResonanceRateRecommender.kt))
- Removed the exponential decay recency weighting (14-day half-life). All data points within the 60-day lookback window are now treated equally in terms of time.
- **Assessments now use `compositeScore`** (the multi-factor epoch quality metric, 0–260) instead of `maxCoherenceRatio`. The composite score better reflects overall resonance quality since it factors in phase synchrony, PT amplitude, LF power, and RMSSD.
- **Sessions continue to use `meanCoherenceRatio`** (0–5 range), which is the only comparable metric available for sessions.
- Both are **normalized to 0–1** for fair comparison: assessments divide by 260, sessions divide by 5.
- **Assessments count 3× more** than sessions in the weighted average, since assessments are more controlled and rigorous measurements.
- Confidence multiplier now uses effective points (assessment=3 pts, session=1 pt) with a threshold of 5 effective points for full confidence.
- Formula: `finalScore = weightedAvgNormalizedScore × confidenceMultiplier`.

**Updated: Rate Recommendation screen** ([`RateRecommendationScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RateRecommendationScreen.kt))
- Bucket metrics now show "Avg Score" (normalized weighted average) instead of "Avg Coh".
- Data points show the raw value appropriate to their source: "score: X.X" for assessments, "coh: X.XX" for sessions.
- Assessment data points display "(3×)" to indicate their higher weight.

#### Files Changed
- **Modified**: [`ResonanceRateRecommender.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/ResonanceRateRecommender.kt) — New scoring algorithm with composite score, normalization, and source weighting
- **Modified**: [`RateRecommendationScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RateRecommendationScreen.kt) — Updated display for new data model fields

---

### 2026-03-30 — Fix: manual breathing rate ignored during session

**Bug fix: manually-set breathing rate overwritten by async recommendation** ([`BreathingViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt))
- When navigating from the Breathing hub to the Resonance Session screen, the `ResonanceSessionScreen` gets its own `BreathingViewModel` instance (different nav destination = different Hilt scope). The new ViewModel's `init` block launches `loadBestBreathingRate()` asynchronously, which queries the database for the recommended rate. Meanwhile, `LaunchedEffect(Unit)` calls `setBreathingRate(userRate)` then `startSession()`. The async DB query would complete *after* the manual rate was set, overwriting it back to the recommended value.
- **Fix**: Added a `rateManuallySet` flag. `setBreathingRate()` sets it to `true`, and `loadBestBreathingRate()` now skips overwriting `breathingRateBpm` when the flag is set. The `bestRateBpm` (displayed as "Suggested") is still always updated.
- This also fixes the detail screen showing the wrong rate, since the saved session reads `breathingRateBpm` from the ViewModel state.

---

### 2026-03-30 — Resonance Breathing Session Fixes

**Bug fix: sessions saved on back-out** ([`BreathingViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt), [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt))
- Previously, pressing the back arrow or confirming the "Discard session?" dialog still called `stopSession()`, which saved the session to the database. Now the back arrow and discard dialog call a new `cancelSession()` method that stops all jobs without saving. Sessions are only saved when the user taps the "End Session" button or when the timer expires naturally.
- The habit integration increment is now only sent when a session is actually saved (not on cancel).

**Bug fix: wrong breathing rate recorded in session details** ([`BreathingViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt), [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt), [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt), [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt))
- If a user had a recommended resonance rate but manually adjusted the slider, the session would record the recommended rate instead of the manually set one. Root cause: the `ResonanceSessionScreen` gets its own `BreathingViewModel` instance via `hiltViewModel()`, which re-runs `loadBestBreathingRate()` in `init`, overwriting the user's manual rate. Fix: the breathing rate is now passed as a navigation argument (`rate`) from the hub screen to the session screen, and `setBreathingRate()` is called before `startSession()`.

**Bug fix: screen blacks out after session ends** ([`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt), [`SessionGuards.kt`](app/src/main/java/com/example/wags/ui/common/SessionGuards.kt), and all session screens)
- Previously, when a timed resonance session completed, the screen auto-navigated back after only 1.5 seconds, giving no time to review results or decide what to do next. Now the `COMPLETE` phase shows a **"Session Complete" summary screen** with duration, breathing rate, and coherence stats, plus a manual "Done" button — the screen stays on indefinitely until the user taps Done.
- Extended `KeepScreenOn` to remain active during the `COMPLETE` phase across **all** session screens (Session, Meditation, Readiness, Morning Readiness, Apnea Table, Advanced Apnea, Assessment, Free Hold). Previously only the active/recording phase kept the screen on; now the post-session review phase does too.

**UI: replaced "points earned" with "mean coherence"** ([`ResonanceSessionDetailScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailScreen.kt), [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt), [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt), [`RfAssessmentHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RfAssessmentHistoryScreen.kt))
- The "points earned" gamification metric was not meaningful to users. Replaced the hero display on the session detail screen and session-complete screen with "mean coherence". The live session stats row now shows "BPM" (breathing rate) instead of "POINTS". Removed the "Session Points" chart from the history screen. Session event cards in the history now show RMSSD instead of points.

**UI: "End Session" button** ([`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt))
- Renamed "Stop & Save Session" to "End Session" for clarity.

**Verified: delete button placement** ([`ResonanceSessionDetailScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailScreen.kt))
- The delete icon is already correctly placed in the top-right corner of the session detail screen's top app bar. No bottom delete button exists.

#### Files Changed
- **Modified**: [`BreathingViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt) — Added `cancelSession()`, moved habit increment inside save-only path
- **Modified**: [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt) — Back/cancel uses `cancelSession()`, added `breathingRate` param, replaced auto-navigate with "Session Complete" summary screen + manual Done button, replaced POINTS with BPM in stats row, renamed button
- **Modified**: [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt) — Pass rate in `onNavigateToSession`, replaced "points earned" with "mean coherence" in session-complete hero
- **Modified**: [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt) — Added `rate` nav argument to resonance session route
- **Modified**: [`ResonanceSessionDetailScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailScreen.kt) — Replaced "points earned" hero with "mean coherence"
- **Modified**: [`RfAssessmentHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RfAssessmentHistoryScreen.kt) — Removed "Session Points" chart, event cards show RMSSD instead of points
- **Modified**: [`SessionScreen.kt`](app/src/main/java/com/example/wags/ui/session/SessionScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`MeditationScreen.kt`](app/src/main/java/com/example/wags/ui/meditation/MeditationScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`ReadinessScreen.kt`](app/src/main/java/com/example/wags/ui/readiness/ReadinessScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`MorningReadinessScreen.kt`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`ApneaTableScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaTableScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`AdvancedApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/AdvancedApneaScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt) — `KeepScreenOn` now covers COMPLETE phase
- **Modified**: [`FreeHoldActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt) — `KeepScreenOn` now covers PB check and PB dialog phases

---

### 2026-03-30 — Smart Resonance Breathing Rate Recommendation

**Improved: intelligent breathing rate recommendation** ([`ResonanceRateRecommender.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/ResonanceRateRecommender.kt))
- Replaced the simple "highest average coherence" rate picker with a smarter algorithm that uses **recency-weighted scoring** (exponential decay, 14-day half-life) and a **confidence multiplier** (requires ≥3 data points per rate bucket for full confidence).
- The algorithm collects all valid RF assessments and resonance sessions from the last 60 days, groups them into 0.1 BPM buckets (rounded to nearest), and scores each bucket as `weightedAvgCoherence × confidenceMultiplier`.
- The old logic had a rounding bug (`(rateBpm * 10).toInt() / 10f` rounds down instead of to nearest) and no weighting by recency or data count.

**New: Rate Recommendation Explanation screen** ([`RateRecommendationScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RateRecommendationScreen.kt), [`RateRecommendationViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/RateRecommendationViewModel.kt))
- A dedicated screen showing full transparency into the recommendation: the recommended rate, data summary (lookback window, assessment/session counts), algorithm explanation, and a ranked table of all rate buckets with their raw average, weighted average, confidence multiplier, final score, and individual data points (with source, coherence, recency weight, and date).
- Accessible via a "Why?" button on the Breathing hub screen next to the suggested rate display.

**UI: suggested rate card on Breathing hub** ([`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt))
- When a recommendation is available, a card below the breathing controls shows "Suggested: X.XX BPM — Based on 60-day history" with a "Why?" link to the explanation screen.

#### Files Changed
- **New**: [`ResonanceRateRecommender.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/ResonanceRateRecommender.kt), [`RateRecommendationScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RateRecommendationScreen.kt), [`RateRecommendationViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/RateRecommendationViewModel.kt)
- **Modified**: [`BreathingViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt), [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt), [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt)

---

### 2026-03-29 — Strip Chart Initial Batch Fix

**Bug fix: first ~7-8 RR points bunched at time zero on scrolling chart** ([`RrStripChart.kt`](app/src/main/java/com/example/wags/ui/common/RrStripChart.kt))
- When the Polar sensor delivers its first batch of buffered RR intervals, `RrStripChartState.ingest()` and `RmssdStripChartState.ingest()` set `firstBeatWallMs` to `System.currentTimeMillis()` and then immediately compute `wallTimeMs = wallNow − firstBeatWallMs ≈ 0`. The spreading formula `lastBeatTime + fraction * (wallTimeMs − lastBeatTime)` collapses to 0 for every beat, producing a vertical line at the left edge.
- Fix: on the first batch, back-date `firstBeatWallMs` by the total duration of the batch's RR intervals, then place each beat at its cumulative RR offset from time 0. Subsequent batches continue using the existing wall-clock spreading logic.
- Affects all screens using the shared strip chart: Morning Readiness, Resonance Breathing sessions, RF Assessments, and general Breathing sessions.

#### Files Changed
- **Modified**: [`RrStripChart.kt`](app/src/main/java/com/example/wags/ui/common/RrStripChart.kt)

---

### 2026-03-29 — Resonance Breathing Fixes & Calendar Navigation

**Bug fix: no vibration at end of exhale** ([`WagsFeedback.kt`](app/src/main/java/com/example/wags/ui/common/WagsFeedback.kt))
- The double vibration at the end of exhale (start of inhale) was not being felt because `createWaveform` was used without explicit amplitudes, defaulting to device-minimum amplitude. Both breath transition patterns now use `createWaveform` with explicit amplitude arrays (180/255) for reliable haptic feedback.
- Inhale transition (end of exhale): single 80ms pulse. Exhale transition (end of inhale): double 80ms pulses with 80ms gap.
- Fix applies to both normal resonance sessions and RF assessments.

**Bug fix: screen blacks out after 10-minute session** ([`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt))
- The `KeepScreenOn` flag was only active during PREPARING/BREATHING phases. When the timer expired and the phase changed to COMPLETE, the flag was removed before navigation could complete, causing the screen to turn off immediately. Now `KeepScreenOn` stays active during the COMPLETE phase as well.

**Removed redundant coherence zone card** ([`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt), [`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt))
- Removed the full-width "coherence zone indicator" card from both normal sessions and assessments. The coherence ratio is already displayed in the stats row directly below, making the card redundant.

**Calendar: clickable event cards for sessions and assessments** ([`RfAssessmentHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RfAssessmentHistoryScreen.kt), [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt))
- When a calendar day has exactly 1 event (assessment or session), tapping it auto-navigates to the detail screen.
- When a day has multiple events, a combined list of clickable cards is shown — each card displays the event type (Assessment/Session), time, key metrics, and navigates to the appropriate detail screen on tap.
- Cards are visually distinguished: assessments have a grey dot indicator, sessions have a cyan dot indicator.

**New: Resonance Session Detail Screen** ([`ResonanceSessionDetailScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailScreen.kt), [`ResonanceSessionDetailViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailViewModel.kt))
- New detail screen for normal resonance breathing sessions showing: points earned, date/time, breathing rate, duration, coherence zone breakdown (high/medium/low bar chart), session metrics (mean/max coherence, RMSSD, SDNN, beats, artifact %), coherence-over-time chart, and delete option.

#### Files Changed
- **New**: [`ResonanceSessionDetailScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailScreen.kt), [`ResonanceSessionDetailViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionDetailViewModel.kt)
- **Modified**: [`WagsFeedback.kt`](app/src/main/java/com/example/wags/ui/common/WagsFeedback.kt), [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt), [`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt), [`RfAssessmentHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RfAssessmentHistoryScreen.kt), [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt), [`ResonanceSessionDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ResonanceSessionDao.kt), [`ResonanceSessionRepository.kt`](app/src/main/java/com/example/wags/data/repository/ResonanceSessionRepository.kt)

---
### 2026-03-28 — BLE device connectivity bug fixes

**Bug fix: crash when switching between device types** ([`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt), [`AutoConnectManager.kt`](app/src/main/java/com/example/wags/data/ble/AutoConnectManager.kt), [`UnifiedDeviceManager.kt`](app/src/main/java/com/example/wags/data/ble/UnifiedDeviceManager.kt))
- The app would crash when connected to a Polar H10, turning it off, and then auto-connecting to a different device type (OxySmart, O2Ring). Root cause: the Polar SDK's `connectToDevice()` is fire-and-forget and keeps retrying in the background even after the auto-connect loop moves on to try generic devices. This caused BLE stack conflicts and stale callbacks.
- `PolarBleManager` now tracks `lastRequestedDeviceId` so lingering SDK connection attempts can be explicitly cancelled via `disconnectFromDevice()`.
- `PolarBleManager.disconnect()` now cancels stale SDK connection attempts even when the state is already `Disconnected` (the SDK may still be trying internally).
- `AutoConnectManager.connectPolarNow()` now explicitly disconnects the Polar SDK when the device doesn't connect within the settle window, preventing background retry interference.
- `AutoConnectManager.connectGenericNow()` ensures no stale Polar SDK connection attempt is running before attempting a generic BLE connection.
- `UnifiedDeviceManager.connect()` now unconditionally disconnects both backends before connecting a new device (previously only disconnected if state was Connected/Connecting, missing the case where the SDK was still retrying internally).

**Bug fix: Polar H10 shows connected but no HR data streaming** ([`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt))
- HR streaming was started in the `deviceConnected` callback, but the Polar SDK's HR feature may not be ready at that point. The stream would silently fail or complete immediately, leaving `liveHr` stuck at null despite the Connected state.
- HR streaming is now started from the `bleSdkFeatureReady(FEATURE_HR)` callback, which fires only when the SDK is actually ready to stream HR data.
- Added retry logic: if the HR stream completes or errors while the device is still connected, it retries up to 5 times with a 2-second delay between attempts. The retry counter resets on successful data receipt.

**Bug fix: Polar H10 connecting via generic GATT instead of Polar SDK** ([`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt))
- The Polar H10's MAC address could end up in the device history as a non-Polar device (e.g. from a previous generic BLE scan). The auto-connect loop would then connect it via raw GATT instead of the Polar SDK, resulting in standard BLE HR packets (`0x10` header) that were not parsed — HR data was received but silently discarded.
- `GenericBleManager.onServicesDiscovered()` now detects Polar devices by name and immediately disconnects, letting the Polar SDK handle them on the next auto-connect cycle.
- Added standard BLE Heart Rate Measurement packet parsing (`handleStandardHrPacket`) as a safety net — any device sending standard HR packets (UUID 0x2A37) now has its HR data correctly extracted and displayed. This also enables support for generic HR straps that aren't Polar or oximeter devices.

#### Files Changed
- [`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt) — `bleSdkFeatureReady` callback, `lastRequestedDeviceId` tracking, HR stream retry logic, enhanced `disconnect()`
- [`AutoConnectManager.kt`](app/src/main/java/com/example/wags/data/ble/AutoConnectManager.kt) — Cancel stale Polar SDK attempts in `connectPolarNow()` and `connectGenericNow()`
- [`UnifiedDeviceManager.kt`](app/src/main/java/com/example/wags/data/ble/UnifiedDeviceManager.kt) — Unconditional backend disconnect in `connect()`
- [`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt) — Reject Polar devices in `onServicesDiscovered`, standard BLE HR packet parsing

---

### 2026-03-28 — Resonance Breathing Enhancements

#### New Features
- **Auto-detect best breathing rate**: The breathing rate slider is now automatically set to the user's optimal rate, determined by analyzing coherence scores from the last 2 months of assessments and normal sessions. The rate with the highest average coherence is selected.
- **Session duration timer**: Added a 1–30 minute duration slider for normal resonance breathing sessions. An ∞ (infinity) toggle disables the timer for open-ended sessions.
- **Normal session persistence**: Normal resonance breathing sessions are now saved to the database with full metrics (coherence, HRV, points, duration, etc.).
- **Session history integration**: Normal sessions now appear in the Resonance Breathing History calendar (with cyan dots, distinct from grey assessment dots) and have their own dedicated graphs section (coherence, RMSSD, SDNN, points, duration over time).
- **Distinct vibration patterns**: Inhale transitions use a rapid double vibration, exhale transitions use a rapid single vibration, and "breathe naturally" phases in assessments trigger a longer vibration.

#### Improvements
- **Removed redundant coherence card** from the main Resonance Breathing screen (coherence is shown during active sessions).
- **Stop & Save behavior**: Stopping a normal session (even early) now always saves the session data. In infinity mode, the Stop & Save button is the only way to end the session.
- **Timer auto-stop**: When the duration timer expires, the session automatically stops, saves, and plays the end-session chime.
- **Screen stays on**: Screen wake lock is active during both normal sessions and assessments (was already implemented, verified).

#### Database
- Added `resonance_sessions` table (DB version 22) for persisting normal breathing sessions.
- Added `getSince()` query to [`RfAssessmentDao`](app/src/main/java/com/example/wags/data/db/dao/RfAssessmentDao.kt) for time-range queries.

#### Files Changed
- **New**: [`ResonanceSessionEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/ResonanceSessionEntity.kt), [`ResonanceSessionDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ResonanceSessionDao.kt), [`ResonanceSessionRepository.kt`](app/src/main/java/com/example/wags/data/repository/ResonanceSessionRepository.kt)
- **Modified**: [`WagsFeedback.kt`](app/src/main/java/com/example/wags/ui/common/WagsFeedback.kt), [`BreathingPacerCircle.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingPacerCircle.kt), [`BreathingViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt), [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt), [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt), [`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt), [`RfAssessmentHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/RfAssessmentHistoryScreen.kt), [`RfAssessmentHistoryViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/RfAssessmentHistoryViewModel.kt), [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt), [`WagsDatabase.kt`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt), [`DatabaseModule.kt`](app/src/main/java/com/example/wags/di/DatabaseModule.kt), [`RfAssessmentDao.kt`](app/src/main/java/com/example/wags/data/db/dao/RfAssessmentDao.kt), [`RfAssessmentRepository.kt`](app/src/main/java/com/example/wags/data/repository/RfAssessmentRepository.kt)

---

### 2026-03-27 — Device connection bug fixes + Wellue O2Ring support

**Bug fix: crash when disconnecting from a turned-off device** ([`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt), [`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt), [`UnifiedDeviceManager.kt`](app/src/main/java/com/example/wags/data/ble/UnifiedDeviceManager.kt))
- `GenericBleManager.disconnect()` now wraps the GATT disconnect call in try/catch. When the device is already gone (turned off, out of range), it force-cleans the state to `Disconnected` instead of leaving the UI stuck in `Connected` or `Error`.
- `PolarBleManager.disconnectDevice()` catches all exceptions (not just `PolarInvalidArgument`) and forces state to `Disconnected` when the Polar SDK throws because the device is unreachable.
- `PolarBleManager.disconnect()` now handles the `Error` state — previously it only disconnected from `Connected` or `Connecting`, leaving the UI stuck if the device had already errored out.
- `UnifiedDeviceManager.disconnect()` no longer requires the state to be `Connected` — it now unconditionally tells both backends to disconnect, which is safe even when already disconnected.
- `GenericBleManager` GATT callback `onConnectionStateChange` now always transitions to `Disconnected` on disconnect (previously set `Error` for non-success status codes, which prevented the user from reconnecting).

**Bug fix: crash when switching devices without restarting** ([`UnifiedDeviceManager.kt`](app/src/main/java/com/example/wags/data/ble/UnifiedDeviceManager.kt), [`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt), [`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt))
- `UnifiedDeviceManager.connect()` now disconnects both backends before connecting to a new device, preventing stale GATT references and BLE stack conflicts.
- `PolarBleManager.connectDevice()` disconnects the old device first when switching to a different device ID.
- `GenericBleManager` GATT callback now checks for stale GATT instances on connect (not just disconnect), closing them immediately to prevent race conditions.
- `gatt.close()` moved inside the `gattLock` synchronized block to eliminate a race where the old GATT's disconnect callback could null out `bluetoothGatt` right after a new connection set it.

**Wellue O2Ring support** ([`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt), [`DeviceType.kt`](app/src/main/java/com/example/wags/domain/model/DeviceType.kt))
- Added Wellue O2Ring as a recognized oximeter device. It maps to `DeviceType.OXIMETER` with HR + SpO2 capabilities, so it works everywhere the OxySmart oximeter works (apnea holds, SpO2 safety monitor, live HR display, etc.).
- `DeviceType.fromName()` now matches "O2Ring" in addition to existing oximeter name patterns.
- `GenericBleManager` data handler now recognizes two distinct packet protocols:
  - **OxySmart/PC-60F** — Nordic UART Service packets starting with `AA 55` (existing)
  - **Wellue O2Ring/Viatom** — Proprietary GATT service (`14839ac4-...`) with packets starting with `AA <cmd>` and a CRC-8 checksum
- O2Ring live data is obtained by periodically sending command `0x17` to the device's write characteristic. A background polling coroutine starts automatically after connection and stops on disconnect.
- Full CRC-8 implementation for building valid Viatom protocol packets.
- O2Ring service UUID (`14839ac4-7d7e-415c-9a42-167340cf2339`), write UUID (`8b00ace7-...`), and notify UUID (`0734594a-...`) are auto-discovered by the existing notify-queue mechanism.

---

### 2026-03-26 — Apnea History screen: All Records tab, unfiltered calendar, Stats settings popup, "History" button

**All Records tab** ([`ApneaHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryScreen.kt))
- The All Records tab now renders `AllApneaRecordsScreen` directly (with full paging, chart, filters) instead of a filtered list. No wrapper — the full screen is embedded in the tab.

**Calendar tab shows all sessions** ([`ApneaRecordDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaRecordDao.kt), [`ApneaRepository.kt`](app/src/main/java/com/example/wags/data/repository/ApneaRepository.kt), [`ApneaHistoryViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryViewModel.kt))
- Added `observeAll()` DAO query and `getAllRecords()` repository method to stream every apnea record regardless of settings.
- Calendar tab now uses `allDatesWithRecords` / `allRecordsByDate` (all sessions, not filtered by the current 5-setting combination). Tapping a day still auto-navigates for single sessions and shows a picker list for multiple sessions.

**Stats tab: audio in settings label + settings popup** ([`ApneaHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryScreen.kt), [`ApneaHistoryViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryViewModel.kt))
- The current-settings label now includes the audio setting (was missing before).
- Tapping the settings label opens a `StatsSettingsDialog` — an `AlertDialog` with `FilterChip` rows for all 5 settings (Lung Volume, Prep, Time of Day, Posture, Audio). Changes take effect immediately and update the stats reactively via `flatMapLatest`.
- Settings in the ViewModel are now `MutableStateFlow` (initialized from nav args) so they can be changed from within the History screen.

**"History" text button** ([`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt))
- Replaced the 📋 `IconButton` in the apnea top bar with a `TextButton` showing the word "History" in `TextSecondary` colour.

---

### 2026-03-26 — Apnea section: celebration trophies, Free Hold card, History screen with 3 tabs

**Celebration dialog trophies** ([`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt))
- `NewPersonalBestDialog` now calls `category.trophyEmojis()` to display the correct number of trophy emojis matching the breadth of the personal best (1 trophy for exact-settings PB up to 6 for all-time global PB), consistent with the trophy display on the Free Hold card.

**"Best Time" renamed to "Free Hold" + last-hold time** ([`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt), [`ApneaViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaViewModel.kt), [`ApneaRecordDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaRecordDao.kt), [`ApneaRepository.kt`](app/src/main/java/com/example/wags/data/repository/ApneaRepository.kt))
- The "Best Time" accordion card is renamed to "Free Hold".
- A new `getLastFreeHold()` DAO query returns the most recent free-hold duration for the current 5-setting combination.
- `ApneaUiState` gains `lastFreeHoldForSettingsMs` (updated reactively when settings change).
- `FreeHoldContent` now shows a smaller "last: X" time beneath the personal best — using the in-memory last hold if one was just completed, otherwise the DB value.

**History screen with 3 tabs** ([`ApneaHistoryScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryScreen.kt), [`ApneaHistoryViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryViewModel.kt), [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt))
- "Recent Records" and "Stats" accordion sections removed from the main apnea screen.
- A 📋 icon button in the top-right of the apnea top bar navigates to the new History screen, passing all 5 current settings (including audio) as nav args.
- `APNEA_HISTORY` route updated to include the `audio` path segment; `apneaHistory()` helper accepts `audio` with default `"SILENCE"`.
- **All Records tab**: settings filter badge, "All Records" button (→ `AllApneaRecordsScreen`), personal best card, scrollable list of free-hold records.
- **Stats tab**: toggle between current-settings and all-settings stats; full activity counts and session extremes.
- **Calendar tab**: month calendar with dots on days that have records; tapping a day with 1 session navigates directly to its detail screen (`LaunchedEffect` auto-navigate); tapping a day with multiple sessions shows a dismissible list of `ApneaSessionSummaryCard` items.
- `ApneaHistoryViewModel` uses nested `combine()` (two groups of 3 flows each) to merge 6 reactive flows into a single `StateFlow<ApneaHistoryUiState>`.

---

### 2026-03-26 — Fix: first-time Spotify song selection in apnea free hold

**Cold-start device resolution** ([`SpotifyApiClient.kt`](app/src/main/java/com/example/wags/data/spotify/SpotifyApiClient.kt))
- Fixed a bug where selecting a song via the picker failed on the first attempt after opening Spotify (i.e. when Spotify had not yet played any audio in the current session).
- **Root cause:** The Spotify Web API `PUT /me/player/play` endpoint requires an "active device". When Spotify is freshly launched but hasn't played anything yet, no device is registered as active, so the API returns a 404 error. The song selection appeared to succeed (no user-visible error) but Spotify silently ignored the track URI. On subsequent attempts it worked because the first failed call + `sendPlayCommand()` fallback activated a device.
- **Fix:** When `startPlayback()` receives a 404, it now fetches the available device list via `GET /me/player/devices`, picks the first smartphone device (falling back to any available device), and retries the play command with an explicit `device_id` parameter. This resolves the cold-start race condition without requiring any UI changes.
- The existing OAuth scopes already include `user-read-playback-state`, so no re-authentication is needed.

---

### 2026-03-26 — Song picker improvements: dedup, sorting, album art, reliable pre-loading

**Duplicate song prevention** ([`ApneaSongLogDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaSongLogDao.kt), [`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt), all 3 ViewModels)
- DB query now groups by `LOWER(TRIM(title)) || '|' || LOWER(TRIM(artist))` instead of `COALESCE(spotifyUri, ...)` — prevents the same song appearing twice when stored with different Spotify URIs.
- `mergeSongs()` in all ViewModels now deduplicates by case-insensitive title+artist as a primary key (previously only checked URI).
- New `deduplicateTracks()` utility applied after API enrichment to catch any remaining duplicates from the Spotify Web API resolution step.
- `persistSongHistory()` also uses case-insensitive title+artist matching.

**Sort buttons** ([`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt))
- Added a `FilterChip` row at the top of the song picker dialog with four sort options: Recent, Title, Artist, Length.
- Tapping the active chip toggles ascending/descending (arrow indicator ↑/↓ shown on the active chip).
- Default: Recent (most recently played first).

**Album art on song cards** ([`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt), [`build.gradle.kts`](app/build.gradle.kts))
- Added Coil (`io.coil-kt:coil-compose:2.6.0`) dependency for async image loading.
- Song cards now display the album art thumbnail (36×36 dp, rounded corners) when available; falls back to the 🎵 emoji when no art URL is present.

**Smaller, more compact song cards** ([`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt))
- Reduced card padding from 12 dp to 8 dp, icon/art size from 40 dp to 36 dp, and card spacing from 8 dp to 6 dp.
- Text styles downgraded from `bodyMedium`/`bodySmall` to `bodySmall`/`labelSmall` for a tighter layout.

**Reliable song pre-loading** ([`FreeHoldActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt), [`ApneaViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaViewModel.kt), [`AdvancedApneaViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/AdvancedApneaViewModel.kt), [`SpotifyManager.kt`](app/src/main/java/com/example/wags/data/spotify/SpotifyManager.kt))
- New `ensureSpotifyActive()` in `SpotifyManager` — if Spotify is not running, automatically launches it via its package intent and polls up to 3 s for its MediaSession to appear before proceeding.
- `selectSong()` calls `ensureSpotifyActive()` first, then starts playback of the selected track via the Spotify Web API, waits 1.2 s for Spotify to buffer, then pauses and rewinds. This ensures the track is fully loaded and ready even when Spotify was not open.
- `startFreeHold()` / `startTableSession()` / `startSession()` now simply call `sendPlayCommand()` to resume the already-buffered track, eliminating the ~500 ms API latency that previously caused intermittent failures.
- A loading spinner is shown on the selected card while the pre-load is in progress.

---

### 2026-03-26 — Resonance breathing improvements: end sound, vibration toggle, white flash, fine-tune granularity

**End-of-session sound for RF assessments** ([`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt))
- Assessment sessions now play the same `chime_end` sound + haptic pattern as morning readiness and HRV readiness sessions when they complete.
- Uses the existing [`WagsFeedback.sessionEnd()`](app/src/main/java/com/example/wags/ui/common/WagsFeedback.kt) helper.

**Breath transition vibration toggle** ([`WagsFeedback.kt`](app/src/main/java/com/example/wags/ui/common/WagsFeedback.kt), [`BreathingPacerCircle.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingPacerCircle.kt))
- New `WagsFeedback.breathTransition()` fires a short 80 ms haptic pulse at medium amplitude.
- A vibration toggle button (〰 icon) is placed next to the "Start Session" button on [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt) and next to the "Start Assessment" button on [`AssessmentPickerScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentPickerScreen.kt).
- When enabled, the phone vibrates at the exact moment the pacer transitions between inhale and exhale phases.
- Vibration state is passed through navigation to [`ResonanceSessionScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt) and [`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt).
- `BreathingPacerCircle` now accepts an `onPhaseTransition` callback that fires exactly once per `isInhaling` change.

**White flash at peak inhale** ([`BreathingPacerCircle.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingPacerCircle.kt))
- When the inhale circle reaches ≥ 95% of its maximum size, the circle color smoothly transitions to white (`Color.White`) to visually signal the upcoming exhale transition.
- Text color switches to dark (`BackgroundDark`) on the white circle for contrast, maintaining the greyscale aesthetic.

**Targeted assessment finer granularity** ([`RfAssessmentOrchestrator.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/RfAssessmentOrchestrator.kt), [`AssessmentPickerScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentPickerScreen.kt))
- The Targeted (fine-tune) protocol now tests at `optimal ± 0.1 BPM` instead of `± 0.2 BPM` for finer granularity.
- Updated the protocol description in the assessment picker to reflect the new 0.1 BPM step size.

---

### 2026-03-26 — Apnea section improvements: persistent settings, global PB sound, universal song picker

**Persistent settings across app restarts** (`ApneaViewModel.kt`)
- All apnea settings (Lung Volume, Prep Type, Posture, Audio, Show Timer, Table Length, Table Difficulty) are now saved to `SharedPreferences` (`apnea_prefs`) and restored on next launch.
- **Time of Day** is intentionally excluded — it continues to be smart-set from the current time on every launch.
- Settings are saved immediately on each change via the existing setter functions.

**Global best PB sound** (`ApneaPbSoundPlayer.kt`)
- `apnea_pb6.mp3` is now wired up for the `GLOBAL` personal best category (all-time best across all settings).
- Previously `GLOBAL` reused `apnea_pb5.mp3`; now each of the 6 PB levels has its own distinct sound.

**Persistent song history** (`FreeHoldActiveScreen.kt`, `ApneaViewModel.kt`, `AdvancedApneaViewModel.kt`)
- Songs played during any apnea session are now persisted to `SharedPreferences` (`song_history` key) in addition to the existing DB song log.
- The song picker now merges DB records (free holds) with the SharedPreferences history so songs from table and advanced sessions are also remembered.
- Song history survives app restarts and is shared across all session types.
- Up to 50 unique songs are retained (most recent first), deduplicated by Spotify URI or title+artist.

**"Choose a Song" in all session types** (`ApneaTableScreen.kt`, `AdvancedApneaScreen.kt`, `AdvancedApneaViewModel.kt`)
- The song picker button and dialog are now available in O2/CO2 table sessions (`ApneaTableScreen`) and all advanced modality sessions (`AdvancedApneaScreen`).
- Shown only when Audio setting is MUSIC and Spotify is connected, before the session starts.
- Selected song is queued and played via Spotify Web API when the session starts; falls back to generic play if no URI.
- Spotify tracking starts/stops with table and advanced sessions, and played tracks are persisted to song history.

---

### 2026-03-26 — Greyscale emoji filter via ColorMatrix
Added [`GrayscaleEmoji.kt`](app/src/main/java/com/example/wags/ui/common/GrayscaleEmoji.kt) with a reusable `grayscale()` Modifier extension that applies a zero-saturation `ColorMatrix` to strip colour from emoji glyphs. Applied to all colour emoji `Text()` calls across the app:
- `ApneaScreen.kt` — 📊, 🌍/⭐/🏆 (PB dialog), 🎉, 🎵, 🏆 (trophy row), 📋
- `ApneaHistoryScreen.kt` — 🏆 Personal Best banner
- `ApneaRecordDetailScreen.kt` — 🎵 Songs Played header
- `SongPickerComponents.kt` — all three 🎵 instances
- `PersonalBestsScreen.kt` — 🏆 section headers
- `MeditationScreen.kt` — 📊 history button

---

### 2026-03-26 — Full greyscale UI conversion (final sweep)
Follow-up pass fixing remaining colored references missed in the initial conversion:
- `ApneaRecordDetailScreen.kt` — `SpO2Blue` (`0xFF42A5F5`) → `0xFFB0B0B0`; canvas label `Color.White` → `TextSecondary`
- `ApneaScreen.kt` — scrim overlay `Color.Black` → `BackgroundDark`
- `MeditationSessionDetailScreen.kt` — canvas grid/label `Color.White` → `TextDisabled`/`TextSecondary`
- `MorningReadinessScreen.kt` — `SkipButtonBg` dark red `0xFF8B0000` → `0xFF333333`
- `ContractionOverlay.kt` — contraction count text `0xFFFF6B35` (orange) → `TextSecondary`
- `BreathingPacerCircle.kt` — pacer text `Color.White` → `TextPrimary`
- `Theme.kt` — `onPrimary`/`onSecondary`/`onError` `Color.White` → `TextPrimary`

Final regex sweep confirmed **0 remaining** `Color.White`, `Color.Black`, `Color.Gray`, or colored `Color(0xFF...)` references across all UI files.

### 2026-03-26 — Full greyscale UI conversion
Converted the entire app UI to black-and-white greyscale monochrome. All colored elements (buttons, text, cards, charts, emojis, indicators, progress bars, canvas drawings) now use the greyscale palette defined in [`Color.kt`](app/src/main/java/com/example/wags/ui/theme/Color.kt).

**Files updated:**
- `ui/theme/Color.kt` — Complete greyscale palette rewrite; all named colors remapped to grey values
- `ui/theme/Theme.kt` — Updated Material3 color scheme references
- All 35+ UI screen/component files — Replaced `Color.White`, `Color.Black`, `Color.Gray`, `Color(0xFF1DB954)` (Spotify green), `Color(0xFF0D2818)` (dark green), and all inline colored hex literals with greyscale theme tokens (`TextPrimary`, `TextSecondary`, `TextDisabled`, `SurfaceVariant`, `SurfaceDark`, etc.)

**Greyscale palette:**
- Background: `0xFF0A0A0A` (near-black)
- Surface Dark: `0xFF141414`
- Surface Variant: `0xFF242424`
- Text Primary: `0xFFE8E8E8` (near-white)
- Text Secondary: `0xFFB0B0B0` (mid-light grey)
- Text Disabled: `0xFF606060` (dim grey)
- Accent (EcgCyan): `0xFFD0D0D0` (light grey)

---

## Overview

Wags is a professional-grade Android freediving training app built with Kotlin and Jetpack Compose. It combines HRV-based readiness assessment, structured apnea table training, unified BLE-connected biometric monitoring (any BLE device — Polar H10/Verity Sense, Wellue O2Ring, Wellue/Viatom oximeters, generic HR sensors — auto-detected by name), and real-time safety monitoring into a single cohesive training ecosystem.

The app is designed for competitive freedivers and serious recreational practitioners who want data-driven training, not just a stopwatch.

---

## Features

- **Morning Readiness Protocol** — Full 5-phase ANS assessment: supine HRV → stand → orthostatic response → 30:15 ratio → respiratory rate scoring
- **HRV Readiness Screen** — Quick standalone HRV measurement with ln(RMSSD), SDNN, and readiness score
- **Resonance Breathing** — Coherence biofeedback pacer with real-time RF assessment and coherence scoring
- **CO₂ Table Training** — Structured CO₂ tolerance tables with configurable Session Length (4/8/12 rounds) and Difficulty (Easy/Medium/Hard)
- **O₂ Table Training** — Progressive O₂ hypoxic training tables scaled to personal best
- **Progressive O₂ Training** — Advanced modality with incremental hold targets
- **Minimum Breath Training** — Breath-packing and minimal ventilation protocol
- **Wonka First Contraction** — Contraction-triggered hold termination with cruising/struggle phase tracking
- **Wonka Endurance** — Post-contraction endurance extension protocol
- **Free Hold Mode** — Stopwatch-style free breath-hold with Polar HR monitoring
- **Contraction Tracking** — Double-tap or volume-button logging during holds; cruising/struggle phase analytics
- **Session Analytics** — Per-session contraction delta bar chart and historical hypoxic resistance scatter plot
- **Unified BLE Integration** — Single scan/connect flow for any BLE device; device type auto-detected from advertised name (Polar H10, Verity Sense, Wellue O2Ring, Wellue OxySmart, Viatom PC-60F, generic HR sensors); capabilities (HR, RR, ECG, ACC, PPI, SpO₂) determined per device type
- **SpO₂ Safety Monitor** — Configurable critical threshold (70–95%) with TTS + haptic abort alert
- **Personal Best Tracking** — Persistent PB storage; all tables auto-scale to current PB
- **TTS + Haptic Engine** — Phase announcements, countdown cues, and differentiated vibration patterns
- **NSDR / Meditation Sessions** — Session logging with HR sonification analytics; full per-beat HR and rolling RMSSD charts in session detail (added 2026-03-25)
- **Per-Section Advice** — User-entered tips/reminders shown as a swipeable banner at the top of each main screen; managed via Settings
- **Audio Setting** — 5th apnea setting dimension: Silence or Music. When Music is selected, Spotify auto-starts on hold begin and detected songs are logged per record.
- **Spotify Integration** — Two-tier Spotify integration:
  - **Song Detection** (passive): Automatic now-playing detection via Spotify broadcast intents (no special permissions) with MediaSession API fallback; song log stored per free hold record and shown in hold detail summary.
  - **Account Connection** (active, added 2026-03-25): OAuth 2.0 PKCE login from Settings; when connected, a "Choose a Song" button appears on the free-hold screen (Music mode) showing a popup of all previously-played songs with artist, title, and duration; tapping a song card loads it into Spotify playback via the Web API so it plays when the user taps Start.
- **Room Database** — 16-table local database with full migration history (v21)

---

## Architecture

### Package Structure

```
com.example.wags/
├── MainActivity.kt                  # Volume-key contraction hook
├── WagsApplication.kt               # Hilt application entry point
│
├── data/
│   ├── ble/
│   │   ├── UnifiedDeviceManager.kt  # Facade merging Polar + Generic backends into single API
│   │   ├── PolarBleManager.kt       # Polar BLE backend — HR, RR, ECG, ACC, PPI streams
│   │   ├── GenericBleManager.kt     # Non-Polar BLE backend — raw GATT (oximeters, generic HR)
│   │   ├── AutoConnectManager.kt    # Persistent auto-reconnect loop cycling saved device history
│   │   ├── DevicePreferencesRepository.kt  # Unified device history (identifier::name::isPolar)
│   │   ├── HrDataSource.kt          # Merged HR/SpO₂ source via UnifiedDeviceManager
│   │   ├── BleService.kt            # Foreground service for BLE connections
│   │   ├── BlePermissionManager.kt  # Runtime BLE permission helper
│   │   ├── AccRespirationEngine.kt  # Accelerometer-based respiration detection
│   │   ├── CircularBuffer.kt        # Lock-free ring buffer for RR samples
│   │   └── RxToFlowBridge.kt        # RxJava3 → Kotlin Flow adapter
│   ├── db/
│   │   ├── WagsDatabase.kt          # Room DB v21, 16 entities, 20 migrations
│   │   ├── dao/                     # 16 DAOs (one per entity)
│   │   └── entity/                  # 16 Room entities (incl. MeditationTelemetryEntity)
│   ├── spotify/
│   │   ├── SpotifyManager.kt        # MediaSession play command + Spotify broadcast song detection
│   │   ├── SpotifyAuthManager.kt    # OAuth 2.0 PKCE login flow + token storage/refresh
│   │   ├── SpotifyApiClient.kt      # Web API client — track metadata + playback control
│   │   └── MediaNotificationListener.kt  # NotificationListenerService for MediaSession access
│   └── repository/
│       ├── AdviceRepository.kt
│       ├── ApneaRepository.kt
│       ├── ApneaSessionRepository.kt
│       ├── MorningReadinessRepository.kt
│       ├── ReadinessRepository.kt
│       ├── RfAssessmentRepository.kt
│       └── SessionRepository.kt
│
├── di/
│   ├── AppModule.kt                 # Repository bindings
│   ├── BleModule.kt                 # PolarBleApi singleton
│   ├── DatabaseModule.kt            # Room DB + all DAOs
│   └── DispatcherModule.kt          # IO / Math / Main dispatchers
│
├── domain/
│   ├── model/                       # Pure data classes & enums
│   └── usecase/
│       ├── apnea/
│       │   ├── ApneaStateMachine.kt
│       │   ├── AdvancedApneaStateMachine.kt
│       │   ├── ApneaTableGenerator.kt
│       │   ├── ApneaAudioHapticEngine.kt
│       │   ├── OximeterSafetyMonitor.kt
│       │   └── ProgressiveO2Generator.kt
│       ├── breathing/
│       │   ├── ContinuousPacerEngine.kt
│       │   ├── CoherenceScoreCalculator.kt
│       │   └── RfAssessmentOrchestrator.kt
│       ├── hrv/
│       │   ├── TimeDomainHrvCalculator.kt
│       │   ├── FrequencyDomainCalculator.kt
│       │   ├── FftProcessor.kt
│       │   ├── PchipResampler.kt
│       │   └── ArtifactCorrectionUseCase.kt
│       ├── readiness/
│       │   ├── MorningReadinessFsm.kt
│       │   ├── MorningReadinessOrchestrator.kt
│       │   └── MorningReadinessScoreCalculator.kt
│       └── session/
│           ├── HrSonificationEngine.kt
│           ├── NsdrAnalyticsCalculator.kt
│           └── SessionExporter.kt
│
└── ui/
    ├── apnea/          ApneaScreen, ApneaTableScreen, AdvancedApneaScreen,
    │                   ContractionOverlay, SessionAnalyticsScreen,
    │                   ApneaViewModel, AdvancedApneaViewModel,
    │                   SessionAnalyticsViewModel
    ├── breathing/      BreathingScreen, BreathingViewModel,
    │                   AssessmentPickerScreen, AssessmentPickerViewModel,
    │                   AssessmentRunScreen, AssessmentRunViewModel,
    │                   AssessmentResultScreen, AssessmentResultViewModel
    ├── common/         InfoHelpBubble, AdviceBanner, AdviceDialog,
    │                   AdviceViewModel, AdviceSection,
    │                   SessionGuards (SessionBackHandler, KeepScreenOn)
    ├── dashboard/      DashboardScreen, DashboardViewModel
    ├── morning/        MorningReadinessScreen, MorningReadinessResultScreen,
    │                   MorningReadinessViewModel,
    │                   MorningReadinessHistoryScreen, MorningReadinessHistoryViewModel
    ├── navigation/     WagsNavGraph, WagsRoutes
    ├── readiness/      ReadinessScreen, ReadinessViewModel
    ├── realtime/       EcgChartView, TachogramView (Canvas-based)
    ├── session/        SessionScreen, SessionViewModel
    ├── settings/       SettingsScreen (unified device management + advice),
    │                   SettingsViewModel
    └── theme/          Color.kt, Theme.kt, Type.kt
```

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material3 |
| Navigation | Navigation Compose 2.8.5 |
| DI | Hilt 2.56.2 |
| Database | Room 2.6.1 |
| Async | Coroutines 1.8.1 + RxJava3 3.1.8 |
| BLE (HRV) | Polar BLE SDK 5.11.0 |
| BLE (SpO₂) | Android BLE GATT (custom) |
| Math | Apache Commons Math 3.6.1 |
| Charts | Vico Compose M3 2.0.0 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |

---

## Training Modalities

| Modality | Enum | Description |
|---|---|---|
| CO₂ Table | `CO2_TABLE` | Fixed hold time, decreasing rest intervals. Builds CO₂ tolerance by forcing holds under rising CO₂. |
| O₂ Table | `O2_TABLE` | Fixed rest time, increasing hold intervals. Builds hypoxic tolerance by progressively extending holds. |
| Progressive O₂ | `PROGRESSIVE_O2` | Each round's hold target increases by a fixed delta from the previous. Smooth progressive overload. |
| Minimum Breath | `MIN_BREATH` | Minimal ventilation between holds. Trains the diver to hold with less pre-oxygenation. |
| Wonka First Contraction | `WONKA_FIRST_CONTRACTION` | Hold terminates immediately on first diaphragmatic contraction signal. Trains contraction awareness. |
| Wonka Endurance | `WONKA_ENDURANCE` | Hold continues for a fixed endurance delta after first contraction. Trains post-contraction tolerance. |

---

## Algorithm Reference

### CO₂ Table Formula

```
Hold   = PB × hold_fraction          (clamped to 55% max)
Rest_1 = Hold
Rest_n = Rest_1 − (n − 1) × ΔRest
ΔRest  = (Rest_1 − Rest_min) / (N − 1)

Variables:
  PB             = Personal best breath-hold (ms)
  hold_fraction  = Fraction of PB for hold (set by Difficulty)
  Rest_min       = Minimum rest duration in ms (set by Difficulty)
  N              = Total rounds (set by Session Length)
  n              = Round number (1-indexed)

Scaling by Difficulty:
  EASY:   hold_fraction = 0.40, Rest_min = 30s
  MEDIUM: hold_fraction = 0.50, Rest_min = 15s
  HARD:   hold_fraction = 0.55, Rest_min = 10s

Rounds by Session Length:
  SHORT = 4 rounds, MEDIUM = 8 rounds, LONG = 12 rounds
```

### O₂ Table Formula

```
Hold_1 = PB × 0.40  (always 40% PB for first hold)
Hold_max = PB × max_fraction          (set by Difficulty)
Hold_n = Hold_1 + (n − 1) × ΔHold
ΔHold  = (Hold_max − Hold_1) / (N − 1)
Rest   = fixed (set by Difficulty)

Variables:
  PB             = Personal best breath-hold (ms)
  max_fraction   = Fraction of PB for max hold (set by Difficulty)
  N              = Total rounds (set by Session Length)
  n              = Round number (1-indexed)

Scaling by Difficulty:
  EASY:   max_fraction = 0.70, Rest = 120s
  MEDIUM: max_fraction = 0.80, Rest = 120s
  HARD:   max_fraction = 0.85, Rest = 150s

Rounds by Session Length:
  SHORT = 4 rounds, MEDIUM = 8 rounds, LONG = 12 rounds
```

### Contraction Efficiency Formula

```
Efficiency = T_cruise / T_total × 100%

Variables:
  T_cruise = Time from hold start to first diaphragmatic contraction (ms)
  T_total  = Total hold duration (ms)
  T_struggle = T_total − T_cruise

Interpretation:
  Efficiency > 70% → Excellent CO₂ tolerance
  Efficiency 50–70% → Developing
  Efficiency < 50% → CO₂ tolerance needs work
```

### HRV — ln(RMSSD)

```
RMSSD = √( (1/N) × Σ(RR_i+1 − RR_i)² )
ln(RMSSD) = natural log of RMSSD

Variables:
  RR_i   = i-th RR interval (ms)
  N      = number of successive differences
  RMSSD  = Root Mean Square of Successive Differences

Readiness scoring:
  ln(RMSSD) ≥ 4.0  → Green  (score 80–100)
  ln(RMSSD) ≥ 3.5  → Orange (score 60–79)
  ln(RMSSD) < 3.5  → Red    (score 0–59)
```

### SpO₂ Formula

```
SpO₂ = (HbO₂ / (HbO₂ + Hb)) × 100%

Variables:
  HbO₂ = Oxygenated hemoglobin concentration
  Hb   = Deoxygenated hemoglobin concentration

Normal range:    95–100%
Freediving concern: < 90%
Critical threshold: User-defined (default 80%, range 70–95%)
```

---

## Scaling Matrix

### Session Length (controls number of rounds)

| Length | Rounds | Best For |
|--------|--------|----------|
| `SHORT`  | 4  | Warm-up, time-limited sessions |
| `MEDIUM` | 8  | Standard training |
| `LONG`   | 12 | Peak training weeks |

### Difficulty (controls PB percentage intensity)

| Difficulty | CO₂ Hold | CO₂ Min Rest | O₂ Max Hold | O₂ Rest | Best For |
|------------|----------|--------------|-------------|---------|----------|
| `EASY`     | 40% PB   | 30s          | 70% PB      | 120s    | Beginners, recovery |
| `MEDIUM`   | 50% PB   | 15s          | 80% PB      | 120s    | Regular training |
| `HARD`     | 55% PB   | 10s          | 85% PB      | 150s    | Advanced athletes |

Combine freely: e.g., "Long + Hard" = 12 rounds at 55%/85% PB intensity.

---

## BLE Devices Supported

### HRV — Polar H10 / Verity Sense

| Property | Value |
|---|---|
| SDK | Polar BLE SDK 5.11.0 |
| Data streams | RR intervals, ECG (H10 only), accelerometer |
| Connection | Polar device ID (e.g. `B5A32F`) |
| Features used | `FEATURE_HR`, `FEATURE_POLAR_ONLINE_STREAMING`, `FEATURE_POLAR_SDK_MODE` |
| Permissions | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` (API < 31) |

### SpO₂ — Wellue OxySmart / Viatom PC-60F

| Property | Value |
|---|---|
| Protocol | Custom Android BLE GATT |
| Data | SpO₂ (%), pulse rate (bpm), perfusion index |
| Connection | BLE scan → device address |
| Safety | Configurable abort threshold (70–95%), TTS + haptic alert |
| Permissions | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+) / `ACCESS_FINE_LOCATION` (API < 31) |

---

## Safety

> ⚠️ **MEDICAL DISCLAIMER**
>
> Freediving is an inherently dangerous activity that can result in loss of consciousness and death. This application is a **training aid only** and does not replace proper freediving education, certification, or the presence of a qualified safety diver.
>
> - **Never freedive alone.** Always have a trained buddy or safety diver present.
> - The SpO₂ safety monitor is a supplementary alert system. Pulse oximeters can produce inaccurate readings during breath-holds due to vasoconstriction.
> - The app's abort threshold is a configurable guideline, not a medical recommendation. Consult a physician before engaging in hypoxic training.
> - CO₂ and O₂ tables are generated algorithmically from your personal best. Progression should be gradual. Do not attempt tables beyond your current fitness level.
> - Hyperventilation before breath-holds is dangerous and can cause shallow water blackout. The app flags hyperventilation prep as a risk factor.
>
> The developers of Wags accept no liability for injury or death resulting from use of this application.

---

## Database Schema

The Room database (`wags.db`) is at version 20 with 15 tables:

| Table | Entity | Purpose |
|---|---|---|
| `daily_readings` | `DailyReadingEntity` | HRV readiness scores and raw metrics per day |
| `apnea_records` | `ApneaRecordEntity` | Free hold records (duration, lung volume, HR, audio) |
| `apnea_song_log` | `ApneaSongLogEntity` | Songs detected during a free hold (FK → apnea_records, cascade delete) |
| `session_logs` | `SessionLogEntity` | NSDR/meditation session logs with HR analytics |
| `rf_assessments` | `RfAssessmentEntity` | Resonance frequency assessment results |
| `acc_calibrations` | `AccCalibrationEntity` | Accelerometer respiration calibration data |
| `morning_readiness` | `MorningReadinessEntity` | Full morning readiness protocol results |
| `apnea_sessions` | `ApneaSessionEntity` | Structured table session records (CO₂/O₂/Advanced) |
| `contractions` | `ContractionEntity` | Per-round contraction timestamps for analytics |
| `telemetry` | `TelemetryEntity` | Time-series HR/SpO₂ telemetry per session |
| `advice` | `AdviceEntity` | Per-section user advice/reminder entries |

### Migration History

| Version | Change |
|---|---|
| 1 → 2 | Added `monitorId` to `session_logs`; made HR columns nullable |
| 2 → 3 | Added `morning_readiness` table |
| 3 → 4 | Added `apnea_sessions`, `contractions`, `telemetry` tables |
| 4 → 5 | Added `accBreathingUsed` column to `rf_assessments` |
| 17 → 18 | Added `posture` column to `apnea_records` (default `LAYING`) |
| 18 → 19 | Added `advice` table |
| 19 → 20 | Added `audio` column to `apnea_records` (default `SILENCE`); added `apnea_song_log` table |

---

## Setup

### Prerequisites

- Android Studio Meerkat (2024.3) or later
- Android SDK 36
- JDK 11
- A physical Android device (API 26+) for BLE testing

### Build & Run

```bash
# Clone the repository
git clone https://github.com/your-org/wags.git
cd wags

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test
```

### BLE Device Setup

1. Open **Settings** from the dashboard top-right gear icon
2. Grant Bluetooth permissions when prompted
3. Tap **Scan** — all nearby BLE devices appear in a single list (Polar, oximeters, generic HR sensors)
4. Tap any device to connect — the app auto-detects its type from the advertised name and routes to the correct backend
5. Connected devices are saved to history and auto-reconnect on app restart

### First Training Session

1. Navigate to **Apnea Training**
2. Set your **Personal Best** (e.g. 120 seconds = 2:00)
3. Select a **Session Length** (Short / Medium / Long) and **Difficulty** (Easy / Medium / Hard)
4. Choose **CO₂ Table** or **O₂ Table**
5. Tap **Start** — the table auto-generates from your PB
6. During holds: double-tap screen or press volume buttons to log contractions
7. After session: view analytics in **Session Analytics History**

---

## Changelog

### 2026-03-26 — BLE Scan Device Name Fix

Fixed BLE device scan showing MAC addresses (e.g. `AA:BB:CC:DD:EE:FF`) instead of human-readable device names for non-Polar devices. The root cause was that `BluetoothDevice.getName()` returns `null` on Android 10+ for unpaired devices. The fix reads the advertised name from `ScanRecord.getDeviceName()` first, which is always available in the BLE advertisement packet.

#### Modified Files

- **[`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt)** — Changed name resolution in both `unifiedScanResults` mapping and `uiScanCallback` to prefer `result.scanRecord?.deviceName` over `result.device.name`, falling back to MAC address only when neither is available.

### 2026-03-25 — Spotify Account Connection + Song Picker

Added full Spotify account integration via OAuth 2.0 PKCE flow. Users can now connect their Spotify account from Settings and choose which song to play before starting a breath hold.

#### New Files

- **[`SpotifyAuthManager.kt`](app/src/main/java/com/example/wags/data/spotify/SpotifyAuthManager.kt)** *(new)* — PKCE OAuth flow: generates code verifier/challenge, opens browser login, exchanges auth code for tokens, auto-refreshes expired tokens. Tokens stored in `spotify_prefs` SharedPreferences.
- **[`SpotifyApiClient.kt`](app/src/main/java/com/example/wags/data/spotify/SpotifyApiClient.kt)** *(new)* — Spotify Web API client: `getTrackDetail()` fetches title/artist/duration/albumArt; `startPlayback()` starts a specific track on the user's active device.
- **[`SongPickerComponents.kt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt)** *(new)* — Composables for the song picker: `SongPickerButton`, `SelectedSongBanner`, `SongPickerDialog`, `SongCard`.

#### Modified Files

- **[`build.gradle.kts`](app/build.gradle.kts)** — Added `okhttp:4.12.0` dependency for HTTP calls.
- **[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)** — Added `launchMode="singleTask"` to MainActivity; added intent filter for `wags://spotify-callback` redirect URI.
- **[`MainActivity.kt`](app/src/main/java/com/example/wags/MainActivity.kt)** — Injects `SpotifyAuthManager`; handles Spotify OAuth redirect in `onCreate()` and `onNewIntent()`.
- **[`AppModule.kt`](app/src/main/java/com/example/wags/di/AppModule.kt)** — Added `@Named("spotify_prefs")` SharedPreferences provider.
- **[`ApneaSongLogDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaSongLogDao.kt)** — Added `getDistinctSongs()` query: returns unique songs (by spotifyUri) ordered by most recently played.
- **[`ApneaRepository.kt`](app/src/main/java/com/example/wags/data/repository/ApneaRepository.kt)** — Added `getDistinctSongs()` method exposing the DAO query.
- **[`SettingsViewModel.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsViewModel.kt)** — Injects `SpotifyAuthManager`; added `spotifyConnected` to UI state; added `buildSpotifyLoginIntent()` and `disconnectSpotify()`.
- **[`SettingsScreen.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsScreen.kt)** — `SpotifyIntegrationCard` now shows Connect/Disconnect button for Spotify account alongside the existing Notification Access section.
- **[`FreeHoldActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt)** — ViewModel injects `SpotifyApiClient` + `SpotifyAuthManager`; UI state extended with song picker fields; `loadPreviousSongs()` fetches enriched track details; `selectSong()` loads track into Spotify; `startFreeHold()` uses Web API when a song was pre-selected; screen shows "Choose a Song" button and selected song banner.

### 2026-03-25 — Fix: Spotify song not captured in free hold summary

Fixed the Spotify song not being recorded when a free hold starts with the MUSIC audio setting. The root cause was that song detection relied entirely on the MediaSession API, which requires Notification Access — a special system permission the user must grant manually. Without it, `refreshSpotifyController()` always threw `SecurityException` and `_currentSong` was never populated.

Rewrote [`SpotifyManager`](app/src/main/java/com/example/wags/data/spotify/SpotifyManager.kt) with a dual-strategy approach:

1. **Primary: Spotify broadcast receiver** — Registers a `BroadcastReceiver` for `com.spotify.music.metadatachanged` and `com.spotify.music.playbackstatechanged`. These legacy Spotify broadcasts require **no special permissions** and fire whenever the track changes. The receiver is registered when tracking starts and unregistered when it stops.

2. **Secondary: MediaSession API** — Still attempted as a bonus path (provides richer metadata) but gracefully degrades when Notification Access is not granted.

3. **Timing poll on start:** If no metadata is available immediately after `sendPlayCommand()`, polls every 250 ms for up to 2 s until the broadcast or MediaSession delivers the track info.

4. **Fallback on stop:** If `_sessionSongs` is still empty but `_currentSong` is non-null (e.g. same song resumed, no metadata-change event), uses it as the single session song.

The song now appears correctly in the hold's detail summary ("Song" row) and in the "🎵 Songs Played" card on [`ApneaRecordDetailScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaRecordDetailScreen.kt).

### 2026-03-25 — Audio Setting + Spotify Integration

Added a 5th apnea setting dimension: **Audio** (Silence / Music). When Music is selected, Spotify auto-starts when a free hold begins and detected songs are logged per record.

#### New Domain Models

- **[`AudioSetting.kt`](app/src/main/java/com/example/wags/domain/model/AudioSetting.kt)** *(new)* — Enum with `SILENCE` and `MUSIC` values, each with a `displayName()`.
- **[`SpotifySong.kt`](app/src/main/java/com/example/wags/domain/model/SpotifySong.kt)** *(new)* — Data class for track metadata: title, artist, albumArt, spotifyUri, startedAtMs, endedAtMs.

#### Database (v19 → v20)

- **[`ApneaRecordEntity`](app/src/main/java/com/example/wags/data/db/entity/ApneaRecordEntity.kt)** — Added `audio: String = "SILENCE"` column.
- **[`ApneaSongLogEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/ApneaSongLogEntity.kt)** *(new)* — Room entity for `apnea_song_log` table; FK with cascade delete to `apnea_records`.
- **[`ApneaSongLogDao.kt`](app/src/main/java/com/example/wags/data/db/dao/ApneaSongLogDao.kt)** *(new)* — `insertAll`, `getForRecord`, `deleteForRecord`.
- **[`WagsDatabase`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt)** — `MIGRATION_19_20` adds `audio TEXT NOT NULL DEFAULT 'SILENCE'` to `apnea_records` and creates `apnea_song_log` table. All existing records backfilled with `SILENCE`.
- **[`DatabaseModule`](app/src/main/java/com/example/wags/di/DatabaseModule.kt)** — Registered `MIGRATION_19_20`; added `ApneaSongLogDao` provider.

#### Data Layer

- **[`ApneaRecordDao`](app/src/main/java/com/example/wags/data/db/dao/ApneaRecordDao.kt)** — Completely rewritten with a **dynamic `@RawQuery` builder** pattern. With 5 settings the combinatorial PB queries would require 96 hand-coded methods; instead three builder functions (`buildBestFreeHoldQuery`, `buildIsBestQuery`, `buildWasBestAtTimeQuery`) construct SQL at runtime. All filtered queries include `audio`.
- **[`ApneaRepository`](app/src/main/java/com/example/wags/data/repository/ApneaRepository.kt)** — All methods accept `audio: String`. PB logic expanded to 5-setting combinatorics (C(5,k) for k=0..5). Added `saveSongLog()` and `getSongLogForRecord()`.

#### Trophy System (1–6 🏆)

- **[`PersonalBestCategory`](app/src/main/java/com/example/wags/domain/model/PersonalBestCategory.kt)** — Added `FOUR_SETTINGS` level. Trophy counts now 1–6: EXACT→1, FOUR_SETTINGS→2, THREE_SETTINGS→3, TWO_SETTINGS→4, ONE_SETTING→5, GLOBAL→6.
- **[`PersonalBestsScreen`](app/src/main/java/com/example/wags/ui/apnea/PersonalBestsScreen.kt)** — Added 6🏆 Global and 2🏆 Four Settings sections. Text style tiers expanded to 6 levels.

#### Spotify Integration

- **[`SpotifyManager.kt`](app/src/main/java/com/example/wags/data/spotify/SpotifyManager.kt)** *(new)* — `@Singleton` with `@Inject constructor`. `sendPlayCommand()` uses `AudioManager.dispatchMediaKeyEvent(KEYCODE_MEDIA_PLAY)` + direct `com.spotify.music.PLAY` broadcast. `sendPauseAndRewindCommand()` sends `KEYCODE_MEDIA_PAUSE` + `KEYCODE_MEDIA_PREVIOUS`. `startTracking()` / `stopTracking()` manage session song list. Song detection uses `MediaController.Callback.onMetadataChanged()` via `MediaSessionManager.getActiveSessions()` — requires Notification Access permission.
- **[`MediaNotificationListener.kt`](app/src/main/java/com/example/wags/data/spotify/MediaNotificationListener.kt)** *(new)* — `NotificationListenerService` subclass. On connect, registers `MediaSessionManager.OnActiveSessionsChangedListener` and forwards session changes to `SpotifyManager.onActiveSessionsChanged()`.
- **[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)** — Declared `MediaNotificationListener` with `BIND_NOTIFICATION_LISTENER_SERVICE` permission.
- **[`SettingsScreen.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsScreen.kt)** — Added `SpotifyIntegrationCard` showing Notification Access grant status (green ✓ / orange ⚠) with an "Open Settings" button that launches `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`. Required for song detection to work.

#### ViewModel Updates

- **[`ApneaViewModel`](app/src/main/java/com/example/wags/ui/apnea/ApneaViewModel.kt)** — Added `SpotifyManager` injection, `_audio` StateFlow, `setAudio()`. `startFreeHold()` calls `sendPlayCommand()` + `startTracking()` when MUSIC. `stopFreeHold()` calls `stopTracking()` and saves song log. `ApneaUiState` has `audio` and `nowPlayingSong`.
- **[`FreeHoldActiveScreen`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt)** — Reads `audio` nav arg; Spotify start/stop wired to hold lifecycle; `NowPlayingBanner` shown during active hold.
- **[`ApneaRecordDetailViewModel`](app/src/main/java/com/example/wags/ui/apnea/ApneaRecordDetailViewModel.kt)** / **[`ApneaRecordDetailScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaRecordDetailScreen.kt)** — Audio editable in edit bottom sheet; song log card shown when songs exist.
- **[`AllApneaRecordsViewModel`](app/src/main/java/com/example/wags/ui/apnea/AllApneaRecordsViewModel.kt)** — `filterAudio` added; `setAudioFilter()` function.
- **[`ApneaHistoryViewModel`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryViewModel.kt)** — Reads `audio` nav arg; passes to all repo calls.

#### UI Layer

- **[`ApneaScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt)** — Audio FilterChips in settings panel; `NowPlayingBanner` composable; `audio` passed to `freeHoldActive` nav call.
- **[`WagsNavGraph`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt)** — `FREE_HOLD_ACTIVE` route updated with `{audio}` path segment (default `SILENCE` for backward compatibility).

#### Garmin Integration

- **[`GarminApneaRepository`](app/src/main/java/com/example/wags/data/garmin/GarminApneaRepository.kt)** — Garmin-sourced free holds default to `audio = "SILENCE"` (no Spotify on watch).

---

### 2026-03-24 — Personal Best Celebration Sound Effects

When a new personal best is set in the apnea free hold section, the confetti celebration dialog now plays a tiered sound effect matching the trophy level:

- **[`apnea_pb1.mp3`](app/src/main/res/raw/apnea_pb1.mp3)** — EXACT (1 🏆): record for the exact 4-setting combination
- **[`apnea_pb2.mp3`](app/src/main/res/raw/apnea_pb2.mp3)** — THREE_SETTINGS (2 🏆🏆): record across any 3-setting combination
- **[`apnea_pb3.mp3`](app/src/main/res/raw/apnea_pb3.mp3)** — TWO_SETTINGS (3 🏆🏆🏆): record across any 2-setting combination
- **[`apnea_pb4.mp3`](app/src/main/res/raw/apnea_pb4.mp3)** — ONE_SETTING (4 🏆🏆🏆🏆): record across any single setting
- **[`apnea_pb5.mp3`](app/src/main/res/raw/apnea_pb5.mp3)** — GLOBAL (5 🏆🏆🏆🏆🏆): all-time best across all settings

Always plays the best (highest-level) sound the user deserves — never more than one sound per new record.

#### Files changed

- **[`ApneaPbSoundPlayer.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaPbSoundPlayer.kt)** *(new)* — Maps `PersonalBestCategory` → raw MP3 resource; plays once via `MediaPlayer`, self-releases on completion.
- **[`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt)** — `NewPersonalBestDialog` fires `playApneaPbSound()` via `LaunchedEffect(Unit)` when the dialog appears.
- MP3 files moved from `res/` root to `res/raw/` (required for `MediaPlayer.create` / `R.raw` access).

---

### 2026-03-23 — Posture Setting for Apnea Free Holds

Added a 4th apnea setting type: **Posture** (Sitting / Laying). This is integrated across the entire apnea feature — database, queries, personal bests, trophy system, history, filters, and navigation.

#### New Model

- **[`Posture.kt`](app/src/main/java/com/example/wags/domain/model/Posture.kt)** — New enum with `SITTING` and `LAYING` values, each with a `displayName()`.

#### Database (v17 → v18)

- **[`ApneaRecordEntity`](app/src/main/java/com/example/wags/data/db/entity/ApneaRecordEntity.kt)** — Added `posture` column with default `"LAYING"`.
- **[`WagsDatabase`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt)** — `MIGRATION_17_18` adds the `posture` column; existing records default to `LAYING`.
- **[`DatabaseModule`](app/src/main/java/com/example/wags/di/DatabaseModule.kt)** — Registered `MIGRATION_17_18`.

#### Data Layer

- **[`ApneaRecordDao`](app/src/main/java/com/example/wags/data/db/dao/ApneaRecordDao.kt)** — All settings-filtered queries now include `posture`. Added single-setting, two-setting, and three-setting posture queries for the expanded PB combinatorics.
- **[`FreeHoldTelemetryDao`](app/src/main/java/com/example/wags/data/db/dao/FreeHoldTelemetryDao.kt)** — All settings-filtered telemetry stat queries now include `posture`.
- **[`ApneaRepository`](app/src/main/java/com/example/wags/data/repository/ApneaRepository.kt)** — All methods accept `posture`. PB logic expanded from 3-setting to 4-setting combinatorics (C(4,k) for k=0..4 → 16 category levels).

#### Trophy System (1–5 🏆)

- **[`PersonalBestCategory`](app/src/main/java/com/example/wags/domain/model/PersonalBestCategory.kt)** — Added `THREE_SETTINGS` level. Trophy counts now 1–5: EXACT→1, THREE_SETTINGS→2, TWO_SETTINGS→3, ONE_SETTING→4, GLOBAL→5.
- **[`PersonalBestsScreen`](app/src/main/java/com/example/wags/ui/apnea/PersonalBestsScreen.kt)** — Added 5🏆 Global section and 2🏆 Three Settings section. Text style tiers expanded to 5 levels.

#### UI Layer

- **[`ApneaViewModel`](app/src/main/java/com/example/wags/ui/apnea/ApneaViewModel.kt)** — Added `_posture` StateFlow, `posture` in `ApneaUiState`, `setPosture()` method. All repo calls pass posture.
- **[`ApneaScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt)** — Posture FilterChips in settings panel. Navigation calls include posture. `NewPersonalBestDialog` handles `THREE_SETTINGS` category. Stats subtitle includes posture.
- **[`FreeHoldActiveScreen`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt)** — ViewModel reads `posture` from nav args. Entity creation and PB check include posture.
- **[`ApneaRecordDetailViewModel`](app/src/main/java/com/example/wags/ui/apnea/ApneaRecordDetailViewModel.kt)** / **[`ApneaRecordDetailScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaRecordDetailScreen.kt)** — Posture editable in the edit bottom sheet. Posture displayed in record detail summary.
- **[`AllApneaRecordsViewModel`](app/src/main/java/com/example/wags/ui/apnea/AllApneaRecordsViewModel.kt)** / **[`AllApneaRecordsScreen`](app/src/main/java/com/example/wags/ui/apnea/AllApneaRecordsScreen.kt)** — Posture filter added to filter UI and all paged queries.
- **[`ApneaHistoryViewModel`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryViewModel.kt)** — Reads posture from nav args; passes to all repo calls.
- **[`WagsNavGraph`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt)** — Routes for `FREE_HOLD_ACTIVE`, `APNEA_HISTORY`, and `APNEA_ALL_RECORDS` now include `{posture}` path segment.

#### Garmin Integration

- **[`GarminApneaRepository`](app/src/main/java/com/example/wags/data/garmin/GarminApneaRepository.kt)** — Garmin-sourced free holds default to `posture = "LAYING"`.

---

### 2026-03-23 — Critical Fix: RR Interval Polling Stops After CircularBuffer Fills

#### Bug Fix

- **[`CircularBuffer`](app/src/main/java/com/example/wags/data/ble/CircularBuffer.kt)** — Added monotonically increasing `totalWrites()` counter. The existing `size()` method caps at `capacity` (1024), so any polling loop using `size()` to detect new data would stop seeing new entries once the buffer was full. `totalWrites()` never caps and is safe for "new since last check" subtraction.
- **[`AssessmentRunViewModel`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunViewModel.kt)** — Replaced `rrBuffer.size()` with `rrBuffer.totalWrites()` in the RR polling loop. This was the primary cause of beats getting stuck during RF assessments (e.g., stuck at beat 57), which cascaded into: coherence stuck at a single value, all epoch results invalid (score 0), warning symbols on all breathing rates in the report, and the resonance curve flat at 0.
- **[`BreathingViewModel`](app/src/main/java/com/example/wags/ui/breathing/BreathingViewModel.kt)** — Same fix applied to all 3 polling loops: the session RR polling loop, the RF assessment RR forwarding loop, and the session-start snapshot.
- **[`ReadinessViewModel`](app/src/main/java/com/example/wags/ui/readiness/ReadinessViewModel.kt)** — Same fix applied to the readiness session RR polling loop and session-start snapshot.

---

### 2026-03-11 — Morning Readiness: FSM Simplification, Bug Fix & History Screen

#### Bug Fix

- **`ArtifactCorrectionUseCase`** — Fixed size mismatch crash (`nn and artifactMask must have same size`). When the pre-filter stage reduced the RR buffer below 10 samples, the early-return path returned `correctedNn` sized to `validRr` but `artifactMask` sized to the original input. Both arrays are now sized to `validRr`, eliminating the mismatch that prevented Morning Readiness results from being saved.

#### FSM Simplification

- **`MorningReadinessState`** — Removed `SUPINE_REST`, `STAND_CAPTURE`, and `STAND_HRV` states. Replaced with a single `STANDING` state. New flow: `IDLE → INIT → SUPINE_HRV → STAND_PROMPT → STANDING → QUESTIONNAIRE → CALCULATING → COMPLETE`.
- **`MorningReadinessFsm`** — Removed the separate `enterStandCapture` / `enterStandHrv` phases. A single 120s `STANDING` phase now collects all standing raw data (peak HR tracking + RR intervals). All metric derivation (HRV, orthostatic, 30:15 ratio, OHRR) happens in `CALCULATING` from the raw buffers.
- **`MorningReadinessStateHandler`** — Updated `canTransitionTo()` to reflect the simplified 8-state machine.
- **`MorningReadinessScreen`** — Removed `StandCaptureContent` and `StandHrvContent`. Added single `StandingContent` composable showing countdown, live RMSSD, RR count, and peak HR. Added "History" button in the top bar.

#### New Feature — Morning Readiness History

- **[`MorningReadinessHistoryViewModel`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessHistoryViewModel.kt)** — Observes all readings from the repository. Combines with a selected-date `StateFlow` to produce `MorningReadinessHistoryUiState` containing the full reading list, the set of dates with readings (for calendar dots), and the currently selected reading.
- **[`MorningReadinessHistoryScreen`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessHistoryScreen.kt)** — Calendar view with month navigation. Days with readings show a cyan dot and are tappable. Tapping a day opens a detail card showing all stored metrics: readiness score, supine HRV, standing HRV, orthostatic response, respiratory rate, Hooper Index, and data quality. Empty state shown when no readings exist.
- **[`WagsNavGraph`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt)** — Added `morning_readiness_history` route. `MorningReadinessScreen` now receives `onNavigateToHistory` callback wired to this route.

---

### 2026-03-11 — RF Assessment Expansion

Ported the full RF Assessment ecosystem from the desktop hrvm app to Android.

#### New Files

**Domain layer (`domain/usecase/breathing/`)**

- [`SteppedEpochScorer.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/SteppedEpochScorer.kt) — Composite epoch scoring formula:
  ```
  score = (phase×0.40 + PT/baseline_PT×0.30 + LFnu/100×0.20 + RMSSD/baseline_RMSSD×0.10) × 260
  ```
  Quality gates: phase ≥ 0.25, PT amplitude ≥ 1.5 BPM. 7-tier color scale (Red → White).

- [`SlidingWindowPacerEngine.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/SlidingWindowPacerEngine.kt) — Analytically-integrated chirp pacer sweeping 6.75 → 4.5 BPM over 78 breath cycles (~16 min). Port of desktop `ContinuousPacer`.

- [`SlidingWindowAnalytics.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/SlidingWindowAnalytics.kt) — Post-session analytics for the sliding-window protocol: resamples RR to 4 Hz, sliding 60-second FFT windows for LF power, PT amplitude, Hilbert PLV. Resonance index = `0.4×norm(LF) + 0.4×norm(PT) + 0.2×norm(PLV)`.

**Data layer (`data/repository/`)**

- [`RfAssessmentRepository.kt`](app/src/main/java/com/example/wags/data/repository/RfAssessmentRepository.kt) — Repository wrapping `RfAssessmentDao`: `saveSession()`, `getAllSessions()`, `getLatestForProtocol()`, `getBestSession()`, `hasAnySession()`.

**UI layer (`ui/breathing/`)**

- [`AssessmentPickerScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentPickerScreen.kt) + [`AssessmentPickerViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentPickerViewModel.kt) — Protocol picker with 5 protocols; TARGETED disabled if no history.
- [`AssessmentRunScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt) + [`AssessmentRunViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunViewModel.kt) — Live pacer + HUD during assessment; saves session to DB on completion.
- [`AssessmentResultScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentResultScreen.kt) + [`AssessmentResultViewModel.kt`](app/src/main/java/com/example/wags/ui/breathing/AssessmentResultViewModel.kt) — Leaderboard + History tabs; color-coded scores; ⚠ on invalid epochs.

#### Modified Files

- [`RfAssessmentOrchestrator.kt`](app/src/main/java/com/example/wags/domain/usecase/breathing/RfAssessmentOrchestrator.kt) — Added `SLIDING_WINDOW` to `RfProtocol` enum; wired real epoch math (RMSSD, LF power, phase synchrony, PT amplitude) via `SteppedEpochScorer`; added `DEEP_GRID` constant; exposes `Flow<RfOrchestratorState>` with 5 variants.
- [`RfAssessmentEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/RfAssessmentEntity.kt) — Added `accBreathingUsed: Boolean` column.
- [`WagsDatabase.kt`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt) — Bumped version 4 → 5; added `MIGRATION_4_5`.
- [`RfAssessmentDao.kt`](app/src/main/java/com/example/wags/data/db/dao/RfAssessmentDao.kt) — Added `getLatestForProtocol()` and `hasAnySession()` queries.
- [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt) — Added 3 new routes: `rf_assessment_picker`, `rf_assessment_run/{protocol}`, `rf_assessment_result/{sessionTimestamp}`.
- [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt) — Replaced inline RF section with a single "RF Assessment" button navigating to the picker.

#### RF Protocols Supported

| Protocol | Duration | Description |
|---|---|---|
| `EXPRESS` | ~8 min | 5 rates × 1 min, quick scan |
| `STANDARD` | ~18 min | 5 rates × 2 min, standard calibration |
| `DEEP` | ~42 min | 13 combos × 3 min, deep calibration |
| `TARGETED` | ~10 min | Optimal ±0.2 BPM × 3 min (requires history) |
| `SLIDING_WINDOW` | ~16 min | Chirp 6.75 → 4.5 BPM, continuous scan |

---

### Unified BLE Device Architecture — 2026-03-23

**Problem:** The previous BLE connection system had two predetermined device categories (Polar and Oximeter) managed by separate subsystems (`PolarBleManager` and `OximeterBleManager`). This caused devices to be misclassified (e.g. a Polar H10 ending up in the oximeter slot), required separate scan/connect flows for each type, and made the Settings screen confusing with dual device sections.

**Solution:** Replaced the dual-backend system with a **unified device architecture**:

- **Single scan** finds ALL nearby BLE devices (Polar SDK + raw GATT simultaneously)
- **Connect to any device** — routing to the correct backend (Polar SDK or raw GATT) is automatic based on the device's advertised name
- **Device type auto-detection** from name after connection:
  - Name contains **"H10"** → `POLAR_H10` (HR, RR, ECG, ACC)
  - Name contains **"Sense"** → `POLAR_VERITY` (HR, PPI)
  - Name contains **"OxySmart"** or **"PC-60F"** → `OXIMETER` (HR, SpO₂)
  - Unknown → `GENERIC_BLE` (HR only)
- **Capability-based feature checks** — sessions check `hasCapability(RR)` or `isH10Connected()` instead of checking specific device slots
- **Single device history** — all saved devices stored in one list (`identifier::name::isPolar`), auto-reconnect cycles through the list until one connects
- **Single Settings screen** — one scan button, one device list, one connected device card showing type and capabilities

#### New Files

| File | Purpose |
|---|---|
| [`UnifiedDeviceManager.kt`](app/src/main/java/com/example/wags/data/ble/UnifiedDeviceManager.kt) | Facade merging PolarBleManager + GenericBleManager into single API: connectionState, scanResults, liveHr, liveSpO2, connect/disconnect, capability queries, stream delegation |
| [`GenericBleManager.kt`](app/src/main/java/com/example/wags/data/ble/GenericBleManager.kt) | Renamed from OximeterBleManager; uses unified `BleConnectionState` instead of `OximeterConnectionState`; handles all non-Polar BLE devices via raw Android GATT |
| [`DeviceType.kt`](app/src/main/java/com/example/wags/domain/model/DeviceType.kt) | Enum: POLAR_H10, POLAR_VERITY, OXIMETER, GENERIC_BLE — each with a `capabilities: Set<DeviceCapability>` |
| [`DeviceCapability.kt`](app/src/main/java/com/example/wags/domain/model/DeviceCapability.kt) | Enum: HR, RR, ECG, ACC, PPI, SPO2 |
| [`ScannedDevice.kt`](app/src/main/java/com/example/wags/domain/model/ScannedDevice.kt) | Unified scan result model for both backends |

#### Modified Files

| File | Change |
|---|---|
| [`BleConnectionState.kt`](app/src/main/java/com/example/wags/domain/model/BleConnectionState.kt) | Added `Scanning` state; added `deviceType: DeviceType` to `Connected` |
| [`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt) | Single `connectionState` replacing h10State/verityState dual slots; `unifiedScanResults` flow |
| [`DevicePreferencesRepository.kt`](app/src/main/java/com/example/wags/data/ble/DevicePreferencesRepository.kt) | Unified device history (`SavedDevice` entries); migration from legacy polar/oximeter split |
| [`HrDataSource.kt`](app/src/main/java/com/example/wags/data/ble/HrDataSource.kt) | Now delegates to `UnifiedDeviceManager` instead of both managers |
| [`AutoConnectManager.kt`](app/src/main/java/com/example/wags/data/ble/AutoConnectManager.kt) | Single loop iterating unified `deviceHistory`; routes to Polar or Generic based on `isPolar` flag |
| [`BleService.kt`](app/src/main/java/com/example/wags/data/ble/BleService.kt) | Injects `UnifiedDeviceManager` instead of `PolarBleManager` |
| [`BleModule.kt`](app/src/main/java/com/example/wags/di/BleModule.kt) | Only provides PolarBleApi; all managers are `@Singleton @Inject` |
| [`SettingsViewModel.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsViewModel.kt) | Single `deviceState`, single `scanResults: List<ScannedDevice>`, unified `connectDevice()`/`disconnectDevice()` |
| [`SettingsScreen.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsScreen.kt) | Single connected device card with type + capabilities; single scan results list |
| All session ViewModels | Replaced `PolarBleManager` + `OximeterBleManager` injection with `UnifiedDeviceManager`; use `deviceManager.rrBuffer`, `deviceManager.startRrStream()`, etc. |

#### Deleted Files

| File | Reason |
|---|---|
| `OximeterBleManager.kt` | Replaced by `GenericBleManager.kt` |
| `OximeterConnectionState.kt` | Replaced by unified `BleConnectionState` with `deviceType` field |

### Bug Fixes — 2026-03-23

#### Sawtooth HR chart in apnea oximeter holds

When only an oximeter was connected during a free-hold, `saveFreeHoldRecord()` unconditionally read `rrBuffer.readLast(512)` which returned stale Polar RR data from a previous session. This interleaved with fresh oximeter HR values, producing an alternating high/low sawtooth pattern. Fixed by gating the RR snapshot on `oximeterIsPrimary`:

- `ApneaViewModel.saveFreeHoldRecord()`
- `FreeHoldActiveViewModel.saveFreeHoldRecord()`

#### Polar H10 HR stream killed by duplicate `startHrStreaming` subscription

`PolarBleManager.startRrStream()` opened a **second** `polarApi.startHrStreaming()` Flowable (key `"$deviceId-rr"`) while the auto-started HR stream (key `"$deviceId-hr"`) was already active. The Polar SDK only supports one active HR stream per device, so the second subscription silently killed the first — which was the only one writing to `liveHr`. Result: HR disappeared from the top bar and HRV readiness got zero RR intervals ("need at least 2 NNs").

Fix: `startRrStream()` now delegates to `startHrStream()`, which writes to both `liveHr` and `rrBuffer` using a single SDK subscription.

- `PolarBleManager.kt` — `startRrStream()` → delegates to `startHrStream()`

#### RF assessment phase synchrony falsely rejecting later epochs

`CoherenceScoreCalculator.calculatePhaseSynchrony()` computed cross-correlation between the IHR signal and a cosine reference wave to find the best-matching lag, but then **discarded the correlation value** and based the synchrony score solely on the **magnitude of the lag**: `synchrony = 1.0 - (bestLagSec / halfCycle)`. Since the pacer runs continuously across epochs, later epochs start at arbitrary pacer phases, producing large lags and near-zero synchrony — even when the user was perfectly following the pacer. The first 3 rounds happened to start near the reference wave's phase 0, so they passed; rounds 4–5 did not.

Fix: synchrony is now the **peak cross-correlation value** (clamped to 0–1), which measures how well the IHR tracks the breathing pattern regardless of phase offset. The lag search window was also widened from half-cycle to full-cycle to cover all possible phase offsets.

- `CoherenceScoreCalculator.kt` — `calculatePhaseSynchrony()` returns `bestCorr.coerceIn(0.0, 1.0)` instead of lag-based penalty

### Advice: Markdown Rendering + Home Screen Banner — 2026-03-24

Added inline Markdown rendering to all advice banners and a new "Home" advice section for the main dashboard screen.

#### New Files

| File | Purpose |
|---|---|
| [`MarkdownText.kt`](app/src/main/java/com/example/wags/ui/common/MarkdownText.kt) | `String.toMarkdownAnnotatedString()` extension — converts `**bold**`, `*italic*`, `_italic_`, and `***bold-italic***` to Compose `AnnotatedString` spans with no external dependencies |

#### Modified Files

| File | Change |
|---|---|
| [`AdviceBanner.kt`](app/src/main/java/com/example/wags/ui/common/AdviceBanner.kt) | `Text(text = …)` replaced with `Text(text = "💡 $text".toMarkdownAnnotatedString(), …)` so advice text renders with bold/italic formatting |
| [`AdviceSection.kt`](app/src/main/java/com/example/wags/ui/common/AdviceSection.kt) | Added `HOME = "home"` constant and `"Home"` label; `all` list now starts with `HOME` so it appears first in Settings |
| [`DashboardScreen.kt`](app/src/main/java/com/example/wags/ui/dashboard/DashboardScreen.kt) | Added `AdviceBanner(section = AdviceSection.HOME)` as the first item in the dashboard `LazyColumn` |
| [`SettingsScreen.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsScreen.kt) | `AdviceSettingsCard` iterates `AdviceSection.all` which now starts with `HOME`, so the "Home" row appears at the top of the advice list automatically |

#### Details

- Markdown parser handles `***bold-italic***` → `**bold**` → `*italic*` / `_italic_` in longest-match order; unmatched markers are emitted as plain text
- No external library added — pure Compose `buildAnnotatedString` + `SpanStyle`
- `AdviceViewModel` already iterates `AdviceSection.all`, so it picks up the new `home` section automatically with no ViewModel changes

---

### Per-Section Advice Feature — 2026-03-24

Added a user-facing "Advice" system that lets users enter personal tips, reminders, or cues for each of the five main training sections. Advice is displayed as a swipeable banner at the top of each screen and managed from Settings.

#### New Files

| File | Purpose |
|---|---|
| [`AdviceEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/AdviceEntity.kt) | Room entity for the `advice` table (id, section, text, createdAt) |
| [`AdviceDao.kt`](app/src/main/java/com/example/wags/data/db/dao/AdviceDao.kt) | DAO with observe/get/insert/update/delete for advice items |
| [`AdviceRepository.kt`](app/src/main/java/com/example/wags/data/repository/AdviceRepository.kt) | Repository wrapping AdviceDao |
| [`AdviceSection.kt`](app/src/main/java/com/example/wags/ui/common/AdviceSection.kt) | String constants for the five section keys + display labels |
| [`AdviceViewModel.kt`](app/src/main/java/com/example/wags/ui/common/AdviceViewModel.kt) | Shared Hilt ViewModel managing advice state, random cycling, and CRUD |
| [`AdviceBanner.kt`](app/src/main/java/com/example/wags/ui/common/AdviceBanner.kt) | Swipeable banner composable (left = previous, right = next random) |
| [`AdviceDialog.kt`](app/src/main/java/com/example/wags/ui/common/AdviceDialog.kt) | Full dialog for adding/editing/deleting advice items per section |

#### Modified Files

| File | Change |
|---|---|
| [`WagsDatabase.kt`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt) | Added `AdviceEntity` to entities list; bumped to v19; added `MIGRATION_18_19`; added `adviceDao()` |
| [`DatabaseModule.kt`](app/src/main/java/com/example/wags/di/DatabaseModule.kt) | Added `MIGRATION_18_19` to builder; added `provideAdviceDao()` |
| [`SettingsScreen.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsScreen.kt) | Added "Advice" card with per-section manage/add buttons; opens `AdviceDialog` |
| [`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt) | Added `AdviceBanner` below top bar; reduced collapsible settings sizes (smaller chips, labels, spacing) |
| [`BreathingScreen.kt`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt) | Added `AdviceBanner` below top bar |
| [`ReadinessScreen.kt`](app/src/main/java/com/example/wags/ui/readiness/ReadinessScreen.kt) | Added `AdviceBanner` below top bar |
| [`MorningReadinessScreen.kt`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessScreen.kt) | Added `AdviceBanner` below top bar |
| [`MeditationScreen.kt`](app/src/main/java/com/example/wags/ui/meditation/MeditationScreen.kt) | Added `AdviceBanner` below top bar |

#### UI Details

- Banner uses monochrome-safe colors (light grey text on dark surface) for greyscale display compatibility
- Swipe left on banner → previous advice; swipe right → next random advice
- Banner auto-hides when no advice exists for a section
- Apnea collapsible settings: reduced chip height to 30dp, label text to `bodySmall`, spacing to 4–6dp

---

### 2026-03-24 — Session Guards: Back Confirmation & Keep Screen On

Added two reusable composable utilities in [`SessionGuards.kt`](app/src/main/java/com/example/wags/ui/common/SessionGuards.kt):

- **`SessionBackHandler`** — Intercepts the system back gesture/button during active sessions and shows a confirmation dialog ("Discard session?") before navigating away. When the session is idle or complete, normal back behaviour applies.
- **`KeepScreenOn`** — Prevents auto-dim / auto-off during active sessions using `View.keepScreenOn`. Automatically clears the flag when the session ends or the composable leaves composition.

Both guards are applied to all 10 active session/reading screens:

| Screen | Active condition |
|---|---|
| [`ReadinessScreen`](app/src/main/java/com/example/wags/ui/readiness/ReadinessScreen.kt) | `RECORDING` or `PROCESSING` |
| [`MorningReadinessScreen`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessScreen.kt) | Not `IDLE`, `COMPLETE`, or `ERROR` |
| [`BreathingScreen`](app/src/main/java/com/example/wags/ui/breathing/BreathingScreen.kt) | Not `IDLE` or `COMPLETE` |
| [`ResonanceSessionScreen`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt) | `PREPARING` or `BREATHING` |
| [`AssessmentRunScreen`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt) | Not `IDLE` or `COMPLETE` |
| [`ApneaTableScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaTableScreen.kt) | Not `IDLE` or `COMPLETE` |
| [`FreeHoldActiveScreen`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldActiveScreen.kt) | `freeHoldActive == true` |
| [`AdvancedApneaScreen`](app/src/main/java/com/example/wags/ui/apnea/AdvancedApneaScreen.kt) | Not `IDLE` or `COMPLETE` |
| [`SessionScreen`](app/src/main/java/com/example/wags/ui/session/SessionScreen.kt) | `ACTIVE` or `PROCESSING` |
| [`MeditationScreen`](app/src/main/java/com/example/wags/ui/meditation/MeditationScreen.kt) | `ACTIVE` or `PROCESSING` |
