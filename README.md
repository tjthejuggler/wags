# WAGS — Cardiac Biofeedback App
*Last updated: 2026-03-10T22:54:00Z (Morning Readiness — full feature complete: FSM, algorithms, UI, DB v3)*

---

## Overview

WAGS is a clinical-grade Android biofeedback application designed for athletes, breath-hold divers, and practitioners who want to monitor and train their autonomic nervous system using Polar heart-rate hardware.

**Four core features:**

| Feature | Description |
|---|---|
| **Morning Readiness** | Full orthostatic HRV protocol: supine + standing RR capture, 30:15 ratio, OHRR, respiratory rate, Hooper Index questionnaire, and a 6-step Conditional Limiting Architecture scoring algorithm producing a 0–100 score with RED/YELLOW/GREEN color coding |
| **Resonance Frequency Breathing (HRVB)** | Guided pacer at the user's resonance frequency with real-time coherence scoring and RF assessment protocols |
| **Static Apnea Training** | O₂ and CO₂ table sessions with countdown timers, haptic/audio cues, and personal-best tracking |
| **Meditation / NSDR Analytics** | Session logging with HRV, frequency-domain metrics, and NSDR-specific analytics |

---

## Target Hardware

### Polar H10
- **ECG**: 130 Hz, 14-bit resolution
- **RR intervals**: Multi-per-packet delivery in 1/1024 s units (converted to ms on receipt)
- **ACC**: 200 Hz, ±2 g range — used for respiration rate estimation via Z-axis displacement

### Polar Verity Sense
- **PPI** (Pulse-to-Pulse Interval): ms values with per-sample error estimate and skin-contact flag
- Requires **SDK mode** to be activated before PPI streaming begins
- Samples are discarded when `skinContactSupported && !skinContactStatus` or `errorEstimate > 10 ms`

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Concurrency | Kotlin Coroutines + Flow |
| BLE | Official Polar BLE SDK v5+ |
| RxJava bridge | `kotlinx-coroutines-rx3` |
| Database | Room |
| Math | Apache Commons Math 3 |
| Real-time charts | Custom Canvas composable (hardware-accelerated) |
| Historical charts | Vico (native Compose) |
| Navigation | Jetpack Navigation Compose |
| Audio | `SoundPool` (countdown beeps) + `AudioTrack` (HR sonification) |
| Haptics | `VibrationEffect` (`VibratorManager` API 31+ / legacy `Vibrator`) |

---

## Architecture Overview

WAGS follows **Clean Architecture** with strict unidirectional dependency flow:

```
UI (Compose Screens)
    ↓
ViewModels (Hilt, StateFlow)
    ↓
Use Cases (domain/usecase/)
    ↓
Repositories (data/repository/)
    ↓
Data Sources (Room DB + BLE Buffers)
```

### Package Structure

```
com.example.wags/
├── di/                         # Hilt modules (4 files)
│   ├── AppModule.kt
│   ├── BleModule.kt
│   ├── DatabaseModule.kt
│   └── DispatcherModule.kt
├── data/
│   ├── ble/                    # BLE layer (6 files)
│   │   ├── PolarBleManager.kt
│   │   ├── BleService.kt
│   │   ├── BlePermissionManager.kt
│   │   ├── CircularBuffer.kt
│   │   ├── RxToFlowBridge.kt
│   │   └── AccRespirationEngine.kt
│   ├── db/                     # Room database (15 files)
│   │   ├── WagsDatabase.kt
│   │   ├── dao/                # 7 DAOs (incl. MorningReadinessDao)
│   │   └── entity/             # 7 entities (incl. MorningReadinessEntity)
│   └── repository/             # 5 repositories (incl. MorningReadinessRepository)
├── domain/
│   ├── model/                  # 15 domain models (incl. MorningReadinessResult, HooperIndex, OrthostasisMetrics)
│   └── usecase/                # 31 use case files
│       ├── apnea/              # ApneaCountdownTimer, StateMachine, etc.
│       ├── breathing/          # CoherenceScoreCalculator, ContinuousPacerEngine, RfAssessmentOrchestrator
│       ├── hrv/                # Artifact correction, FFT, PCHIP, time/freq domain calculators
│       ├── readiness/          # MorningReadinessFsm, MorningReadinessState, MorningReadinessTimer,
│       │                       #   MorningReadinessStateHandler, MorningReadinessOrchestrator,
│       │                       #   MorningReadinessScoreCalculator, ReadinessScoreCalculator,
│       │                       #   ThirtyFifteenRatioCalculator, OhrrCalculator, RespiratoryRateCalculator
│       └── session/            # HrSonificationEngine, NsdrAnalyticsCalculator, SessionExporter
└── ui/                         # 27 UI files
    ├── apnea/                  # ApneaScreen, ApneaTableScreen, ApneaViewModel
    ├── breathing/              # BreathingScreen, BreathingViewModel
    ├── dashboard/              # DashboardScreen (+ Morning Readiness card), DashboardViewModel
    ├── morning/                # MorningReadinessScreen, MorningReadinessViewModel,
    │                           #   MorningReadinessResultScreen, HelpBubble
    ├── navigation/             # WagsNavGraph (+ morning_readiness route)
    ├── readiness/              # ReadinessScreen, ReadinessViewModel
    ├── realtime/               # EcgChartView, TachogramView
    ├── session/                # SessionScreen, SessionViewModel
    ├── settings/               # SettingsScreen, SettingsViewModel
    └── theme/                  # Color, Theme, Type
```

