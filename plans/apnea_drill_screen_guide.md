# Guide: Adding a New Apnea Drill Screen

*Created: 2026-04-09 14:00 UTC-6*

This guide explains how to create a new dedicated apnea drill screen in the WAGS app. It is based on how the **Progressive O₂** drill was implemented and serves as a repeatable recipe for Min Breath, Wonka: Till Contraction, Wonka: Endurance, or any future drill type.

---

## 1. Overview

A "dedicated apnea drill screen" is a **3-screen flow**:

```
Setup Screen → Active Screen → Detail Screen
```

| Screen | Purpose |
|--------|---------|
| **Setup** | Configure drill parameters, view past sessions, tap Start |
| **Active** | Run the drill with live countdown, vitals, round tracking |
| **Detail** | Post-session analytics with charts and round breakdown |

The Setup and Active screens share a single `@HiltViewModel`. The Detail screen has its own ViewModel that loads saved data by `sessionId`.

---

## 2. Files to Create (6 per drill)

Replace `{DrillName}` with your drill's PascalCase name (e.g., `MinBreath`, `WonkaEndurance`).

| # | File | Purpose |
|---|------|---------|
| 1 | [`domain/usecase/apnea/{DrillName}StateMachine.kt`](app/src/main/java/com/example/wags/domain/usecase/apnea/ProgressiveO2StateMachine.kt) | Phase transitions + countdown timer |
| 2 | [`ui/apnea/{DrillName}ViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2ViewModel.kt) | Shared ViewModel for Setup + Active screens |
| 3 | [`ui/apnea/{DrillName}Screen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2Screen.kt) | Setup/landing screen |
| 4 | [`ui/apnea/{DrillName}ActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2ActiveScreen.kt) | Active drill screen |
| 5 | [`ui/apnea/{DrillName}DetailScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2DetailScreen.kt) | Post-session detail screen |
| 6 | [`ui/apnea/{DrillName}DetailViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2DetailViewModel.kt) | Detail screen ViewModel |

## 3. Files to Modify (always the same 2)

| # | File | What to change |
|---|------|----------------|
| 1 | [`ui/navigation/WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt) | Add 3 route constants + helper function + 3 `composable()` blocks |
| 2 | [`ui/apnea/ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt) | Replace inline card content with navigation button |

---

## 4. State Machine Pattern

Reference: [`ProgressiveO2StateMachine.kt`](app/src/main/java/com/example/wags/domain/usecase/apnea/ProgressiveO2StateMachine.kt)

### 4.1 Structure

Every state machine needs three things: a **Phase enum**, a **State data class**, and the **machine class** itself.

```kotlin
// ── Phase enum ──
enum class {DrillName}Phase {
    IDLE, HOLD, BREATHING, COMPLETE
    // Add drill-specific phases as needed (e.g., CONTRACTION_WAIT)
}

// ── Per-round result ──
data class {DrillName}RoundResult(
    val roundNumber: Int,
    val targetHoldMs: Long,
    val actualHoldMs: Long,
    val completed: Boolean
    // Add drill-specific fields (e.g., contractionMs)
)

// ── State data class ──
data class {DrillName}State(
    val phase: {DrillName}Phase = {DrillName}Phase.IDLE,
    val currentRound: Int = 0,
    val timerMs: Long = 0L,
    val holdDurationMs: Long = 0L,
    val breathDurationMs: Long = 0L,
    val totalHoldTimeMs: Long = 0L,
    val roundResults: List<{DrillName}RoundResult> = emptyList()
)
```

### 4.2 Machine class

```kotlin
@Singleton
class {DrillName}StateMachine @Inject constructor() {

    private val _state = MutableStateFlow({DrillName}State())
    val state: StateFlow<{DrillName}State> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var scope: CoroutineScope? = null

    fun start(/* drill-specific params */, scope: CoroutineScope) {
        this.scope = scope
        _state.value = {DrillName}State(/* initial values */)
        startHoldRound(1)
    }

    fun stop() {
        timerJob?.cancel()
        val current = _state.value
        // Record partial round if in HOLD phase
        // Transition to COMPLETE
    }

    private fun runCountdown(durationMs: Long, onComplete: () -> Unit) {
        timerJob?.cancel()
        timerJob = scope?.launch {
            var remaining = durationMs
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1000L
                _state.value = _state.value.copy(
                    timerMs = remaining.coerceAtLeast(0L)
                )
            }
            onComplete()
        }
    }
}
```

### 4.3 Key design decisions

- **`@Singleton` + `@Inject constructor()`** — Hilt provides it; shared across Setup/Active screens via the ViewModel
- **`CoroutineScope` passed in via `start()`** — Uses the ViewModel's `viewModelScope` so the timer is cancelled when the ViewModel is cleared
- **Timer ticks every 1000ms** — Updates `timerMs` by decrementing; UI observes via `StateFlow`
- **`stop()` records partial rounds** — If stopped mid-hold, calculate elapsed time as `holdDurationMs - timerMs`
- **Round transitions** — `onHoldComplete()` records the round result, switches to BREATHING, then `runCountdown()` calls `startHoldRound(nextRound)` when breathing finishes

---

## 5. ViewModel Pattern

Reference: [`ProgressiveO2ViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2ViewModel.kt)

