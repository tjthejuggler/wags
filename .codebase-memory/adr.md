# ADR: Tail Integration Protocol v2 — Minute-Based Habit Reporting

**Date:** 2026-08-08
**Status:** Accepted

## Context

The WAGS app integrates with the Tail habit-tracking app via explicit,
permission-guarded broadcasts. Previously, when a resonance-breathing session,
RF assessment, or meditation session completed, WAGS sent a simple
"increment by 1" signal — Tail just recorded that a session happened, with no
duration information.

The user wants Tail to receive the **actual number of minutes** each session
lasted, and also wants to retroactively backfill all past sessions.

## Decision

Implement **Protocol v2** with two enhancements, both backward compatible:

### 1. Real-time minute increments (EXTRA_MINUTES)
- Add an optional `EXTRA_MINUTES` (Int) extra to the existing
  `ACTION_INCREMENT_HABIT` broadcast.
- Tail reads it and uses it as the increment amount; falls back to 1 if absent.
- Only `RESONANCE_BREATHING` and `MEDITATION` slots send this extra.
- Minutes are rounded to nearest whole minute, minimum 1.

### 2. Retroactive backfill (ACTION_SET_HABIT_VALUES)
- New broadcast action with a JSON payload `{date: minutes}` for multiple dates.
- Tail SETS (replaces) the value for each date — idempotent.
- Triggered manually via a "Backfill Past Sessions" button in Settings.
- New `HabitBackfillManager` aggregates all past sessions from Room DB.

## Consequences

- **WAGS side:** Fully implemented. All call sites updated, backfill UI added.
- **Tail side:** Needs two changes documented in `plans/tail_integration_protocol_v2.md`.
- **Backward compatibility:** Old Tail app continues to work (ignores new extras).
- **Data model:** No Room schema changes needed — duration fields already exist
  in all three entity types (ResonanceSessionEntity, RfAssessmentEntity,
  MeditationSessionEntity).