### Dependency Flow

- **UI** depends only on ViewModels via `StateFlow`; never touches repositories or data sources directly.
- **ViewModels** depend on use cases and repositories; hold no business logic.
- **Use cases** are pure Kotlin classes (no Android framework imports) except where explicitly noted (e.g., `ApneaViewModel` handles `VibrationEffect` and `SoundPool`).
- **Repositories** abstract Room DAOs and BLE circular buffers behind a clean interface.
- **DI modules** wire everything together at app startup via Hilt.

---

## Setup Requirements

- **Android Studio** Hedgehog (2023.1.1) or newer
- **Android SDK**: `minSdk 26`, `compileSdk 36`
- **Polar BLE SDK** is fetched automatically via JitPack (configured in `settings.gradle.kts`)
- **No commercial chart licenses** required — real-time ECG and tachogram use a custom hardware-accelerated Canvas composable
- **BLE permissions** (see section below)
- A physical Android device with Bluetooth LE support (BLE cannot be tested on emulators)

---

## Build Instructions

```bash
# 1. Clone the repository
git clone <repo-url>
cd wags

# 2. Open in Android Studio
#    File → Open → select the wags/ directory

# 3. Sync Gradle
#    Android Studio will prompt; JitPack will download the Polar BLE SDK automatically

# 4. Connect a physical Android device (API 26+) with BLE support

# 5. Run the 'app' module via the Run toolbar or:
./gradlew :app:installDebug
```

---

## BLE Permission Notes

### Android 12+ (API 31+)
- `BLUETOOTH_SCAN` — required for scanning; granted at runtime
- `BLUETOOTH_CONNECT` — required to connect to paired devices; granted at runtime
- `ACCESS_FINE_LOCATION` — also requested alongside BLE permissions for Polar SDK compatibility

### Android < 12 (API 26–30)
- `ACCESS_FINE_LOCATION` is required for BLE scanning (Android platform requirement)
- `BLUETOOTH` and `BLUETOOTH_ADMIN` are normal permissions (no runtime grant needed)

### Runtime Permission Flow
- Permissions are requested in `SettingsScreen` at the moment the user taps **Connect**
- If denied, a banner explains how to grant them via system Settings → Apps → WAGS → Permissions

### Background Operation
- A **foreground service** (`BleService`) with `android:foregroundServiceType="connectedDevice"` keeps the BLE connection alive when the app is backgrounded
- `PARTIAL_WAKE_LOCK` prevents CPU sleep during active measurement sessions

## Device Connection

Device connections are managed in **Settings** (gear icon in the Dashboard top bar):

1. Tap **Scan** — the app requests BLE permissions if not yet granted, then calls `polarApi.searchForDevice()` to discover nearby Polar sensors
2. Each discovered device appears as a card showing its name and device ID
3. Tap **H10** or **Verity** on a card to assign that device to the corresponding role and connect immediately
4. The device ID is persisted in `SharedPreferences` and shown in the Connected Devices section on future launches
5. Tap **Disconnect** on a connected device to end the session

---

## Key Algorithms

### Artifact Correction (Two-Stage Pipeline)
1. **`RrPreFilter`** — Absolute bounds filter (300–2000 ms) followed by a rolling median filter; rejects physiologically impossible intervals before the main pipeline.
2. **Lipponen & Tarvainen 2019** — Three-phase classification:
   - `Phase1DifferenceSeries`: Computes successive RR differences and flags outliers
   - `Phase2MedianComparison`: Compares each interval against a local median window
   - `Phase3Classification`: Combines phase 1 and 2 flags to classify each beat as Normal, Ectopic, Missed, Extra, or Long
3. **`ArtifactCorrectionUseCase`**: Applies cubic spline interpolation to replace flagged beats before metric calculation.

### HRV Time-Domain Metrics
- **RMSSD** computed over a 60-second artifact-corrected buffer
- **SDNN** computed over a 5-minute buffer
- **pNN50** computed over the 60-second buffer
- All metrics are artifact-aware: flagged beats are excluded from difference calculations

