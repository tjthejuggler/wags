# ADR: Meditation session lifecycle — foreground-service ownership, crash isolation, and orphan-recovery safety

**Date:** 2026-08-15
**Status:** Accepted

## Context
Two long-standing bugs in the meditation/NSDR flow (evidence: on-device crash log `crash_2026-08-15_15-14-54.txt`, Samsung SM-S918U1, Android 16 / SDK 36, targetSdk 36):

1. **Screen-off kill at ~10 min.** `MeditationService` used a plain `Job()` scope: any child-coroutine failure (e.g. an unguarded `recoverOrphanedSessions()` launch) cancelled the whole scope, silently killing the wake-lock renewal job; the 10-minute `WAKE_LOCK_TIMEOUT_MS` then expired exactly when users reported the session dying. Compounding factors: no `MediaSession` while running a `mediaPlayback` FGS (Android 14+ ties this FGS type's legitimacy to an active media session; Samsung One UI enforces aggressively), `MediaPlayer` without `setWakeMode`, and the 2-arg `startForeground()` which on API 34+ activates ALL manifest types (`mediaPlayback|connectedDevice`), requiring `connectedDevice` runtime prerequisites even for monitor-less sessions.

2. **"Done" button crash + total data loss.** `deleteIncompleteShorterThan(5s)` ran concurrently with the creation of the new session row (which starts at `durationMs = 0, completed = 0`) and deleted the ACTIVE row. Every 15-s telemetry flush then failed with `SQLiteConstraintException: FOREIGN KEY constraint failed` (caught, silent), and the final unguarded flush at session stop crashed the process — killing the ViewModel's in-flight save. 26 minutes of data lost.

## Decision
- **Service owns session audio** exclusively (ViewModel's duplicate MediaPlayer removed); service holds an **active framework `MediaSession`** (STATE_PLAYING) for the whole session and calls `MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)`.
- **`startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`** explicitly on API 29+ (subset of manifest types); sticky restarts (`onStartCommand(null)`) also re-enter foreground before recovery+stop to avoid `ForegroundServiceDidNotStartInTime`.
- **`serviceScope = SupervisorJob() + CoroutineExceptionHandler`** — no child failure can crash the process or cancel siblings (wake-lock renewal survives).
- **Orphan recovery is safe by construction:** `deleteIncompleteShorterThan` now requires a 10-minute staleness cutoff on `timestamp`; recovery runs sequentially BEFORE the new session row is created; `flushTelemetry()` always refreshes `durationMs` (even with no telemetry) so active rows are never mistaken for orphans; recovery never throws.
- **Session finalization is atomic:** `MeditationRepository.finalizeSessionWithTelemetry / insertSessionWithTelemetry` wrap UPDATE+DELETE+INSERT in a Room `withTransaction`; the ViewModel save is fully try/caught so a DB failure can never crash the app mid-save (row stays incomplete → recovered on next launch).
- `Service.onTimeout(fgsType)` (API 35+) emergency-saves the session instead of silently dying.

## Consequences
- Any future FGS that plays audio must hold an active MediaSession and pass an explicit foreground-service type at runtime.
- Any coroutine scope hosting wake-lock renewal or other lifecycle-critical jobs must use SupervisorJob + exception handler.
- DB recovery/cleanup queries must never match rows that a live component could have just created; guard with recency cutoffs and keep "liveness" columns (durationMs) freshly updated.
