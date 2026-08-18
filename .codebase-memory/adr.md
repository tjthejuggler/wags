# ADR: Global "breathing" brightness pulse via draw-phase scrim

## Context
User wanted all UI text and card borders across every screen to pulse in brightness together, in a slow-rhythmic-breathing rhythm (~6 breaths/min, 10 s cycle), preserving relative brightness differences between elements. An earlier iteration animated each card border individually (`pulsingCardBorder()`), which (a) was too subtle and (b) could not cover text without touching hundreds of `Text` call sites.

## Decision
Replace per-element border animation with a single global `BreathingOverlay` (`ui/theme/BreathingOverlay.kt`) mounted once in `MainActivity`'s root `Box`, on top of `WagsNavGraph` (hidden in PiP). It is a full-screen black scrim whose alpha animates 0 → 0.45 over 5 s and back (FastOutSlowInEasing, RepeatMode.Reverse). Because the dimming is multiplicative, ALL content (text, borders, cards) pulses by the same relative amount in the same rhythm while keeping relative brightness hierarchy. Card borders reverted to static `BorderStroke(1.dp, CardBorder)` so they don't double-pulse.

## Performance
The animated alpha is read inside `Modifier.graphicsLayer { alpha = dim }` — a draw-phase-only read, so no per-frame recomposition; each step is a cheap re-draw of one node. The Box has no pointer-input modifiers, so touches pass through to the UI underneath.

## Consequences
- Dialogs/AlertDialogs render in separate windows and therefore do NOT pulse.
- System status bar / keyboard do not pulse.
- Any future "pulse strength" tuning lives in one place (targetValue in BreathingOverlay.kt).