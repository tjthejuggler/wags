# ADR: Empty-Lung Safety Warning Gate

## Context
Dry empty-lung (residual volume) holds carry a higher risk profile than full-lung holds (violent contractions, chest tightness, coughing reflex). Users can enter hold/drill screens with Lung Volume = EMPTY selected and start immediately.

## Decision
A shared `EmptyLungWarningDialog` (app/src/main/java/com/example/wags/ui/apnea/EmptyLungWarningDialog.kt) is shown on the free-hold and drill screens as soon as the screen is entered with lung volume EMPTY — before the user can tap Start. It fires once per screen entry via `LaunchedEffect(lungVolume)` with a local shown-flag, so it also covers async settings load and a mid-screen switch to EMPTY, without re-showing after "hold again" resets on the same screen. Wired into: FreeHoldActiveScreenContent (free hold), ProgressiveO2Screen, MinBreathScreen (drills). Any future hold/drill screen with a lung-volume setting should wire the same gate.

## Consequences
- Dialog overlays the screen, so Start cannot be tapped before acknowledging (OK).
- No persistence: the warning re-appears on every fresh screen entry with EMPTY selected (intentional, safety-first).
- FULL/PARTIAL lung volumes are unaffected.