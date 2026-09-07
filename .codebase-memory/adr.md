# ADR: Settings re-sync on ApneaScreen resume (all session-type screens)

**Date:** 2026-09-07
**Status:** Accepted (extended)

## Context
The 5 apnea settings (lung volume, prep type, time of day, posture, audio) live in the `apnea_prefs` SharedPreferences and can be edited from the main ApneaScreen chips and from every apnea session-type screen (FreeHoldActiveScreen dialog, ContractionTable, MinBreath, ProgressiveO2 settings sections), plus "Repeat Hold" via `ApneaRecordDetailViewModel.prepareRepeatHold`. `ApneaViewModel` only read those keys in `init`, so edits made while the main screen sat in the back stack were not reflected when the user popped back.

## Decision
- `ApneaViewModel.syncSettingsOnResume()` re-reads `setting_lung_volume`, `setting_prep_type`, `setting_posture`, `setting_audio` from apnea_prefs and adopts changed values through the existing setters (preserving HYPER/RESONANCE lock checks and guided/biofeedback side effects). It is generic: it adopts writes from ANY screen, not just Free Hold.
- Time of Day: `ApneaViewModel` does not persist tod (smart-set from the clock). Every session-type ViewModel (`FreeHoldActiveViewModel.updateTimeOfDay`, `ContractionTableViewModel.setTimeOfDay`, `MinBreathViewModel.setTimeOfDay`, `ProgressiveO2ViewModel.setTimeOfDay`) now stamps `setting_tod_edit_ms` when the user edits tod there. `syncSettingsOnResume()` adopts the tod value only when the stamp is newer than the VM's creation watermark (`todEditWatermarkMs`), once per stamp.
- `ApneaScreen`'s ON_RESUME `DisposableEffect` calls `viewModel.syncSettingsOnResume()` before `refreshDrillParams()`/`refreshForecast()`.

## Consequences
- Settings edits made on ANY apnea session-type screen now appear on the main apnea screen when navigating back, without recreating the ViewModel.
- Convention: any future writer that deliberately changes tod outside the main screen must also write `setting_tod_edit_ms` or the main screen will ignore the tod change.
- All four session ViewModels already persisted the 4 non-tod settings under the same keys, so only the tod stamp needed adding to ContractionTable/MinBreath/ProgressiveO2.