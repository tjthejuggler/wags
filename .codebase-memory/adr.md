# ADR: Resonance prep linking to apnea records (5-minute window)

Date: 2026-08-16
Status: Accepted

## Context
Apnea holds/drills can declare prepType = RESONANCE, but the resonance breathing session that preceded the activity was not linked to the record, so its specifics (rate, coherence, HRV) could not be shown in the apnea record details screen. Existing DB rows also needed retroactive linking.

## Decision
1. **Link storage**: `apnea_records.resonanceSessionId` (nullable FK-by-convention to resonance_sessions.sessionId), added in DB v41.
2. **Timestamp semantics**: resonance_sessions.timestamp = session END; apnea_records.timestamp = activity END. Activity start is estimated as `record.timestamp - COALESCE(apnea_sessions.totalSessionDurationMs (matched on identical timestamp for table/drill records), record.durationMs)`.
3. **Linking rule**: a resonance session qualifies if its END falls within [activityStart - 5min, activityStart]. Implemented once in `ApneaRepository.withResonanceLink()` at the saveRecord/updateRecord choke point so all save paths (tables, drills, free holds, MinBreath, ProgressiveO2, Garmin sync) get linking for free.
4. **Retroactive backfill**: MIGRATION_40_41 runs the same rule in SQL (correlated subquery) for existing RESONANCE-prep rows with NULL resonanceSessionId.
5. **Prep gate**: `ResonancePrepGate` (@Singleton) exposes `isLocked: Flow<Boolean>` (combine of latest resonance END + 2s ticker) and `isLockedNow()`. Lock engages when no resonance session ended within the last 5 minutes. All four prep-type ViewModels (Apnea, MinBreath, ProgressiveO2, FreeHoldActive) expose `resonancePrepLocked` in UiState, guard RESONANCE selection, and auto-deselect RESONANCE when the window expires.
6. **UI**: 🔒 badge on the RESONANCE chip in ApneaScreen SettingChip and FreeHoldSettingsDialog (mirrors HyperLockManager badge pattern); locked chips are non-selectable.
7. **Details screen**: ApneaRecordDetailScreen renders a "Resonance Prep" card (rate, IE ratio, coherence mean/max, high-coherence time, RMSSD/SDNN, artifact %, points) for RESONANCE-prep records, with "Session not linked" fallback.

## Consequences
- RESONANCE-prep records saved without a qualifying prior resonance session store resonanceSessionId = NULL and show the fallback row.
- updateRecord clears the link when prepType changes away from RESONANCE and re-links (via save path) when changed to RESONANCE.
- The gate is time-based only; it does not verify the linked session belongs to the same user/device (single-user app).