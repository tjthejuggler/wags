# Wags — Professional Freediving Training Ecosystem

---

## Overview

Wags is a professional-grade Android freediving training app built with Kotlin and Jetpack Compose. It combines HRV-based readiness assessment, structured apnea table training, BLE-connected biometric monitoring (Polar H10/Verity Sense for HRV, Wellue/Viatom pulse oximeters for SpO₂), and real-time safety monitoring into a single cohesive training ecosystem.

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
- **Polar BLE Integration** — Real-time RR interval streaming from Polar H10 / Verity Sense
- **Oximeter BLE Integration** — SpO₂ monitoring from Wellue OxySmart / Viatom PC-60F
- **SpO₂ Safety Monitor** — Configurable critical threshold (70–95%) with TTS + haptic abort alert
- **Personal Best Tracking** — Persistent PB storage; all tables auto-scale to current PB
- **TTS + Haptic Engine** — Phase announcements, countdown cues, and differentiated vibration patterns
- **NSDR / Meditation Sessions** — Session logging with HR sonification analytics
- **Room Database** — 9-table local database with full migration history

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
│   │   ├── PolarBleManager.kt       # Polar BLE — name-based auto-detection (H10/Verity Sense)
│   │   ├── OximeterBleManager.kt    # Wellue/Viatom SpO₂ BLE scanning & GATT
│   │   ├── AutoConnectManager.kt    # Persistent auto-reconnect loop for all BLE devices
│   │   ├── DevicePreferencesRepository.kt  # Unified Polar + oximeter device history
│   │   ├── HrDataSource.kt          # Merged HR/SpO₂ source across all devices
│   │   ├── BleService.kt            # Foreground service for BLE connections
│   │   ├── BlePermissionManager.kt  # Runtime BLE permission helper
│   │   ├── AccRespirationEngine.kt  # Accelerometer-based respiration detection
│   │   ├── CircularBuffer.kt        # Lock-free ring buffer for RR samples
│   │   └── RxToFlowBridge.kt        # RxJava3 → Kotlin Flow adapter
│   ├── db/
│   │   ├── WagsDatabase.kt          # Room DB v4, 9 entities, 3 migrations
│   │   ├── dao/                     # 9 DAOs (one per entity)
│   │   └── entity/                  # 9 Room entities
│   └── repository/
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
    ├── common/         InfoHelpBubble (reusable ⓘ bottom-sheet)
    ├── dashboard/      DashboardScreen, DashboardViewModel
    ├── morning/        MorningReadinessScreen, MorningReadinessResultScreen,
    │                   MorningReadinessViewModel,
    │                   MorningReadinessHistoryScreen, MorningReadinessHistoryViewModel
    ├── navigation/     WagsNavGraph, WagsRoutes
    ├── readiness/      ReadinessScreen, ReadinessViewModel
    ├── realtime/       EcgChartView, TachogramView (Canvas-based)
    ├── session/        SessionScreen, SessionViewModel
    ├── settings/       SettingsScreen (Polar), OximeterSettingsScreen,
    │                   SettingsViewModel, OximeterViewModel
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

The Room database (`wags.db`) is at version 5 with 9 tables:

| Table | Entity | Purpose |
|---|---|---|
| `daily_readings` | `DailyReadingEntity` | HRV readiness scores and raw metrics per day |
| `apnea_records` | `ApneaRecordEntity` | Free hold records (duration, lung volume, HR) |
| `session_logs` | `SessionLogEntity` | NSDR/meditation session logs with HR analytics |
| `rf_assessments` | `RfAssessmentEntity` | Resonance frequency assessment results |
| `acc_calibrations` | `AccCalibrationEntity` | Accelerometer respiration calibration data |
| `morning_readiness` | `MorningReadinessEntity` | Full morning readiness protocol results |
| `apnea_sessions` | `ApneaSessionEntity` | Structured table session records (CO₂/O₂/Advanced) |
| `contractions` | `ContractionEntity` | Per-round contraction timestamps for analytics |
| `telemetry` | `TelemetryEntity` | Time-series HR/SpO₂ telemetry per session |

### Migration History

| Version | Change |
|---|---|
| 1 → 2 | Added `monitorId` to `session_logs`; made HR columns nullable |
| 2 → 3 | Added `morning_readiness` table |
| 3 → 4 | Added `apnea_sessions`, `contractions`, `telemetry` tables |
| 4 → 5 | Added `accBreathingUsed` column to `rf_assessments` |

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

### Polar Device Setup

1. Open **Device Settings** from the dashboard top-right gear icon
2. Grant Bluetooth permissions when prompted
3. Tap **Scan** and select your Polar H10 or Verity Sense
4. The device ID is saved automatically for future sessions

### Oximeter Setup

1. Open **Oximeter (SpO₂)** from the dashboard
2. Grant Bluetooth permissions when prompted
3. Tap **Scan** and select your Wellue OxySmart or Viatom PC-60F
4. Set your critical SpO₂ threshold (default: 80%)
5. The app will alert you if SpO₂ drops below threshold during training

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

### BLE Device Connection Simplification — 2026-03-23

**Problem:** The previous BLE connection system required users to manually assign Polar devices as either "H10" or "Verity Sense" when connecting from the Settings screen. This led to devices being connected to the wrong slot (e.g. an H10 connected as a Verity Sense), which caused features like Morning Readiness to reject the device because it checked `h10State` specifically.

**Solution:** Replaced the manual device-type assignment with **automatic name-based identification**. Devices are now identified by their advertised name after connection:

- Name contains **"H10"** → routed to H10 slot (chest strap: HR, RR, ECG, ACC)
- Name contains **"Sense"** → routed to Verity Sense slot (optical: HR, PPI)
- Name contains **"OxySmart"** → handled by OximeterBleManager (HR + SpO₂)
- Unknown Polar devices → default to H10 slot (safe fallback)

#### Files Modified

| File | Change |
|---|---|
| [`PolarBleManager.kt`](app/src/main/java/com/example/wags/data/ble/PolarBleManager.kt) | Removed `isH10` parameter from `connectDevice()`; added `detectSlotFromName()` for automatic routing; added `isH10Connected()` and `connectedH10DeviceId()` helpers |
| [`DevicePreferencesRepository.kt`](app/src/main/java/com/example/wags/data/ble/DevicePreferencesRepository.kt) | Merged separate H10/Verity history lists into unified `polarHistory`; added migration from old keys; backward-compatible accessors |
| [`AutoConnectManager.kt`](app/src/main/java/com/example/wags/data/ble/AutoConnectManager.kt) | Replaced dual H10+Verity reconnect loops with single Polar reconnect loop |
| [`SettingsViewModel.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsViewModel.kt) | Replaced `connectH10()`/`connectVerity()`/`disconnectH10()`/`disconnectVerity()` with `connectPolar()`/`disconnectPolar()` |
| [`SettingsScreen.kt`](app/src/main/java/com/example/wags/ui/settings/SettingsScreen.kt) | Replaced dual "H10"/"Verity" buttons with single "Connect" button; unified Polar status row |
| [`MorningReadinessViewModel.kt`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessViewModel.kt) | Changed H10 check from slot-based (`h10State`) to name-based (`connectedH10DeviceId()`) |
| [`SessionViewModel.kt`](app/src/main/java/com/example/wags/ui/session/SessionViewModel.kt) | Simplified device ID resolution to use `hrDataSource.connectedPolarDeviceId()` |
| [`MeditationViewModel.kt`](app/src/main/java/com/example/wags/ui/meditation/MeditationViewModel.kt) | Simplified device ID resolution to use `hrDataSource.connectedPolarDeviceId()` |
