# WAGS — System Patterns

*Last updated: 2026-04-10 07:39 UTC-6*

## Architecture Pattern

**Clean Architecture** with three layers:
- **data/** — BLE, Room DB, repositories, Garmin, Spotify, IPC
- **domain/** — Pure Kotlin models and use cases (no Android dependencies)
- **ui/** — Jetpack Compose screens + ViewModels (MVVM)

## Dependency Injection

**Hilt** with modules in `di/`:
- [`AppModule.kt`](app/src/main/java/com/example/wags/di/AppModule.kt) — Application-scoped bindings
- [`DatabaseModule.kt`](app/src/main/java/com/example/wags/di/DatabaseModule.kt) — Room DB singleton + all DAOs
- [`BleModule.kt`](app/src/main/java/com/example/wags/di/BleModule.kt) — Polar BLE SDK instance
- [`DispatcherModule.kt`](app/src/main/java/com/example/wags/di/DispatcherModule.kt) — Named dispatchers (`@IoDispatcher`, `@MathDispatcher`, `@MainDispatcher`)
- [`GarminModule.kt`](app/src/main/java/com/example/wags/di/GarminModule.kt) — Garmin Connect IQ SDK

## BLE Data Flow Pattern

```
Polar SDK (RxJava3) → RxToFlowBridge → PolarBleManager → CircularBuffer → ViewModel (16ms poll) → UI
```

- `CircularBuffer<T>` — Pre-allocated ring buffer with `AtomicInteger` write pointer + `ReentrantReadWriteLock`
- BLE callbacks never touch UI directly; ViewModel polls buffer at ~60 FPS
- RR buffer: 1024 intervals, ECG buffer: 8192 samples, PPI buffer: 1024 intervals

## Coroutine Dispatcher Strategy

- `@IoDispatcher` → `Dispatchers.IO` — BLE callbacks, DB operations
- `@MathDispatcher` → Custom 2-thread pool — FFT, artifact correction, HRV calculations
- `@MainDispatcher` → `Dispatchers.Main.immediate` — UI updates only

## HRV Processing Pipeline

```
Raw RR → RrPreFilter → ArtifactCorrectionUseCase (Phase1 → Phase2 → Phase3) → Clean NN array
Clean NN → TimeDomainHrvCalculator → RMSSD, lnRMSSD, SDNN, pNN50
Clean NN → PchipResampler → FftProcessor → PsdBandIntegrator → FrequencyDomainCalculator → VLF, LF, HF power
```

Artifact correction follows **Lipponen & Tarvainen 2019** — split into Phase1DifferenceSeries, Phase2MedianComparison, Phase3Classification for modularity.

## Room Database Pattern

- Entities in `data/db/entity/` — 17 entity types
- DAOs in `data/db/dao/` — 17 DAO interfaces
- Single `WagsDatabase` class
- Repositories in `data/repository/` — 10 repository classes wrapping DAOs

## Navigation Pattern

- Single `WagsNavGraph` with `NavHost` and string-based routes
- Routes defined in `WagsRoutes` object with helper functions for parameterized routes
- Each feature has its own screen + ViewModel pair

## UI Patterns

- **Screens** are `@Composable` functions receiving navigation callbacks
- **ViewModels** are `@HiltViewModel` with `StateFlow` for UI state
- **Real-time charts** use custom `Canvas` composables (hardware-accelerated)
- **Historical charts** use Vico library
- **Common components** in `ui/common/` — AdviceBanner, InfoHelpBubble, LiveSensorTopBar, RrStripChart, etc.

## Garmin Integration Pattern

- Connect IQ Companion SDK (`ciq-companion-app-sdk:2.3.0`)
- `GarminManager` handles device discovery and communication
- `GarminApneaRepository` syncs free hold data from watch to phone
- Watch app is a separate Monkey C project in `garmin/` directory (do NOT modify unless specifically instructed)

## File Organization Rules

- No file > 500 lines
- Large use cases split into orchestrator + sub-components
- Feature-based UI package structure (`ui/apnea/`, `ui/breathing/`, `ui/meditation/`, etc.)

## Apnea Drill State Machine Patterns (2026-04-09 15:32 UTC-6)

Two distinct state machine patterns exist for apnea drills:

### Timer-Driven (Progressive O₂, O₂/CO₂ Tables)
- State machine controls phase transitions automatically via countdown timers
- Phases: IDLE → HOLD (countdown) → BREATHING (countdown) → next round or COMPLETE
- Audio/haptic cues fire at phase transitions and during final countdown seconds
- User only controls start/stop; the timer drives everything else
- Example: [`ProgressiveO2StateMachine.kt`](app/src/main/java/com/example/wags/domain/usecase/apnea/ProgressiveO2StateMachine.kt)

### User-Driven (Min Breath)
- State machine only tracks elapsed time; the **user** controls all phase transitions by tapping buttons
- Phases: IDLE → HOLD (count-up) → BREATHING (count-up) → HOLD → … → COMPLETE (when session duration reached)
- No audio/haptic cues during session (nothing to warn about since user decides when to breathe/hold)
- Only `announceSessionComplete()` fires when total elapsed time reaches session duration
- 100ms tick loop with wall-clock deltas for smooth count-up display (vs 1000ms for countdown timers)
- HOLD phase shows "First Contraction" + "Breath" buttons; BREATHING phase shows "HOLD" button
- Example: [`MinBreathStateMachine.kt`](app/src/main/java/com/example/wags/domain/usecase/apnea/MinBreathStateMachine.kt)

### Shared Patterns (both types)
- All state machines are `@Singleton` with `@Inject constructor()`
- State exposed via `StateFlow<State>` with immutable data class
- Both save 4 entities: `ApneaSessionEntity`, `ApneaRecordEntity`, `TelemetryEntity`, `FreeHoldTelemetryEntity`
- Both use `tableParamsJson` for drill-specific per-session data
- Both reuse shared `ApneaRecordDetailScreen` for post-session details (no custom detail screens)

## Universal Trophy System (DrillContext) (2026-04-09 20:23 UTC-6)

The trophy/personal-best system is generalized via `DrillContext` to work with any drill type:

### DrillContext Abstraction
- [`DrillContext`](app/src/main/java/com/example/wags/domain/model/DrillContext.kt) identifies a PB pool: `drillType` (null=free hold) + `drillParamValue` (e.g. breathPeriodSec, sessionDurationSec)
- Factory methods: `FREE_HOLD`, `PROGRESSIVE_O2_ANY`, `MIN_BREATH_ANY`, `progressiveO2(breathPeriodSec)`, `minBreath(sessionDurationSec)`, `fromNavArgs()`
- "ANY" variants (null `drillParamValue`) match all records of a drill type — used for main screen trophy display and cross-param-value PB screens
- `fromNavArgs()` returns "ANY" variant when `drillParamValue` is null (2026-04-09 20:31 UTC-6)
- `drillParamValue` column on `apnea_records` table partitions PBs by drill-specific parameter

### How PBs Work Per Drill
- **Free Hold:** `tableType IS NULL` — one PB pool (just 5 apnea settings)
- **Progressive O₂:** `tableType='PROGRESSIVE_O2' AND drillParamValue=X` — separate PB pool per breath period
- **Min Breath:** `tableType='MIN_BREATH' AND drillParamValue=X` — separate PB pool per session duration

### Adding a New Drill's Trophy System
1. Add `DrillContext.myDrill(param)` factory method
2. Set `drillParamValue` when saving `ApneaRecordEntity`
3. Call `apneaRepository.checkBroaderPersonalBest(drill, ...)` before saving
4. Add `newPersonalBest: PersonalBestResult?` to UI state
5. Show `NewPersonalBestDialog` in active screen
6. Add "🏆 Personal Bests" button navigating to `WagsRoutes.personalBests(drillType, drillParamValue)`
7. Add trophy display to main ApneaScreen section using `DrillSectionContent` composable with `getDrillBestAndTrophy()` (2026-04-09 20:31 UTC-6)
- No new screens needed — reuses existing `PersonalBestsScreen`, `PbChartScreen`, `NewPersonalBestDialog`

### Reused UI Components (unchanged)
- `NewPersonalBestDialog` — confetti + sound celebration dialog
- `ConfettiOverlay` — Canvas particle system
- `ApneaPbSoundPlayer` — 6 tiered celebration sounds
- `PersonalBestsScreen` — full trophy hierarchy display
- `PbChartScreen` — landscape line chart with zoom/pan

## Audio/Haptic Indication System (2026-04-10 07:39 UTC-6)

### Architecture
- [`ApneaAudioHapticEngine`](app/src/main/java/com/example/wags/domain/usecase/apnea/ApneaAudioHapticEngine.kt) — `@Singleton` with `@Inject constructor`, provides TTS + vibration
- Voice and vibration independently toggleable via `voiceEnabled` / `vibrationEnabled` properties
- Settings persisted in `@Named("apnea_prefs") SharedPreferences` (`apnea_voice_enabled`, `apnea_vibration_enabled`)
- Safety abort calls (`vibrateAbort()`, `announceAbort()`) always fire regardless of settings

### Countdown Vibration Pattern
- During last 10s of VENTILATION/BREATHING phase: 80ms medium-amplitude tick every second
- **Final tick** (1s remaining): 400ms high-amplitude pulse — signals "hold starts NOW"
- Hold end: 500ms high-amplitude pulse
- Contraction logged: 80ms low-amplitude pulse

### Voice Announcement Pattern
- Phase transitions ("Hold", "Breathe"): prefixed with 500ms silence via `playSilentUtterance()` to prevent audio clipping
- Countdown numbers (10-1): no silence prefix (rapid-fire)
- Session complete: no silence prefix

### UI Toggle Pattern
- [`VoiceVibrationToggles`](app/src/main/java/com/example/wags/ui/apnea/VoiceVibrationToggles.kt) — reusable composable with two checkboxes
- Shown on setup screens for timer-driven drills (Progressive O₂, O₂/CO₂ tables)
- NOT shown on user-driven drills (Min Breath — no countdowns)
- Each ViewModel exposes `voiceEnabled`/`vibrationEnabled` in UI state + `setVoiceEnabled()`/`setVibrationEnabled()` setters

## Picture-in-Picture (PiP) Pattern (2026-04-20 15:15 UTC-4)

### Overview
Native Android PiP (API 26+) — no SYSTEM_ALERT_WINDOW overlay needed.
Auto-enters PiP when user presses Home/switches apps during an active session.

### How to opt a session screen into PiP

1. **Wrap the screen composable** with `PipSessionHost`:
   ```kotlin
   PipSessionHost(
       pipEnabled = isActive || phase == COMPLETE,
       pipContent = { MyDrillPipContent(viewModel = viewModel) },
       fullContent = { MyDrillScreenContent(...) }
   )
   ```

2. **Create a `*PipContent.kt`** in `ui/<feature>/pip/` that:
   - Observes the ViewModel state
   - Builds a `pipActions` list reactively (max 3 OS overlay buttons)
   - Pushes actions via `LaunchedEffect(pipActions) { PipController.setActions(activity, pipActions) }`
   - Collects `PipController.actionFlow` to handle OS button taps
   - Renders `PipRoot { ... }` with `PipTimerText`, `PipButtonRow`, `PipResultCard` etc.

3. **Add `restartSameSession()`** to the ViewModel — calls `cancelSession()` then `startSession()` with same params.

### Key files
- `ui/common/pip/PipController.kt` — singleton; `isInPipMode: StateFlow<Boolean>`, `actionFlow: SharedFlow<String>`
- `ui/common/pip/PipAction.kt` — `PipAction` data class, `PipActionIds` constants, `PipActionReceiver`
- `ui/common/pip/PipSessionHost.kt` — composable wrapper
- `ui/common/pip/MiniSessionLayout.kt` — `PipRoot`, `PipTimerText`, `PipLabel`, `PipButton`, `PipButtonRow`, `PipResultCard`
- `MainActivity.kt` — `onUserLeaveHint()`, `onPictureInPictureModeChanged()`, receiver registration

### Constraints
- OS allows max 3 RemoteAction buttons in the PiP overlay
- `configChanges` flags in AndroidManifest prevent Activity recreation on PiP enter/exit
- `PipController.setPipEligible(false)` is called automatically on composition disposal via `DisposableEffect`

## Picture-in-Picture (PiP) Pattern (2026-04-20 15:15 UTC-4)

### Overview
Native Android PiP (API 26+) — no SYSTEM_ALERT_WINDOW overlay needed.
Auto-enters PiP when user presses Home/switches apps during an active session.

### How to opt a session screen into PiP

1. **Wrap the screen composable** with `PipSessionHost`:
   ```kotlin
   PipSessionHost(
       pipEnabled = isActive || phase == COMPLETE,
       pipContent = { MyDrillPipContent(viewModel = viewModel) },
       fullContent = { MyDrillScreenContent(...) }
   )
   ```

2. **Create a `*PipContent.kt`** in `ui/<feature>/pip/` that:
   - Observes the ViewModel state
   - Builds a `pipActions` list reactively (max 3 OS overlay buttons)
   - Pushes actions via `LaunchedEffect(pipActions) { PipController.setActions(activity, pipActions) }`
   - Collects `PipController.actionFlow` to handle OS button taps
   - Renders `PipRoot { ... }` with `PipTimerText`, `PipButtonRow`, `PipResultCard` etc.

3. **Add `restartSameSession()`** to the ViewModel — calls `cancelSession()` then `startSession()` with same params.

### Key files
- `ui/common/pip/PipController.kt` — singleton; `isInPipMode: StateFlow<Boolean>`, `actionFlow: SharedFlow<String>`
- `ui/common/pip/PipAction.kt` — `PipAction` data class, `PipActionIds` constants, `PipActionReceiver`
- `ui/common/pip/PipSessionHost.kt` — composable wrapper
- `ui/common/pip/MiniSessionLayout.kt` — `PipRoot`, `PipTimerText`, `PipLabel`, `PipButton`, `PipButtonRow`, `PipResultCard`
- `MainActivity.kt` — `onUserLeaveHint()`, `onPictureInPictureModeChanged()`, receiver registration

### Constraints
- OS allows max 3 RemoteAction buttons in the PiP overlay
- `configChanges` flags in AndroidManifest prevent Activity recreation on PiP enter/exit
- `PipController.setPipEligible(false)` is called automatically on composition disposal via `DisposableEffect`
