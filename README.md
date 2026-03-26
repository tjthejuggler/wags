# Wags — Professional Freediving Training Ecosystem

---

## Changelog

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

Wags is a professional-grade Android freediving training app built with Kotlin and Jetpack Compose. It combines HRV-based readiness assessment, structured apnea table training, unified BLE-connected biometric monitoring (any BLE device — Polar H10/Verity Sense, Wellue/Viatom oximeters, generic HR sensors — auto-detected by name), and real-time safety monitoring into a single cohesive training ecosystem.

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
- **Unified BLE Integration** — Single scan/connect flow for any BLE device; device type auto-detected from advertised name (Polar H10, Verity Sense, Wellue OxySmart, Viatom PC-60F, generic HR sensors); capabilities (HR, RR, ECG, ACC, PPI, SpO₂) determined per device type
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
