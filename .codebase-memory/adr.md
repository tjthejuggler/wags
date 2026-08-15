# ADR: Eucapnic pacer gauge mirrors resonance breathing full-expansion behavior

## Context
The Eucapnic Diaphragmatic prep pacer (`EucapnicPacerGauge`) previously scaled its maximum circle radius by `EucapnicConfig.breathDepthPercent` (15–50% → 0.30–1.00 of outer radius) to visualize breath depth. This diverged from the resonance breathing pacer (`BreathingPacerCircle`), which always expands fully 0→1.0. The color toggle also only changed the screen background, not the gauge colors.

## Decision
- The eucapnic gauge circle now always expands to the full outer radius regardless of configured breath depth, exactly matching resonance breathing's `BreathingPacerCircle`.
- The target lung fullness is communicated via a "to X%" label inside the circle (below the phase label) instead of by scaling the animation. `breathDepthPercent` is retained as a parameter but is used only for this label.
- The depth guide ring was removed (no longer meaningful).
- `EucapnicPacerGauge` gained a `useColors: Boolean` parameter; when enabled the circle uses `PacerInhaleColor`/`PacerExhaleColor` (same colored palette as resonance), otherwise the monochrome greys. `EucapnicPacerScreen` passes its persisted `breathing_colors` pref through, so the 🎨 toggle now drives both background tint and circle colors.
- Vibration (〰) and color (🎨) toggles live in the TopAppBar using the same pattern/SharedPreferences keys (`breathing_vibration`, `breathing_colors`) as resonance breathing and apnea drills.

## Consequences
- Single caller (`EucapnicPacerScreen`) was updated in the same change; no other impact.
- The engine (`EucapnicPacerEngine.getPacerRadius`) is unchanged — it already emits 0→1.0; scaling was purely a UI concern.