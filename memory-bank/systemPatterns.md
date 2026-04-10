# WAGS — System Patterns

*Last updated: 2026-04-09 15:32 UTC-6*

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
