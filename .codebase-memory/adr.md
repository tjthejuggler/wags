# ADR: Standardized feedback-toggle icon language (vibration / sound / color)

## Status
Accepted (2026-08-15)

## Context
The app had inconsistent feedback toggles: vibration was sometimes a rectangle emoji (📳) and sometimes a wavy line (〰); several screens (Meditation timer, VoiceVibrationToggles on drill screens, FreeHold PB-indication sub-options) used Checkboxes + text labels next to the icons.

## Decision
1. Vibration toggles ALWAYS use the wavy-line glyph "〰" (titleLarge, accent color when on, TextDisabled when off — no grayscale needed for monochrome glyph).
2. Sound/voice toggles ALWAYS use "🔊" (titleMedium, grayscale + TextDisabled when off, TextPrimary when on).
3. Color mode toggles ALWAYS use "🎨" (titleMedium, grayscale + TextDisabled when off, accent when on).
4. NO checkboxes or text labels next to vibration/sound/color toggles — the icon button itself is the toggle; state is conveyed by lit (colored) vs greyed (disabled).
5. Labeled feature master-toggles that are NOT vibration/sound/color icons (e.g. "Countdown Timer", "New Record Indication", "Guided Hyperventilation", "Start MP3 with Hyper") keep Checkbox + label.

## Consequences
- Canonical implementations: BreathingScreen / AssessmentPickerScreen (〰 + 🎨), ProgressiveO2ActiveScreen (🔊 + 〰), VoiceVibrationToggles.kt (shared 🔊 + 〰 row).
- Files updated 2026-08-15: AssessmentRunScreen, ResonanceSessionScreen, EucapnicPacerScreen (📳→〰), MeditationScreen, FreeHoldActiveScreen PbIndicationSection, VoiceVibrationToggles (checkbox→icon buttons).
- New feedback toggles must follow this pattern.