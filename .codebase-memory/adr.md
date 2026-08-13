# ADR: Meditation Session Crash-Safety — Incremental Persistence

## Date
2026-08-13

## Status
Accepted

## Context
The meditation session feature had a recurring bug where starting a session, turning off the screen, and returning later would find the app completely closed and all session data lost. This happened repeatedly despite multiple attempted fixes.

## Root Causes (3 interconnected)
1. **Memory-only storage**: Both MeditationViewModel and MeditationService accumulated ALL telemetry data in in-memory lists. Nothing was written to the database until the session was explicitly stopped. When Android killed the process, everything evaporated.
2. **Service told NOT to save**: stopMeditationService() sent shouldSave=false, so the Service discarded its data. The ViewModel's processSession() was supposed to save, but if the process died first, data was gone forever.
3. **No emergency save or recovery**: Service.onDestroy() never persisted session data. No mechanism existed to detect or recover interrupted sessions.

## Decision
Implemented incremental database persistence with crash recovery:

1. **Session row created immediately at start** — MeditationSessionRecorder.startSession() now creates a DB row with completed=false synchronously (via runBlocking) when the session begins. This guarantees the session exists even if the process is killed seconds later.

2. **Periodic telemetry flushing** — MeditationService runs a flush job every 15 seconds that writes accumulated telemetry to the DB and updates the session duration. Maximum data loss is now 15 seconds instead of 100%.

3. **Emergency save in onDestroy()** — When the Service is destroyed, it calls sessionRecorder.emergencySave() which flushes remaining telemetry and marks the session as completed with the last-known duration.

4. **Orphan session recovery** — On app launch (ViewModel init), Service restart (START_STICKY null intent), and before starting a new session, the app checks for sessions left with completed=false. Sessions are only recovered if they're truly stale (gap > 60s since last duration update), preventing premature finalization of active sessions.

5. **ViewModel updates existing row** — When the user stops via UI, processSession() now finds the existing incomplete session row (created by the Service) and UPDATEs it with full analytics, rather than INSERTing a duplicate. Uses @Update (not INSERT OR REPLACE) to avoid CASCADE-deleting telemetry.

## Files Changed
- MeditationSessionEntity.kt — Added `completed` column (DB migration v39→v40)
- MeditationSessionDao.kt — Added getIncompleteSessions, getMostRecentIncompleteSession, finalizeSession, deleteIncompleteShorterThan, update methods
- WagsDatabase.kt — Migration v39→v40, version bump to 40
- DatabaseModule.kt — Registered MIGRATION_39_40
- MeditationSessionRecorder.kt — Major rewrite for incremental persistence
- MeditationService.kt — Added periodic flush job, emergency save, orphan recovery
- MeditationViewModel.kt — processSession updates existing row, init recovers orphans, passes posture/timer to Service
- MeditationRepository.kt — Added recovery, finalization, update methods

## Consequences
- Maximum data loss on process death: 15 seconds (vs 100% previously)
- DB schema version bumped to 40 (migration handles upgrade transparently)
- Minimal performance impact: one INSERT at session start, one batch INSERT every 15s