### Frequency-Domain Pipeline
1. **`PchipResampler`** — PCHIP (Piecewise Cubic Hermite Interpolating Polynomial) resampling of the unevenly-spaced RR series to a uniform 4 Hz grid
2. **Linear detrend** — Removes mean and linear trend to reduce spectral leakage
3. **Hanning window** — Applied before FFT
4. **`FftProcessor`** — 256-point FFT (64 s × 4 Hz); uses Apache Commons Math `FastFourierTransformer`
5. **`PsdBandIntegrator`** — Trapezoidal integration over LF (0.04–0.15 Hz) and HF (0.15–0.40 Hz) bands; VLF excluded from sub-5-minute windows
6. **`FrequencyDomainCalculator`** — Computes LF/HF ratio, LFnu, HFnu, total power

### Readiness Score
- Computes `ln(RMSSD)` for the current session
- Z-scores against a 14-day personal baseline (mean ± SD)
- Piecewise linear mapping to 1–100:

| Z-score range | Score range | Interpretation |
|---|---|---|
| Z > 1.5 | 100 (capped) | OVERREACHING signal |
| Z in [0.5, 1.5] | 85–100 | ELEVATED |
| Z in [-0.5, 0.5] | 70–85 | OPTIMAL |
| Z in [-1.0, -0.5] | 55–70 | REDUCED |
| Z in [-2.0, -1.0] | 20–55 | LOW |
| Z < -2.0 | 1 (floor) | LOW / illness |

- Requires ≥ 3 days of baseline history; returns neutral score (70) if insufficient data

### Coherence Score
- Target band: resonance frequency ± 0.03 Hz
- Formula: `tanh(ratio × 2.0) × 100` where `ratio = LF_target / LF_total`
- Anti-sawtooth carry-forward smoothing prevents score oscillation
- Maximum update rate: 1 Hz

### RF Assessment
Three protocols: **Stepped**, **Continuous**, **Targeted**

Composite score formula:
```
score = phase_coherence × 0.40
      + peak_tracking   × 0.30
      + LFnu            × 0.20
      + RMSSD_norm      × 0.10
```

### ACC Respiration Estimation
- Uses H10 Z-axis (chest expansion axis) at 200 Hz
- Computes delta between a recent short window and an older reference window
- Hysteresis debounce prevents double-counting breath cycles
- Per-profile calibration stored in `AccCalibrationEntity`

---

## File Count Summary

| Package | Files |
|---|---|
| `domain/usecase/` (all sub-packages) | 31 |
| `data/db/` (entities + DAOs + database) | 15 |
| `domain/model/` | 15 |
| `ui/` (all screens + ViewModels + theme) | 27 |
| `data/ble/` | 6 |
| `data/repository/` | 5 |
| `di/` | 4 |
| Root (`MainActivity`, `WagsApplication`) | 2 |
| **Total** | **105** |

---

---

## Morning Readiness Feature

*Added: 2026-03-10T22:54:00Z*

### Overview

Morning Readiness is a guided orthostatic HRV protocol that runs each morning before the user gets out of bed. It captures supine and standing RR intervals, computes a suite of autonomic metrics, collects a brief subjective wellness questionnaire (Hooper Index), and produces a **0–100 readiness score** with RED / YELLOW / GREEN color coding.

The score is designed to answer: *"How recovered is my autonomic nervous system today, and is my body ready for training?"*

---

### Test Protocol — 11-State FSM

The protocol is driven by [`MorningReadinessFsm`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessFsm.kt) via [`MorningReadinessState`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessState.kt):

```
IDLE → INIT (5 s) → SUPINE_REST (60 s) → SUPINE_HRV (120 s)
     → STAND_PROMPT (3 s) → STAND_CAPTURE (60 s) → STAND_HRV (120 s)
     → QUESTIONNAIRE → CALCULATING → COMPLETE
                                    ↘ ERROR
```

| State | Duration | Purpose |
|---|---|---|
| `IDLE` | — | Waiting for user to start |
| `INIT` | 5 s | Device check / stabilisation |
| `SUPINE_REST` | 60 s | Quiet lying rest; sensor drop resets this window |
| `SUPINE_HRV` | 120 s | Active supine RR capture for HRV metrics |
| `STAND_PROMPT` | 3 s | Audio beep + haptic cue to stand up |
| `STAND_CAPTURE` | 60 s | Standing RR capture; peak HR tracked as minimum RR |
| `STAND_HRV` | 120 s | Post-stand RR capture for standing HRV metrics |
| `QUESTIONNAIRE` | User-paced | Hooper Index sliders (4 items, 1–5 scale) |
| `CALCULATING` | < 1 s | Algorithm runs on `mathDispatcher` |
| `COMPLETE` | — | Results displayed |
| `ERROR` | — | Unrecoverable failure; user can retry |

**Key FSM design decisions:**
- FSM is pure Kotlin — no Android framework imports
- ViewModel feeds RR data via [`MorningReadinessFsm.addRrInterval()`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessFsm.kt) — FSM never touches BLE directly
- Audio/haptic triggered via `onStandPromptReady` callback; ViewModel sets a `triggerStandAlert` flag in `UiState`; `LaunchedEffect` in the Compose layer handles the actual `ToneGenerator` + `VibrationEffect` call (no Android context in ViewModel)
- Sensor drop during `SUPINE_REST` handled by `restartSupineRest(scope)` which resets the 60 s timer

