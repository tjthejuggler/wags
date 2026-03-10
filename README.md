# WAGS — Cardiac Biofeedback App
*Last updated: 2026-03-10 (live HR top bar + BLE scan-and-select)*

---

## Overview

WAGS is a clinical-grade Android biofeedback application designed for athletes, breath-hold divers, and practitioners who want to monitor and train their autonomic nervous system using Polar heart-rate hardware.

**Four core features:**

| Feature | Description |
|---|---|
| **Morning HRV Readiness** | Computes a 1–100 readiness score from ln(RMSSD) Z-scored against a 14-day personal baseline |
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
│   ├── db/                     # Room database (11 files)
│   │   ├── WagsDatabase.kt
│   │   ├── dao/                # 5 DAOs
│   │   └── entity/             # 5 entities
│   └── repository/             # 3 repositories
├── domain/
│   ├── model/                  # 9 domain models
│   └── usecase/                # 21 use case files
│       ├── apnea/              # ApneaCountdownTimer, StateMachine, etc.
│       ├── breathing/          # CoherenceScoreCalculator, ContinuousPacerEngine, RfAssessmentOrchestrator
│       ├── hrv/                # Artifact correction, FFT, PCHIP, time/freq domain calculators
│       ├── readiness/          # ReadinessScoreCalculator
│       └── session/            # HrSonificationEngine, NsdrAnalyticsCalculator, SessionExporter
└── ui/                         # 19 UI files
    ├── apnea/                  # ApneaScreen, ApneaTableScreen, ApneaViewModel
    ├── breathing/              # BreathingScreen, BreathingViewModel
    ├── dashboard/              # DashboardScreen, DashboardViewModel
    ├── navigation/             # WagsNavGraph
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
| `domain/usecase/` (all sub-packages) | 21 |
| `data/db/` (entities + DAOs + database) | 11 |
| `domain/model/` | 9 |
| `ui/` (all screens + ViewModels + theme) | 19 |
| `data/ble/` | 6 |
| `data/repository/` | 3 |
| `di/` | 4 |
| Root (`MainActivity`, `WagsApplication`) | 2 |
| **Total** | **73** |

---

## Known Limitations / Future Work

1. **14-day historical chart** — Vico chart data is wired in `ReadinessViewModel` but the historical trend composable is not yet rendered in `ReadinessScreen`. This is a placeholder for a future sprint.

2. **`SoundPool` beep resource** — `ApneaViewModel` initialises `SoundPool` and calls `triggerWarning` for haptic cues, but the audio beep requires an actual `R.raw.beep` audio resource file to be added to `app/src/main/res/raw/`. The haptic vibration path is fully functional.

3. **CWT for Continuous RF protocol** — The Continuous Wavelet Transform needed for time-frequency coherence tracking in the `CONTINUOUS` RF protocol is not yet implemented. The protocol runs its timing state machine correctly but uses FFT-based coherence as a proxy metric.

4. **Export formats** — `SessionExporter` currently writes CSV. JSON and HRV4Training-compatible export formats are planned.

5. **Single-device sessions** — The app supports simultaneous H10 + Verity Sense connections in the BLE layer, but the UI currently presents single-device workflows per screen.

6. **Device discovery** — The Settings screen uses `polarApi.searchForDevice()` to scan for nearby Polar sensors. Devices appear as selectable cards; tapping assigns them to the H10 or Verity Sense role.
