# ADR: Biofeedback sonification config + playback on all apnea session types (2026-09-07)

## Context
The biofeedback audio config button + popup (BiofeedbackPickerButton / SelectedBiofeedbackBanner / BiofeedbackPickerDialog) and the live HR/SpO2 sonification (BiofeedbackSonificationEngine) existed only in the Free Hold path (FreeHoldActiveScreen/FreeHoldActiveViewModel). Selecting audio=BIOFEEDBACK on Min Breath, Contraction Table, Progressive O2, or classic tables gave no config UI and no sound.

## Decision
1. Reuse the exact Free Hold UI trio (BiofeedbackPickerButton, SelectedBiofeedbackBanner, BiofeedbackPickerDialog from ui/apnea/BiofeedbackAudioPicker.kt) on every apnea setup screen: MinBreathScreen, ContractionTableScreen, ProgressiveO2Screen, ApneaTableScreen. Placement mirrors the Guided picker section (banner replaces button once a config exists).
2. Each ViewModel (MinBreathViewModel, ContractionTableViewModel, ProgressiveO2ViewModel, ApneaViewModel) now injects the singleton BiofeedbackSonificationEngine, loads samples in init, forwards liveHr/liveSpO2 unconditionally (cheap volatile writes), persists config in the shared apnea_prefs keys (biofeedback_hr_sound, biofeedback_spo2_texture, biofeedback_hr_volume, biofeedback_spo2_volume — shared across all screens so config is global), starts the engine in startSession/startFreeHold when audio==BIOFEEDBACK, and stops it in stop/cancel/onCleared.
3. Drill records persist biofeedbackHrSound/biofeedbackSpo2Texture via the existing ApneaRecordEntity columns (previously only written by Free Hold), so record detail screens keep working for all drill types.
4. Deliberate duplication of the ~40-line wiring per ViewModel (rather than a shared base class) keeps each drill VM self-contained, consistent with the existing per-VM Spotify/guided-audio duplication pattern in this codebase.

## Consequences
- Biofeedback works end-to-end on every apnea session type with one shared config.
- Engine is a singleton; concurrent sessions are not possible in-app, so start/stop races are bounded by lifecycle methods.