---

### Metrics Computed

| Metric | Source | Description |
|---|---|---|
| **RMSSD** (supine) | `TimeDomainHrvCalculator` | Root mean square of successive RR differences over 120 s supine window |
| **SDNN** (supine) | `TimeDomainHrvCalculator` | Standard deviation of NN intervals over 120 s supine window |
| **ln(RMSSD) × 20** | `MorningReadinessScoreCalculator` | Log-transformed RMSSD scaled to a 0–100-like range; primary HRV input to the scoring algorithm |
| **RMSSD** (standing) | `TimeDomainHrvCalculator` | Same metric computed over 120 s standing window |
| **SDNN** (standing) | `TimeDomainHrvCalculator` | Same metric computed over 120 s standing window |
| **30:15 Ratio** | [`ThirtyFifteenRatioCalculator`](app/src/main/java/com/example/wags/domain/usecase/readiness/ThirtyFifteenRatioCalculator.kt) | Shortest RR in beats 6–24 (peak sympathetic) ÷ longest RR in beats 21–39 (vagal rebound); requires ≥ 39 valid beats |
| **OHRR @ 20 s** | [`OhrrCalculator`](app/src/main/java/com/example/wags/domain/usecase/readiness/OhrrCalculator.kt) | Orthostatic HR Recovery: HR at 20 s post-peak as % drop from peak HR |
| **OHRR @ 60 s** | [`OhrrCalculator`](app/src/main/java/com/example/wags/domain/usecase/readiness/OhrrCalculator.kt) | HR at 60 s post-peak as % drop from peak HR; used in Orthostatic Multiplier |
| **Respiratory Rate** | [`RespiratoryRateCalculator`](app/src/main/java/com/example/wags/domain/usecase/readiness/RespiratoryRateCalculator.kt) | Estimated from RR amplitude modulations (RSA); `slowBreathingFlagged = true` if < 9 bpm |
| **Hooper Index** | [`HooperIndex`](app/src/main/java/com/example/wags/domain/model/HooperIndex.kt) | Sleep, fatigue, soreness, stress (1–5 each); total 4–20; `isLow` ≤ 10, `isHigh` ≥ 14 |

---

### Readiness Algorithm — 6-Step Conditional Limiting Architecture

Implemented in [`MorningReadinessScoreCalculator`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessScoreCalculator.kt) and orchestrated by [`MorningReadinessOrchestrator`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessOrchestrator.kt).

| Step | Name | Logic |
|---|---|---|
| 1 | **HRV_Base** | `ln(RMSSD) × 20` vs 30-day chronic baseline. Linear 90–100 within ±0.5 SD (SWC band). 90→30 below band (floor at −2 SD). Cap 75 above +1.5 SD (hyper-compensation guard). |
| 2 | **Orthostatic Multiplier (OM)** | Age-bracket thresholds (< 40 / 40–49 / 50+). OM = 1.0 / 0.90 / 0.70 based on 30:15 ratio + OHRR@60s status. Null ortho data → OM = 1.0. |
| 3 | **CV_Base** | `HRV_Base × OM` |
| 4 | **Volatility Penalty** | 7-day CV > 30-day CV × 1.30 → subtract 10 pts |
| 5 | **Hooper Gating** | Condition A (low CV + low Hooper): no double penalty. Condition B (high CV + low Hooper): −10 pts (−15 if total ≤ 8). Condition C (low CV + high Hooper): trust the math. |
| 6 | **RHR Hard Limiter** | Today's RHR > (90-day mean + 2.5 SD) OR > (90-day mean + 10 bpm) → cap score at 50. |

**Score color coding:**

| Score | Color | Interpretation |
|---|---|---|
| 70–100 | 🟢 GREEN | Ready for full training load |
| 40–69 | 🟡 YELLOW | Moderate readiness; consider reduced intensity |
| 0–39 | 🔴 RED | Poor recovery; prioritise rest |

**Confidence levels:** `PROVISIONAL` (< 7 days baseline), `MODERATE` (7–29 days), `HIGH` (≥ 30 days).

---

### Help Bubbles

[`HelpBubble`](app/src/main/java/com/example/wags/ui/morning/HelpBubble.kt) is a reusable `(i)` icon button that shows an `AlertDialog` with a title and body text on tap. [`MetricRowWithHelp`](app/src/main/java/com/example/wags/ui/morning/HelpBubble.kt) is a convenience composable that renders a label + bold value + `HelpBubble` in a single `Row`.

Metrics with help text in [`MorningReadinessResultScreen`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessResultScreen.kt):

