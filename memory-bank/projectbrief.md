# WAGS — Project Brief

*Last updated: 2026-03-28*

## Overview

**WAGS** (Working title) is a clinical-grade Android biofeedback application targeting Polar H10 (ECG/RR) and Polar Verity Sense (PPG/PPI) chest strap / arm band hardware. It is a personal project — no commercial licenses are used for charting.

## Core Features

1. **Morning HRV Readiness Scoring** — 2–5 minute supine RR recording → artifact correction → time-domain & frequency-domain HRV → Z-score readiness (1–100 scale) against a 14-day rolling baseline.
2. **Morning Readiness (Orthostatic)** — Extended readiness protocol with supine/standing phases, orthostatic heart rate recovery (OHRR), 30:15 ratio, and respiratory rate estimation via accelerometer.
3. **Resonance Frequency Breathing (HRVB)** — Visual pacer at 4.0–7.0 BPM with coherence scoring; includes RF assessment discovery mode that cycles through rates.
4. **Static Apnea Training** — O2 tables (increasing hold, constant ventilation), CO2 tables (constant hold, decreasing ventilation), free hold logging with personal bests, Garmin watch integration for underwater holds.
5. **Meditation / NSDR Session Analytics** — Bradycardia detection via linear regression, RMSSD trajectory, autonomic balance index, HR sonification, Spotify integration for audio.
6. **Garmin Watch Companion** — Connect IQ app on Garmin watches for apnea free holds with data sync back to the Android app.

## Target Hardware

- **Polar H10** — ECG at 130 Hz, RR intervals (raw 1/1024s units)
- **Polar Verity Sense** — PPG/PPI with skin contact and error estimate validation
- **Garmin watches** — Connect IQ companion app for apnea training
- **Android phone** — minSdk 26, compileSdk/targetSdk 36

## Project Constraints

- Personal project — no commercial chart licenses (SciChart, LightningChart)
- Real-time charts use custom hardware-accelerated Canvas composables
- Historical charts use Vico (open-source Compose charting)
- No file may exceed 500 lines (modularity guardrail)
- Simplicity over over-engineering

## Integration Points

- **Tail app** — IPC via ContentProvider + broadcast for habit tracking integration
- **Spotify** — OAuth PKCE flow for playback control during meditation/NSDR sessions
- **YouTube** — oEmbed metadata fetch for audio URLs
