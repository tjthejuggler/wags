# ADR: Music Tail Habit Integration with Time-of-Day Deduplication

**Date:** 2026-05-09
**Status:** Accepted

## Context
We want to add a new Tail habit integration for "music" — when an apnea session (of any kind: free hold, table training, progressive O2, min breath, or advanced) has its audio setting set to MUSIC, we want to increment the corresponding Tail habit.

The complication: we only want to send the increment signal once per TimeOfDay bucket (Morning/Day/Night) per calendar day. So even if a user does 3 drills with music in the Morning, we only increment once. The maximum possible increments per day is 3.

## Decision
- Added `Slot.MUSIC` to `HabitIntegrationRepository.Slot` enum with keys `habit_id_music` / `habit_name_music` and label "Music Session".
- Added `sendMusicHabitIncrementIfNeeded(audioSetting: String, timeOfDay: String)` method to `HabitIntegrationRepository` that:
  1. Checks if `audioSetting == AudioSetting.MUSIC.name` (skips if not)
  2. Parses `timeOfDay` string into `TimeOfDay` enum (skips if invalid)
  3. Checks a SharedPreferences boolean key `habit_music_sent_{TOD}_{yyyy-MM-dd}` — if already true, skips
  4. Marks the key as true before firing the broadcast (prevents double-fire on crash)
  5. Calls `sendHabitIncrement(Slot.MUSIC)`
- The "effective audio" logic (MUSIC with no tracks played → SILENCE) is handled at each call site before calling the method.
- Called from all 5 apnea completion points: ApneaViewModel.stopFreeHold, ApneaViewModel.onStateChanged (TABLE COMPLETE), FreeHoldActiveViewModel.stopFreeHold, MinBreathViewModel.saveSession, ProgressiveO2ViewModel.saveSession, AdvancedApneaViewModel.saveCompletedSession.
- Updated SettingsViewModel (HabitPartialState, copySlot, buildInitialHabitState) and SettingsScreen (TailAppIntegrationCard) to expose the new MUSIC slot in the UI.

## Consequences
- Maximum 3 music habit increments per day (one per TimeOfDay bucket).
- SharedPreferences keys accumulate daily but are tiny; old dates are simply never true-checked again.
- The deduplication is local to this device; if the user uses WAGS on another device, it could send another increment for the same TimeOfDay. This is acceptable for now.