| Metric | Help text topic |
|---|---|
| Readiness Score | What the 0–100 score means and how it's computed |
| RMSSD (supine & standing) | What RMSSD measures and why it matters |
| ln(RMSSD) × 20 | Why log-transform is used |
| SDNN | What SDNN captures vs RMSSD |
| 30:15 Ratio | How the orthostatic ratio is derived and what it indicates |
| OHRR @ 20 s / 60 s | What orthostatic HR recovery reveals about vagal tone |
| Respiratory Rate | RSA-based estimation and the slow-breathing flag |
| Hooper Index | Subjective wellness scoring and gating logic |
| HRV Base Score | Step 1 of the algorithm |
| Orthostatic Multiplier | Step 2 of the algorithm |

---

### Database Schema — `morning_readiness` Table (DB v3)

Added in [`MorningReadinessEntity`](app/src/main/java/com/example/wags/data/db/entity/MorningReadinessEntity.kt); migration in [`WagsDatabase`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt) (`MIGRATION_2_3`).

**28 columns across 7 groups:**

| Group | Columns |
|---|---|
| Identity | `id` (PK, auto), `timestamp` |
| Supine HRV | `supineRmssd`, `supineLnRmssd20`, `supineSdnn` |
| Standing HRV | `standingRmssd`, `standingLnRmssd20`, `standingSdnn` |
| Orthostatic | `peakHr`, `thirtyFifteenRatio`, `ohrrAt20s`, `ohrrAt60s` |
| Respiratory | `respiratoryRate`, `slowBreathingFlagged` |
| Hooper Index | `hooperSleep`, `hooperFatigue`, `hooperSoreness`, `hooperStress`, `hooperTotal` (all nullable) |
| Algorithm output | `readinessScore`, `readinessColor`, `hrvBaseScore`, `orthoMultiplier`, `volatilityPenaltyApplied`, `rhrLimiterApplied`, `supineArtifactPct`, `standingArtifactPct` |

[`MorningReadinessDao`](app/src/main/java/com/example/wags/data/db/dao/MorningReadinessDao.kt) provides 12 queries including rolling-window column queries for baseline computation. [`MorningReadinessRepository`](app/src/main/java/com/example/wags/data/repository/MorningReadinessRepository.kt) exposes 7-day acute, 30-day chronic, and 90-day history helpers.

---

### Cubic Spline Upgrade — `Phase3Classification`

[`Phase3Classification`](app/src/main/java/com/example/wags/domain/usecase/hrv/Phase3Classification.kt) ectopic beat correction was upgraded from neighbor average to **cubic spline interpolation** via Apache Commons Math3 `SplineInterpolator`. Uses up to 5 valid beats on each side as context; falls back to neighbor average when fewer than 4 context points are available. Cumulative time is used as the spline x-axis. This upgrade benefits all HRV windows including the Morning Readiness supine and standing captures.

---

## Session Recording

### Sessions Without a Heart Rate Monitor

Sessions can be started and recorded without any Polar device connected. The session screen shows a monitor status banner:

- **Green dot** — monitor connected; full HR + HRV analytics will be recorded
- **Grey dot** — no monitor; session is recorded as a timer-only entry (duration + session type only)

The "Start Session" button is always enabled regardless of monitor state. When no monitor is connected the button label reads *"Start Session (no HR monitor)"*.

### Monitor Tracking in History

Every session record in the `session_logs` table now includes a `monitorId` column (nullable `TEXT`):

| `monitorId` value | Meaning |
|---|---|
| `NULL` | Session recorded without a heart rate monitor |
| `"<device-id>"` | Session recorded with the named Polar device |

This enables filtering history and stats by monitor:

```kotlin
// All sessions from a specific monitor
sessionRepository.getByMonitor("B36F1234")

// Only sessions that have HR data
sessionRepository.getWithHrData()

// Only sessions without HR data
sessionRepository.getWithoutHrData()
```

### Database Migration (v1 → v2)

`WagsDatabase` was bumped from **v1 → v2**. The migration (`MIGRATION_1_2`) recreates `session_logs` with:
- All HR columns made nullable (`REAL` without `NOT NULL`)
- New `monitorId TEXT` column (nullable)

Existing v1 rows are preserved with `monitorId = NULL` and their original HR values intact.

### Database Migration (v2 → v3) — Morning Readiness

`WagsDatabase` was bumped from **v2 → v3**. The migration (`MIGRATION_2_3`) creates the new `morning_readiness` table with 28 columns covering:
- Supine and standing HRV metrics (RMSSD, ln(RMSSD), SDNN)
- Orthostatic metrics (peak HR, 30:15 ratio, OHRR at 20s and 60s)
- Respiratory rate and slow-breathing flag
- Hooper Index (sleep, fatigue, soreness, stress, total) — all nullable
- Data quality (artifact percentages for each window)
- Final readiness score, color, HRV base score, orthostatic multiplier, and penalty flags

New files added in the v3 schema sprint:

| File | Type |
|---|---|
| `domain/model/MorningReadinessResult.kt` | Domain model + `ReadinessColor` enum |
| `domain/model/HooperIndex.kt` | Domain model with computed `total`, `isLow`, `isHigh` |
| `domain/model/OrthostasisMetrics.kt` | Domain model for orthostatic stand metrics |
| `data/db/entity/MorningReadinessEntity.kt` | Room entity (`morning_readiness` table) |
| `data/db/dao/MorningReadinessDao.kt` | DAO with 12 queries incl. rolling-window baseline queries |
| `data/repository/MorningReadinessRepository.kt` | Repository with 7-/30-/90-day history helpers |

### Sprint: Orthostatic Use Cases + Cubic Spline Upgrade (2026-03-10)

**Modified:**

| File | Change |
|---|---|
| `domain/usecase/hrv/Phase3Classification.kt` | Ectopic beat correction upgraded from neighbor average to **cubic spline interpolation** via `SplineInterpolator` (Apache Commons Math3). Uses up to 5 valid beats on each side as context; falls back to neighbor average when fewer than 4 context points are available. Cumulative time is used as the spline x-axis. MISSED and EXTRA beat logic unchanged. |

**Created:**

| File | Purpose |
|---|---|
| `domain/usecase/readiness/ThirtyFifteenRatioCalculator.kt` | Computes the 30:15 ratio: shortest RR in beats 6–24 (peak sympathetic) and longest RR in beats 21–39 (vagal rebound). Returns `null` if fewer than 39 valid beats. |
| `domain/usecase/readiness/OhrrCalculator.kt` | Computes Orthostatic HR Recovery (OHRR): HR at 20 s and 60 s post-peak, percentage drop from peak, and full `OrthostasisMetrics` output. |
| `domain/usecase/readiness/RespiratoryRateCalculator.kt` | Estimates respiratory rate from RR amplitude modulations (RSA) by counting local maxima over recording duration. Sets `slowBreathingFlagged = true` if rate < 9 bpm. |

### Sprint: Morning Readiness Algorithm — Conditional Limiting Architecture (2026-03-10)

**Created:**

| File | Purpose |
|---|---|
| `domain/usecase/readiness/MorningReadinessScoreCalculator.kt` | 6-step scoring algorithm: HRV_Base (SWC band linear scaling), Orthostatic Multiplier (age-bracket 30:15 + OHRR@60s), CV_Base, Volatility Penalty (7-day vs 30-day CV), Hooper Index Gating (3 conditions), RHR Hard Limiter. Returns `Output` with full diagnostic breakdown and `ConfidenceLevel` (PROVISIONAL/MODERATE/HIGH). |
| `domain/usecase/readiness/MorningReadinessOrchestrator.kt` | Full pipeline orchestrator: runs artifact correction → HRV metrics → supine RHR → orthostatic metrics → respiratory rate → baseline fetch → score calculation → `MorningReadinessResult`. Calls `ArtifactCorrectionUseCase.execute()` (not `.correct()`). |

**Algorithm steps implemented in `MorningReadinessScoreCalculator`:**

| Step | Logic |
|---|---|
| 1. HRV_Base | `ln(RMSSD) × 20` vs 30-day chronic baseline; linear 90–100 within ±0.5 SD SWC band; 90→30 below band (floor at −2 SD); cap 75 above +1.5 SD (hyper-compensation) |
| 2. Orthostatic Multiplier | Age-bracket thresholds (< 40 / 40–49 / 50+); OM = 1.0 / 0.90 / 0.70 based on 30:15 ratio + OHRR@60s status; null ortho data → OM = 1.0 |
| 3. CV_Base | `HRV_Base × OM` |
| 4. Volatility Penalty | 7-day CV > 30-day CV × 1.30 → subtract 10 pts |
| 5. Hooper Gating | Condition A (low CV + low Hooper): no double penalty; Condition B (high CV + low Hooper): −10 pts (−15 if total ≤ 8); Condition C (low CV + high Hooper): trust the math |
| 6. RHR Limiter | Today's RHR > (90-day mean + 2.5 SD) OR > (90-day mean + 10 bpm) → cap at 50 |

**Color coding:** 0–39 = RED, 40–69 = YELLOW, 70–100 = GREEN

### Sprint: Morning Readiness UI Layer (2026-03-10)

**Created:**