### 5.1 Constructor injection

```kotlin
@HiltViewModel
class {DrillName}ViewModel @Inject constructor(
    private val stateMachine: {DrillName}StateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val hrDataSource: HrDataSource,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel()
```

### 5.2 UI State data class

```kotlin
data class {DrillName}UiState(
    // State machine state
    val sessionState: {DrillName}State = {DrillName}State(),
    // Drill-specific config
    val breathPeriodSec: Int = 60,  // example — replace with your drill's config
    // Session management
    val isSessionActive: Boolean = false,
    val completedSessionId: Long? = null,
    // Live vitals
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    // Past sessions
    val pastSessions: List<{DrillName}PastSession> = emptyList(),
    // Apnea settings (5 standard settings)
    val lungVolume: String = "FULL",
    val prepType: String = "NO_PREP",
    val timeOfDay: String = "DAY",
    val posture: String = "LAYING",
    val audio: String = "SILENCE",
    // Spotify
    val spotifyConnected: Boolean = false,
    val isMusicMode: Boolean = false,
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSong: SpotifyTrackDetail? = null,
    val loadingSelectedSong: Boolean = false
)
```

### 5.3 Combined StateFlow

The public `uiState` combines the internal `_uiState` with live data sources:

```kotlin
val uiState: StateFlow<{DrillName}UiState> = combine(
    _uiState,
    hrDataSource.liveHr,
    hrDataSource.liveSpO2,
    stateMachine.state,
    spotifyAuthManager.isConnected
) { args ->
    val ui = args[0] as {DrillName}UiState
    val hr = args[1] as Int?
    val spo2 = args[2] as Int?
    val session = args[3] as {DrillName}State
    val connected = args[4] as Boolean
    ui.copy(sessionState = session, liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), {DrillName}UiState())
```

### 5.4 Settings (5 standard apnea settings)

Read from SharedPreferences in `init {}`, expose setter methods that persist immediately:

```kotlin
init {
    val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
    val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
    val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"
    val savedAudio      = prefs.getString("setting_audio", "SILENCE") ?: "SILENCE"
    _uiState.update { it.copy(
        lungVolume = savedLungVolume, prepType = savedPrepType,
        timeOfDay = TimeOfDay.fromCurrentTime().name,
        posture = savedPosture, audio = savedAudio,
        isMusicMode = savedAudio == AudioSetting.MUSIC.name
    ) }
}

fun setLungVolume(v: String) {
    prefs.edit().putString("setting_lung_volume", v).apply()
    _uiState.update { it.copy(lungVolume = v) }
}
// ... same pattern for setPrepType, setTimeOfDay, setPosture, setAudio
```

### 5.5 Spotify integration

Three methods following the same pattern as the Progressive O₂ ViewModel:

- **`loadPreviousSongs()`** — Loads from DB + prefs, enriches via Spotify API, deduplicates
- **`selectSong(track)`** — Sets selected song, starts playback, pauses + rewinds
- **`clearSelectedSong()`** — Clears selection

### 5.6 Telemetry collection

Start a 1-second sampling loop when the session begins:

```kotlin
private data class TelemetrySample(val timestampMs: Long, val hr: Int?, val spO2: Int?)
private val telemetrySamples = mutableListOf<TelemetrySample>()
private var telemetryJob: Job? = null

fun startSession() {
    sessionStartMs = System.currentTimeMillis()
    telemetrySamples.clear()
    telemetryJob = viewModelScope.launch {
        while (true) {
            val hr = hrDataSource.liveHr.value
            val spo2 = hrDataSource.liveSpO2.value
            if (hr != null || spo2 != null) {
                telemetrySamples.add(TelemetrySample(System.currentTimeMillis(), hr, spo2))
            }
            delay(1000L)
        }
    }
    stateMachine.start(/* params */, viewModelScope)
}
```

### 5.7 Audio/haptic cues

Observe state machine phase transitions and fire cues via `ApneaAudioHapticEngine`:

```kotlin
init {
    viewModelScope.launch {
        stateMachine.state.collect { state ->
            handlePhaseTransition(state)
            previousPhase = state.phase
        }
    }
}

private fun handlePhaseTransition(state: {DrillName}State) {
    if (state.phase == previousPhase) {
        // Same phase — check for breathing countdown tick (last 10s)
        if (state.phase == BREATHING && state.timerMs in 1000..10_000) {
            audioHapticEngine.vibrateBreathingCountdownTick()
        }
        return
    }
    when (state.phase) {
        HOLD -> audioHapticEngine.announceHoldBegin()
        BREATHING -> { audioHapticEngine.vibrateHoldEnd(); audioHapticEngine.announceBreath() }
        COMPLETE -> audioHapticEngine.announceSessionComplete()
        IDLE -> { /* no cue */ }
    }
}
```

### 5.8 Session saving

