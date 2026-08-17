# ADR: Waveform-based, edge-triggered apnea vibration warnings

**Date:** 2026-08-17
**Status:** Accepted

## Context
Apnea drills (O₂/CO₂ tables, Progressive O₂, Contraction Tables) warned users of ending phases via per-tick single-shot vibrations (`vibrateBreathingCountdownTick` fired from every state emission, ~100 ms apart in Progressive O₂ — repeatedly within a second) with hard-coded durations/intensities. Users need fully customizable warnings: separate hold-ending and breath-ending warnings with configurable window length, intensity, beat rapidness, an optional final-second long/intense pulse, and a "same for both" link.

## Decision
1. **Single waveform per warning instead of per-tick pulses.** `ApneaAudioHapticEngine.playWarning()` builds one `VibrationEffect.createWaveform` (beats at the configured interval + optional final pulse) aligned to end exactly when the phase ends. Triggered exactly ONCE via edge detection when the countdown crosses into the configured window.
   - O₂/CO₂ tables: `ApneaCountdownTimer` now fires `onWarning` every second (voice cues filter internally); `ApneaViewModel.onWarning` compares `remainingSeconds == windowSec`.
   - Progressive O₂ / Contraction Tables: `previousTimerMs > windowMs && timerMs <= windowMs` edge detection in `handlePhaseTransition` (ticks arrive every ~100 ms).
2. **Config model** `ApneaVibrationWarningConfig` (enabled, windowSec, intensityPct, intervalSec, finalPulseEnabled, finalPulseMs, finalIntensityPct) persisted in `apnea_prefs` SharedPreferences; `breathSameAsHold` flag makes breaths reuse the hold config.
3. **Hold-end de-duplication:** when the hold countdown's final pulse is enabled, natural HOLD→BREATHE transitions skip the generic 500 ms `vibrateHoldEnd(countdownCovered = true)`; manual ends (endHoldEarly, stopFreeHold) always buzz.
4. **Cancellation:** every phase change and session stop/cancel calls `cancelWarningVibrations()`; the safety abort pattern cancels then fires.
5. **Scope:** Contraction Table holds are open-ended (end on contractions/user input) → no hold countdown there, only the breath warning. Free holds / Min Breath unaffected.
6. **UI:** Settings → Apnea card hosts voice/vibration master toggles + warning editors (sliders) + "Same vibration for holds & breaths" toggle + Test buttons.

## Consequences
- Arbitrary beat intervals are possible without depending on driver tick rate; timing stays accurate even if UI ticks jitter.
- The old `vibrateBreathingCountdownTick` API was removed; all three drill ViewModels were migrated.
- Defaults: hold = 5 s @ 80 %, 1 s beats, 1 s final @ 100 % (the reference setup); breath = 10 s @ 60 %, 1 s ticks, 400 ms final (mirrors legacy behaviour).