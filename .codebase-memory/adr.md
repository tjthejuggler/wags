# ADR: Background battery-drain remediation — gate BLE auto-connect on app visibility

**Status**: Accepted (2026-11-19)

## Context
Users observed heavy background battery usage. Investigation (graph traces + manifest audit) found the app had no WorkManager/alarms; the drain came from the process staying alive with an always-running BLE reconnect loop:

1. `AutoConnectManager.mainLoop()` (app/src/main/java/com/example/wags/data/ble/AutoConnectManager.kt) ran forever regardless of UI visibility: every ~5 s it attempted GATT connects (`connectWithScan` = active BLE scans), plus a 5 s-on / 10 s-off background scan loop via `GenericBleManager.startBackgroundScan()`. `cancel()` had zero callers.
2. `MeditationService.onCreate()` fired `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` on every session start; once granted, Doze never throttled the above loop.
3. MainActivity's 10-minute background kill timer gave up permanently (one-shot `return@launch`) when an audio import was running, leaving the process — and the BLE loop — alive indefinitely.
4. `BleService` is declared in the manifest (foregroundServiceType=connectedDevice, START_STICKY + untimed wakelock) but never started by any code — dead code with dangerous potential; left undeleted pending explicit user decision.

## Decision
- **Auto-connect is now visibility-gated**: `AutoConnectManager.setBackgrounded(true/false)` is called from MainActivity's ProcessLifecycleOwner observer (onStop/onStart). While backgrounded, the main loop parks (no connect attempts, no BLE scans); established GATT connections are untouched. The loop resumes on foreground.
- **Battery-optimization exemption request removed** from MeditationService (and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission removed from the manifest). The service keeps its timed wakelock + mediaPlayback foreground type, which is sufficient for screen-off sessions.
- **Kill timer made robust**: it now polls every 60 s while `AudioImportService.isRunning || MeditationService.isSessionActive` (new static flag mirroring AudioImportService's) instead of surrendering after one check, then kills the process.
- Foreground services with deliberate background jobs (MeditationService mediaPlayback, AudioImportService dataSync) remain the ONLY sanctioned background execution paths.

## Consequences
- Backgrounded app = no BLE radio activity except active foreground-service sessions → battery drain eliminated.
- Reconnect latency on app resume is one loop iteration (≤5 s), imperceptible.
- If the user previously granted the battery exemption, Android Settings still shows it; they may revoke manually — the app no longer requests it.
- BleService remains unused; candidate for deletion in a future cleanup.