See [Section 10: Data Storage](#10-data-storage) for the full pattern.

---

## 6. Setup Screen Pattern

Reference: [`ProgressiveO2Screen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2Screen.kt)

### 6.1 Structure

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun {DrillName}Screen(
    navController: NavController,
    viewModel: {DrillName}ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadPastSessions() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("{Drill Display Name}") },
                navigationIcon = { /* back arrow IconButton */ },
                actions = { LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Dialog states
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showSongPicker by remember { mutableStateOf(false) }

        if (showSettingsDialog) { FreeHoldSettingsDialog(/* ... */) }
        if (showSongPicker) { SongPickerDialog(/* ... */) }

        Column(/* scrollable */) {
            // 0. Settings summary banner (clickable -> FreeHoldSettingsDialog)
            ApneaSettingsSummaryBanner(/* 5 settings + onClick */)

            // 0b. Song picker (when MUSIC mode)
            // if (state.isMusicMode) { ... SongPickerButton or SpotifyConnectPrompt ... }

            // 1. Explanation card
            // 2. Drill-specific configuration inputs (e.g., breath period stepper)
            // 3. Start button -> navController.navigate(WagsRoutes.{DRILL_NAME}_ACTIVE)
            // 4. Past sessions section (grouped, clickable -> sessionAnalytics)
        }
    }
}
```

### 6.2 Key elements

- **`TopAppBar`** with back arrow + [`LiveSensorActions`](app/src/main/java/com/example/wags/ui/common/LiveSensorTopBar.kt:19) showing live HR/SpO₂
- **[`ApneaSettingsSummaryBanner`](app/src/main/java/com/example/wags/ui/apnea/ApneaSettingsSummaryBanner.kt:30)** — Clickable banner showing lung volume, prep type, time of day, posture, audio. Opens [`FreeHoldSettingsDialog`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldSettingsDialog.kt:35)
- **Song picker** — When `audio == MUSIC`: show [`SongPickerButton`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:44) if Spotify connected, or [`SpotifyConnectPrompt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:75) if not. [`SelectedSongBanner`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:117) when a song is pre-selected
- **Explanation card** — `Card` with `SurfaceVariant.copy(alpha = 0.3f)` background, describing the drill
- **Drill-specific inputs** — e.g., stepper for breath period, number of rounds, etc.
- **Start button** — `Button` navigating to the active screen route
- **Past sessions** — Grouped by drill-specific parameter, each card clickable to `WagsRoutes.sessionAnalytics(sessionId)`

---

## 7. Active Screen Pattern

Reference: [`ProgressiveO2ActiveScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2ActiveScreen.kt)

### 7.1 Structure

```kotlin
@Composable
fun {DrillName}ActiveScreen(
    navController: NavController,
    viewModel: {DrillName}ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val phase = state.sessionState.phase
    val isActive = phase == HOLD || phase == BREATHING

    // Guards
    SessionBackHandler(enabled = isActive) {
        viewModel.stopSession()
        navController.popBackStack()
    }
    KeepScreenOn(enabled = isActive || phase == COMPLETE)

    // Auto-start
    LaunchedEffect(Unit) {
        if (!state.isSessionActive) viewModel.startSession()
    }

    Scaffold(/* ... */) { padding ->
        when (phase) {
            IDLE -> IdleContent(/* "Starting..." */)
            HOLD, BREATHING -> ActiveContent(state, onStop = { viewModel.stopSession() })
            COMPLETE -> CompleteContent(
                state = state,
                onViewDetails = { sessionId ->
                    navController.navigate(WagsRoutes.{drillName}Detail(sessionId)) {
                        popUpTo(WagsRoutes.{DRILL_NAME}_ACTIVE) { inclusive = true }
                    }
                },
                onDone = { navController.popBackStack() }
            )
        }
    }
}
```

### 7.2 Key elements

- **[`SessionBackHandler`](app/src/main/java/com/example/wags/ui/common/SessionGuards.kt:22)** — Intercepts system back button during active session; stops session + pops back
- **[`KeepScreenOn`](app/src/main/java/com/example/wags/ui/common/SessionGuards.kt:65)** — Prevents screen timeout via `Window.addFlags(FLAG_KEEP_SCREEN_ON)`
- **Auto-start via `LaunchedEffect(Unit)`** — Calls `viewModel.startSession()` once on first composition
- **Phase-dependent display:**
  - **HOLD** — Red `#FF6B6B` phase label, giant countdown timer, target hold info, round indicator
  - **BREATHING** — Teal `#4ECDC4` phase label, countdown, next hold info
  - **COMPLETE** — Summary card with rounds/max hold/total hold/session time/HR/SpO₂, completed rounds list, "View Details" button, "Done" button
- **Live vitals** — HR and SpO₂ displayed during active phases
- **Completed rounds list** — `LazyColumn` with `heightIn(max = 200.dp)`, most recent first, checkmark/cross icons
- **Stop button** — `OutlinedButton` with red border
- **Navigation on complete** — "View Details" navigates to detail screen with `popUpTo(ACTIVE, inclusive = true)` so back from detail goes to setup, not active

---

## 8. Detail Screen Pattern

Reference: [`ProgressiveO2DetailScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2DetailScreen.kt) + [`ProgressiveO2DetailViewModel.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2DetailViewModel.kt)

### 8.1 Detail ViewModel

```kotlin
@HiltViewModel
class {DrillName}DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: ApneaSessionRepository
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    init {
        viewModelScope.launch {
            val session = sessionRepository.getSessionById(sessionId)
            if (session == null) { /* set notFound = true */ return@launch }
            val telemetry = sessionRepository.getTelemetryForSession(sessionId)
            val (drillParams, rounds) = parseTableParams(session.tableParamsJson)
            // Compute HR/SpO2 stats from telemetry (filter 20-250 bpm, > 0% SpO2)
            // Update UI state
        }
    }
}
```

### 8.2 Detail Screen sections

1. **Session Summary Card** — Date, drill-specific params, rounds completed/attempted, max hold, total hold time, session duration, device, HR stats min/avg/max, lowest SpO₂
2. **HR Line Chart** — Canvas-based, red `#FF6B6B`, 200dp tall, Y-axis labels, X-axis minute markers
3. **SpO₂ Line Chart** — Canvas-based, grey `#B0B0B0`, Y-axis clamped 80-100%
4. **Rounds Breakdown Table** — Alternating row colors, columns: #, Target, Actual, checkmark/cross

### 8.3 Chart pattern

The `TelemetryLineChart` composable in the detail screen follows the same Canvas pattern used in [`ApneaRecordDetailScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaRecordDetailScreen.kt). Copy the `TelemetryLineChart` composable from [`ProgressiveO2DetailScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2DetailScreen.kt:218) — it handles grid lines, data path, end dots, Y-axis labels, and X-axis minute markers.

---

## 9. Navigation Wiring

Reference: [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt)

### Step 1: Add route constants to `WagsRoutes`

```kotlin
object WagsRoutes {
    // ... existing routes ...

    // ── {Drill Display Name} ──
    const val {DRILL_NAME} = "{drill_name}"
    const val {DRILL_NAME}_ACTIVE = "{drill_name}_active"
    const val {DRILL_NAME}_DETAIL = "{drill_name}_detail/{sessionId}"

    fun {drillName}Detail(sessionId: Long) = "{drill_name}_detail/$sessionId"
}
```

For example, Progressive O₂ uses:
- `PROGRESSIVE_O2 = "progressive_o2"`
- `PROGRESSIVE_O2_ACTIVE = "progressive_o2_active"`
- `PROGRESSIVE_O2_DETAIL = "progressive_o2_detail/{sessionId}"`
- `fun progressiveO2Detail(sessionId: Long) = "progressive_o2_detail/$sessionId"`

### Step 2: Add `composable()` blocks in `NavHost`

```kotlin
// ── {Drill Display Name} ──
composable(WagsRoutes.{DRILL_NAME}) {
    {DrillName}Screen(navController = navController)
}
composable(WagsRoutes.{DRILL_NAME}_ACTIVE) {
    {DrillName}ActiveScreen(navController = navController)
}
composable(
    route = WagsRoutes.{DRILL_NAME}_DETAIL,
    arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
) {
    {DrillName}DetailScreen(navController = navController)
}
```

### Step 3: Add imports at the top of `WagsNavGraph.kt`

```kotlin
import com.example.wags.ui.apnea.{DrillName}Screen
import com.example.wags.ui.apnea.{DrillName}ActiveScreen
import com.example.wags.ui.apnea.{DrillName}DetailScreen
```

### Step 4: Update `ApneaScreen.kt`

Replace the inline card content with a navigation button:

```kotlin
// ── {Drill Display Name} ──
val {drillName}Open = state.openSection == ApneaSection.{DRILL_SECTION}
CollapsibleCard(
    title = "{Drill Display Name}",
    expanded = {drillName}Open,
    onToggle = { viewModel.toggleSection(ApneaSection.{DRILL_SECTION}) },
    headerExtra = { TableHelpIcon(title = ..., text = ...) }
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Brief description of the drill.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Button(
            onClick = { navController.navigate(WagsRoutes.{DRILL_NAME}) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open {Drill Display Name}")
        }
    }
}
```

---

## 10. Data Storage

Reference: [`ProgressiveO2ViewModel.saveSession()`](app/src/main/java/com/example/wags/ui/apnea/ProgressiveO2ViewModel.kt:356)

### 10.1 Four entities are saved per session

```
ApneaSessionEntity (1 row)
    |-- TelemetryEntity (N rows, FK -> sessionId)
ApneaRecordEntity (1 row)
    |-- FreeHoldTelemetryEntity (N rows, FK -> recordId)
```

### 10.2 ApneaSessionEntity

Reference: [`ApneaSessionEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/ApneaSessionEntity.kt)

| Field | What to put |
|-------|-------------|
| `timestamp` | `System.currentTimeMillis()` at save time |
| `tableType` | Your drill's type string, e.g. `"PROGRESSIVE_O2"`, `"MIN_BREATH"`, `"WONKA_FIRST"` |
| `tableVariant` | `"ENDLESS"` or a variant identifier |
| `tableParamsJson` | JSON with drill-specific config + per-round data (see below) |
| `pbAtSessionMs` | `0L` or user's PB if relevant |
| `totalSessionDurationMs` | `now - sessionStartMs` |
| `contractionTimestampsJson` | `"[]"` or JSON array of contraction timestamps |
| `maxHrBpm` | Max HR from telemetry samples |
| `lowestSpO2` | Min SpO₂ from telemetry samples |
| `roundsCompleted` | Count of rounds where `completed == true` |
| `totalRounds` | Total rounds attempted |
| `hrDeviceId` | `hrDataSource.activeHrDeviceLabel()` |

### 10.3 tableParamsJson format

```json
{
  "breathPeriodSec": 60,
  "rounds": [
    { "round": 1, "targetMs": 15000, "actualMs": 15000, "completed": true },
    { "round": 2, "targetMs": 30000, "actualMs": 30000, "completed": true },
    { "round": 3, "targetMs": 45000, "actualMs": 22000, "completed": false }
  ]
}
```

Add any drill-specific fields at the root level. The `rounds` array should always follow this structure so the Detail ViewModel can parse it generically.

### 10.4 ApneaRecordEntity

Reference: [`ApneaRecordEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/ApneaRecordEntity.kt)

| Field | What to put |
|-------|-------------|
| `timestamp` | Same as session timestamp |
| `durationMs` | Longest completed hold (for display in All Records) |
| `lungVolume` | From UI state settings |
| `prepType` | From UI state settings |
| `minHrBpm` | Min HR from telemetry (as Float) |
| `maxHrBpm` | Max HR from telemetry (as Float) |
| `tableType` | Same as session's `tableType` |
| `lowestSpO2` | Min SpO₂ from telemetry |
| `timeOfDay` | From UI state settings |
| `hrDeviceId` | Device label |
| `posture` | From UI state settings |
| `audio` | From UI state settings |

### 10.5 TelemetryEntity (FK -> sessionId)

Reference: [`TelemetryEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/TelemetryEntity.kt)

One row per second of the session:

| Field | Value |
|-------|-------|
| `sessionId` | The saved session's ID |
| `timestampMs` | Absolute epoch ms |
| `spO2` | SpO₂ reading (nullable) |
| `heartRateBpm` | HR reading (nullable) |
| `source` | `"OXIMETER"` or `"POLAR"` from `hrDataSource.isOximeterPrimaryDevice()` |

### 10.6 FreeHoldTelemetryEntity (FK -> recordId)

Reference: [`FreeHoldTelemetryEntity.kt`](app/src/main/java/com/example/wags/data/db/entity/FreeHoldTelemetryEntity.kt)

Same samples, linked to the record instead of the session:

| Field | Value |
|-------|-------|
| `recordId` | The saved record's ID |
| `timestampMs` | Absolute epoch ms |
| `heartRateBpm` | HR reading (nullable) |
| `spO2` | SpO₂ reading (nullable) |

### 10.7 History integration

Sessions automatically appear in the existing [`ApneaHistoryScreen`](app/src/main/java/com/example/wags/ui/apnea/ApneaHistoryScreen.kt) because it filters by `tableType`. As long as your `ApneaRecordEntity` has a `tableType` value, it will show up in:

- **All Records** tab — sorted by timestamp
- **Stats** tab — filtered by table type
- **Calendar** tab — date-based view

**No additional wiring is needed** for history integration. The existing screens handle any `tableType` value.

### 10.8 Save session code pattern

```kotlin
private suspend fun saveSession(finalState: {DrillName}State): Long {
    val now = System.currentTimeMillis()
    val totalDurationMs = now - sessionStartMs
    val deviceLabel = hrDataSource.activeHrDeviceLabel()
    val telemetrySnapshot = telemetrySamples.toList()
    telemetrySamples.clear()

    // Compute aggregates
    val maxHr = telemetrySnapshot.mapNotNull { it.hr }.maxOrNull()
    val minHr = telemetrySnapshot.mapNotNull { it.hr }.minOrNull()
    val lowestSpO2 = telemetrySnapshot.mapNotNull { it.spO2 }.minOrNull()

    // Build tableParamsJson
    val paramsJson = buildParamsJson(/* drill config */, finalState.roundResults)

    // 1. Save ApneaSessionEntity
    val sessionEntity = ApneaSessionEntity(
        timestamp = now, tableType = "{DRILL_TABLE_TYPE}",
        tableVariant = "ENDLESS", tableParamsJson = paramsJson,
        /* ... all other fields ... */
    )
    val sessionId = sessionRepository.saveSession(sessionEntity)

    // 2. Save ApneaRecordEntity
    val longestHoldMs = finalState.roundResults
        .filter { it.completed }.maxOfOrNull { it.targetHoldMs } ?: 0L
    val recordId = apneaRepository.saveRecord(ApneaRecordEntity(
        timestamp = now, durationMs = longestHoldMs,
        tableType = "{DRILL_TABLE_TYPE}", /* ... settings from UI state ... */
    ))

    // 3. Save FreeHoldTelemetryEntity rows (FK -> recordId)
    if (recordId > 0 && telemetrySnapshot.isNotEmpty()) {
        apneaRepository.saveTelemetry(telemetrySnapshot.map { sample ->
            FreeHoldTelemetryEntity(
                recordId = recordId, timestampMs = sample.timestampMs,
                heartRateBpm = sample.hr, spO2 = sample.spO2
            )
        })
    }

    // 4. Save TelemetryEntity rows (FK -> sessionId)
    if (sessionId > 0 && telemetrySnapshot.isNotEmpty()) {
        sessionRepository.saveTelemetry(telemetrySnapshot.map { sample ->
            TelemetryEntity(
                sessionId = sessionId, timestampMs = sample.timestampMs,
                spO2 = sample.spO2, heartRateBpm = sample.hr,
                source = if (hrDataSource.isOximeterPrimaryDevice()) "OXIMETER" else "POLAR"
            )
        })
    }

    return sessionId
}
```

---

## 11. Reusable Components

These existing composables should be reused — do NOT recreate them:

| Component | File | Purpose |
|-----------|------|---------|
| [`ApneaSettingsSummaryBanner`](app/src/main/java/com/example/wags/ui/apnea/ApneaSettingsSummaryBanner.kt:30) | `ui/apnea/ApneaSettingsSummaryBanner.kt` | Clickable banner showing 5 apnea settings |
| [`FreeHoldSettingsDialog`](app/src/main/java/com/example/wags/ui/apnea/FreeHoldSettingsDialog.kt:35) | `ui/apnea/FreeHoldSettingsDialog.kt` | Popup with filter chips for all 5 settings |
| [`SongPickerDialog`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:170) | `ui/apnea/SongPickerComponents.kt` | Full song selection dialog |
| [`SongPickerButton`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:44) | `ui/apnea/SongPickerComponents.kt` | "Choose a Song" button |
| [`SpotifyConnectPrompt`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:75) | `ui/apnea/SongPickerComponents.kt` | Prompt when Spotify not connected |
| [`SelectedSongBanner`](app/src/main/java/com/example/wags/ui/apnea/SongPickerComponents.kt:117) | `ui/apnea/SongPickerComponents.kt` | Shows pre-selected song with clear button |
| [`LiveSensorActions`](app/src/main/java/com/example/wags/ui/common/LiveSensorTopBar.kt:19) | `ui/common/LiveSensorTopBar.kt` | TopAppBar actions showing live HR/SpO₂ |
| [`SessionBackHandler`](app/src/main/java/com/example/wags/ui/common/SessionGuards.kt:22) | `ui/common/SessionGuards.kt` | Intercepts back button during active sessions |
| [`KeepScreenOn`](app/src/main/java/com/example/wags/ui/common/SessionGuards.kt:65) | `ui/common/SessionGuards.kt` | Prevents screen timeout via Window flag |
| `ApneaAudioHapticEngine` | `domain/usecase/apnea/ApneaAudioHapticEngine.kt` | Audio announcements + vibration haptics |

---

## 12. Step-by-Step Checklist

Follow this checklist when creating a new drill. Each step should be completed in order.

### Phase 1: State Machine
- [ ] Create `domain/usecase/apnea/{DrillName}StateMachine.kt`
- [ ] Define `{DrillName}Phase` enum with at least: IDLE, HOLD, BREATHING, COMPLETE
- [ ] Define `{DrillName}RoundResult` data class
- [ ] Define `{DrillName}State` data class
- [ ] Implement `start()` method accepting drill-specific params + `CoroutineScope`
- [ ] Implement `stop()` method that records partial rounds and transitions to COMPLETE
- [ ] Implement `runCountdown()` with 1000ms delay loop
- [ ] Implement round transition logic (hold complete -> breathing -> next hold)

### Phase 2: ViewModel
- [ ] Create `ui/apnea/{DrillName}ViewModel.kt`
- [ ] Define `{DrillName}UiState` data class (session state, config, vitals, settings, Spotify)
- [ ] Define `{DrillName}PastSession` data class for history display
- [ ] Inject: StateMachine, ApneaSessionRepository, ApneaRepository, HrDataSource, ApneaAudioHapticEngine, SpotifyManager, SpotifyApiClient, SpotifyAuthManager, SharedPreferences
- [ ] Set up combined `uiState` StateFlow (5-way combine)
- [ ] Read 5 apnea settings from SharedPreferences in `init {}`
- [ ] Add 5 settings setter methods (persist to SharedPreferences)
- [ ] Add Spotify methods: `loadPreviousSongs()`, `selectSong()`, `clearSelectedSong()`
- [ ] Add `loadPastSessions()` — query by tableType, parse tableParamsJson
- [ ] Add `startSession()` — start telemetry collection + state machine
- [ ] Add `stopSession()` — stop telemetry, stop state machine, save session
- [ ] Add `onSessionNavigated()` — clear completedSessionId
- [ ] Add audio/haptic phase transition handler
- [ ] Add `saveSession()` — save 4 entities (session, record, 2x telemetry)
- [ ] Add JSON builder for tableParamsJson
- [ ] Add JSON parser for past sessions

### Phase 3: Setup Screen
- [ ] Create `ui/apnea/{DrillName}Screen.kt`
- [ ] Scaffold with TopAppBar (title, back arrow, LiveSensorActions)
- [ ] ApneaSettingsSummaryBanner (clickable, opens FreeHoldSettingsDialog)
- [ ] Song picker section (conditional on MUSIC mode)
- [ ] Explanation card describing the drill
- [ ] Drill-specific configuration inputs
- [ ] Start button navigating to `WagsRoutes.{DRILL_NAME}_ACTIVE`
- [ ] Past sessions section with grouped history cards
- [ ] LaunchedEffect to load past sessions on entry

### Phase 4: Active Screen
- [ ] Create `ui/apnea/{DrillName}ActiveScreen.kt`
- [ ] SessionBackHandler + KeepScreenOn guards
- [ ] LaunchedEffect auto-start
- [ ] IDLE content ("Starting...")
- [ ] HOLD content (red phase, giant countdown, target, round indicator)
- [ ] BREATHING content (teal phase, countdown, next hold info)
- [ ] Live vitals display (HR + SpO₂)
- [ ] Completed rounds list (LazyColumn, max 200dp)
- [ ] Stop button (OutlinedButton, red border)
- [ ] COMPLETE content (summary card, rounds list, View Details + Done buttons)
- [ ] View Details navigation with `popUpTo(ACTIVE, inclusive = true)`

### Phase 5: Detail Screen
- [ ] Create `ui/apnea/{DrillName}DetailViewModel.kt`
- [ ] Read sessionId from SavedStateHandle
- [ ] Load session + telemetry from repository
- [ ] Parse tableParamsJson into round display data
- [ ] Compute HR stats (min/max/avg, filtered 20-250 bpm)
- [ ] Compute SpO₂ stats (lowest, filtered > 0)
- [ ] Create `ui/apnea/{DrillName}DetailScreen.kt`
- [ ] Session summary card (date, params, rounds, holds, duration, device, HR, SpO₂)
- [ ] HR line chart (Canvas, red, 200dp)
- [ ] SpO₂ line chart (Canvas, grey, Y-axis 80-100%)
- [ ] Rounds breakdown table (alternating rows, #/Target/Actual/Status)

### Phase 6: Navigation Wiring
- [ ] Add 3 route constants to `WagsRoutes` in [`WagsNavGraph.kt`](app/src/main/java/com/example/wags/ui/navigation/WagsNavGraph.kt)
- [ ] Add helper function `{drillName}Detail(sessionId: Long)`
- [ ] Add 3 `composable()` blocks in `NavHost`
- [ ] Add imports for the 3 new screen composables
- [ ] Update [`ApneaScreen.kt`](app/src/main/java/com/example/wags/ui/apnea/ApneaScreen.kt) — replace inline card with navigation button

### Phase 7: Verify
- [ ] Build compiles (`./gradlew installDebug`)
- [ ] Setup screen loads and displays correctly
- [ ] Settings banner opens dialog and persists changes
- [ ] Start button navigates to active screen
- [ ] Active screen auto-starts and shows countdown
- [ ] Phase transitions work (HOLD -> BREATHING -> next HOLD)
- [ ] Audio/haptic cues fire on transitions
- [ ] Stop button ends session and shows COMPLETE
- [ ] View Details navigates to detail screen
- [ ] Detail screen shows summary, charts, and rounds table
- [ ] Back from detail goes to setup (not active)
- [ ] Session appears in Apnea History (All Records tab)
- [ ] Past sessions load on setup screen