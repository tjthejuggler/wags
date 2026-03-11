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
│   │   ├── PolarBleManager.kt       # Polar H10/Verity Sense RR streaming
│   │   ├── OximeterBleManager.kt    # Wellue/Viatom SpO₂ BLE scanning & GATT
│   │   ├── BleService.kt            # Foreground service (connectedDevice type)
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
    ├── breathing/      BreathingScreen, BreathingViewModel
    ├── common/         InfoHelpBubble (reusable ⓘ bottom-sheet)
    ├── dashboard/      DashboardScreen, DashboardViewModel
    ├── morning/        MorningReadinessScreen, MorningReadinessResultScreen,
    │                   MorningReadinessViewModel
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

The Room database (`wags.db`) is at version 4 with 9 tables:

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