| File | Purpose |
|---|---|
| `ui/morning/MorningReadinessViewModel.kt` | `@HiltViewModel` with `MorningReadinessUiState` data class. Collects `fsm.state` and `fsm.remainingSeconds` into `StateFlow`. Sets FSM callbacks: `onStandPromptReady` sets `triggerStandAlert = true` flag (no Android context in VM); `onReadyToCalculate` launches computation on `mathDispatcher`, saves to repository on `ioDispatcher`, then calls `fsm.markComplete()`. Polls `bleManager.rrBuffer` every 1 s to feed new `RrInterval`s to the FSM and compute live RMSSD. Exposes `updateHooper()`, `submitHooper()`, `reset()`, `acknowledgeStandAlert()`. Includes private `toEntity()` extension to map `MorningReadinessResult → MorningReadinessEntity`. |
| `ui/morning/MorningReadinessScreen.kt` | FSM-driven Compose screen. `LaunchedEffect(triggerStandAlert)` plays `ToneGenerator.TONE_PROP_BEEP2` and fires a `VibrationEffect.createWaveform` haptic pattern, then calls `acknowledgeStandAlert()`. Renders 11 sub-composables keyed to each `MorningReadinessState`: `IdleContent`, `InitContent`, `SupineRestContent`, `SupineHrvContent`, `StandPromptContent` (pulsing scale animation), `StandCaptureContent`, `StandHrvContent`, `QuestionnaireContent` (4 `HooperSlider` cards), `CalculatingContent`, `MorningReadinessResultScreen`, `ErrorContent`. Shared helpers: `CountdownCircle` (circular progress + large digit), `PulsingDot`. |
| `ui/morning/MorningReadinessResultScreen.kt` | Full results display split out to keep screen file under 500 lines. Shows: readiness score card (color-coded RED/YELLOW/GREEN), slow-breathing flag banner, supine HRV card, standing HRV card, orthostatic card (30:15 ratio + OHRR), Hooper summary card, algorithm details card (HRV base, ortho multiplier, CV penalty chip, RHR limiter chip, artifact percentages). Every key metric has a `HelpBubble` or `MetricRowWithHelp` with exact spec-defined help text. |
| `ui/morning/HelpBubble.kt` | Reusable `(i)` icon button that shows an `AlertDialog` with title + body text on tap. Companion `MetricRowWithHelp` composable renders a label + bold value + `HelpBubble` in a single `Row`. |

**Key design decisions:**
- ViewModel holds **no Android Context** — audio/haptic triggered via `triggerStandAlert: Boolean` flag in `UiState`, handled by `LaunchedEffect` in the Compose layer
- `MorningReadinessUiState` defined in the same file as the ViewModel (matches `ReadinessViewModel` pattern)
- `CompleteContent` split into `MorningReadinessResultScreen.kt` to keep both files under 500 lines
- `collectAsStateWithLifecycle()` used throughout (matches existing screen pattern)
- `hiltViewModel()` used in the screen composable entry point

---

### Sprint: Morning Readiness FSM (2026-03-10)

**Created:**

| File | Purpose |
|---|---|
| `domain/usecase/readiness/MorningReadinessState.kt` | Enum of 11 states: `IDLE`, `INIT`, `SUPINE_REST`, `SUPINE_HRV`, `STAND_PROMPT`, `STAND_CAPTURE`, `STAND_HRV`, `QUESTIONNAIRE`, `CALCULATING`, `COMPLETE`, `ERROR` |
| `domain/usecase/readiness/MorningReadinessTimer.kt` | Countdown timer exposing `remainingSeconds: StateFlow<Int>`; `start(scope, durationSeconds, onComplete)`, `cancel()`, `reset()` |
| `domain/usecase/readiness/MorningReadinessStateHandler.kt` | State machine handler with `transitionTo()`, `setError()`, `reset()`, and `canTransitionTo()` guard map |
| `domain/usecase/readiness/MorningReadinessFsm.kt` | Main FSM orchestrator: manages supine/standing RR buffers, peak stand HR tracking, auto-advances through all 8 states via timer callbacks; exposes `onStandPromptReady`, `onQuestionnaireRequired`, `onReadyToCalculate` callbacks for ViewModel |

**FSM state flow:**
```
IDLE → INIT (5s) → SUPINE_REST (60s) → SUPINE_HRV (120s) → STAND_PROMPT (3s)
     → STAND_CAPTURE (60s) → STAND_HRV (120s) → QUESTIONNAIRE → CALCULATING → COMPLETE
```

**Key design decisions:**
- FSM is pure Kotlin — no Android framework imports
- ViewModel feeds RR data via `addRrInterval(rr)` — FSM never touches BLE directly
- Audio/haptic triggered via `onStandPromptReady` callback in ViewModel
- Sensor drop during `SUPINE_REST` handled by `restartSupineRest(scope)` which resets the 60s timer
- Peak stand HR tracked as minimum RR interval during `STAND_CAPTURE`

---

## Changelog

### 2026-03-10T22:54:00Z — Morning Readiness (Full Feature)

26 new files across all layers; DB bumped to v3.

