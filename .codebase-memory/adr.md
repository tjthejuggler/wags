# ADR: Biofeedback Sonification Audio Setting

**Status:** Accepted (promoted 2026-09-01; originally experimental 2026-08-31)

## Context
Biofeedback sonification for apnea holds: live HR drives a selectable instrument strike per heartbeat; live SpO2 shapes a peaceful background texture. Trialled experimentally first, then kept.

## Decision
- `AudioSetting.BIOFEEDBACK` is a full persisted audio setting. The temporary `isExperimental` / `persistedName()` downgrade machinery was removed; records store BIOFEEDBACK and it appears in history filters, record editing, stats and PB pools like any audio value.
- DB v43 → v44 (`MIGRATION_43_44`): `apnea_records.biofeedbackHrSound` and `biofeedbackSpo2Texture` TEXT NULL columns record the per-hold configuration; free-hold save populates them; record detail shows "Biofeedback Sound: 🔔 Gong · 🌌 Warm Pad". Record edits use entity copy so the fields survive. Export/import copies whole tables — covered automatically.
- `BiofeedbackSonificationEngine` (domain/usecase/session) synthesizes all sound live (no samples): HR instruments Gong/Tibetan Bell/Heartbeat/Marimba/Chime struck per beat on the audio sample clock; SpO2 textures Warm Pad/Ocean/Wind with volume/brightness/pitch mapped to 85–100% SpO2, one-pole smoothed.
- UI: "Choose Biofeedback Sound" button/banner on the free-hold setup screen (same pattern as Music/Guided pickers); dialog structured as independent sections for future richer modes. Config persists in SharedPreferences (`biofeedback_hr_sound`, `biofeedback_spo2_texture`).
- Layout: in plain-Row chip selectors (ApneaScreen settings, record-detail edit sheet) BIOFEEDBACK sits on its own row so the full label fits; FlowRow selectors wrap naturally.

## Consequences
- Biofeedback holds now count toward PB thresholds/combos under their own audio category.
- Other drill screens share the chip; engine playback is wired into the Free Hold flow only.