**Domain models added:**
- [`MorningReadinessResult.kt`](app/src/main/java/com/example/wags/domain/model/MorningReadinessResult.kt) — Primary result model (25 fields) + `ReadinessColor` enum (RED/YELLOW/GREEN)
- [`HooperIndex.kt`](app/src/main/java/com/example/wags/domain/model/HooperIndex.kt) — Hooper questionnaire model (sleep, fatigue, soreness, stress; 1–5 scale each)
- [`OrthostasisMetrics.kt`](app/src/main/java/com/example/wags/domain/model/OrthostasisMetrics.kt) — Orthostatic stand metrics (30:15 ratio, OHRR at 20 s / 60 s)

**Database layer added:**
- [`MorningReadinessEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/MorningReadinessEntity.kt) — Room entity, table `morning_readiness`, 28 columns
- [`MorningReadinessDao.kt`](app/src/main/java/com/example/wags/data/db/dao/MorningReadinessDao.kt) — 12 queries including rolling-window column queries and cleanup
- [`MorningReadinessRepository.kt`](app/src/main/java/com/example/wags/data/repository/MorningReadinessRepository.kt) — 7-day acute, 30-day chronic, 90-day history helpers

**Use cases added:**
- [`MorningReadinessState.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessState.kt) — 11-state FSM enum
- [`MorningReadinessTimer.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessTimer.kt) — Countdown timer with `StateFlow`
- [`MorningReadinessStateHandler.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessStateHandler.kt) — State transitions with guard map
- [`MorningReadinessFsm.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessFsm.kt) — Main FSM orchestrator
- [`ThirtyFifteenRatioCalculator.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/ThirtyFifteenRatioCalculator.kt) — 30:15 ratio computation
- [`OhrrCalculator.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/OhrrCalculator.kt) — OHRR at 20 s and 60 s post-peak
- [`RespiratoryRateCalculator.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/RespiratoryRateCalculator.kt) — RSA-based respiratory rate; slow-breathing flag
- [`MorningReadinessScoreCalculator.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessScoreCalculator.kt) — 6-step Conditional Limiting Architecture algorithm
- [`MorningReadinessOrchestrator.kt`](app/src/main/java/com/example/wags/domain/usecase/readiness/MorningReadinessOrchestrator.kt) — Full computation pipeline orchestrator

**UI layer added:**
- [`HelpBubble.kt`](app/src/main/java/com/example/wags/ui/morning/HelpBubble.kt) — Reusable `(i)` info icon + `MetricRowWithHelp` composable
- [`MorningReadinessViewModel.kt`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessViewModel.kt) — `@HiltViewModel` with FSM integration and BLE polling
- [`MorningReadinessScreen.kt`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessScreen.kt) — 11 sub-composables for each FSM state
- [`MorningReadinessResultScreen.kt`](app/src/main/java/com/example/wags/ui/morning/MorningReadinessResultScreen.kt) — Full results display with all Help Bubbles

**Modified files:**
- [`WagsDatabase.kt`](app/src/main/java/com/example/wags/data/db/WagsDatabase.kt) — Version 2 → 3; `MorningReadinessEntity` added; `MIGRATION_2_3` added
- [`Phase3Classification.kt`](app/src/main/java/com/example/wags/domain/usecase/hrv/Phase3Classification.kt) — Ectopic correction upgraded from neighbor average to cubic spline interpolation (Apache Commons Math3 `SplineInterpolator`)
- [`DatabaseModule.kt`](app/src/main/java/com/example/wags/di/DatabaseModule.kt) — Added `provideMorningReadinessDao()`
- [`AppModule.kt`](app/src/main/java/com/example/wags/di/AppModule.kt) — Added `provideMorningReadinessRepository()`
- [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt) — Added `MORNING_READINESS = "morning_readiness"` route + composable destination
- [`DashboardScreen.kt`](app/src/main/java/com/example/wags/ui/dashboard/DashboardScreen.kt) — Added "Morning Readiness" navigation card

---

## Known Limitations / Future Work

1. **14-day historical chart** — Vico chart data is wired in `ReadinessViewModel` but the historical trend composable is not yet rendered in `ReadinessScreen`. This is a placeholder for a future sprint.

2. **`SoundPool` beep resource** — `ApneaViewModel` initialises `SoundPool` and calls `triggerWarning` for haptic cues, but the audio beep requires an actual `R.raw.beep` audio resource file to be added to `app/src/main/res/raw/`. The haptic vibration path is fully functional.

3. **CWT for Continuous RF protocol** — The Continuous Wavelet Transform needed for time-frequency coherence tracking in the `CONTINUOUS` RF protocol is not yet implemented. The protocol runs its timing state machine correctly but uses FFT-based coherence as a proxy metric.

4. **Export formats** — `SessionExporter` currently writes CSV. JSON and HRV4Training-compatible export formats are planned.

5. **Single-device sessions** — The app supports simultaneous H10 + Verity Sense connections in the BLE layer, but the UI currently presents single-device workflows per screen.

6. **Device discovery** — The Settings screen uses `polarApi.searchForDevice()` to scan for nearby Polar sensors. Devices appear as selectable cards; tapping assigns them to the H10 or Verity Sense role.
