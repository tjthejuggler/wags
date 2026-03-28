# WAGS — Product Context

*Last updated: 2026-03-28*

## Why This Product Exists

WAGS is a personal biofeedback tool for an individual practitioner of freediving/apnea training, HRV-based readiness monitoring, resonance frequency breathing, and meditation/NSDR. It consolidates multiple wellness modalities into a single app with clinical-grade HRV analysis.

## Problems It Solves

1. **Fragmented tools** — No single app combines HRV readiness, resonance breathing, apnea tables, and meditation analytics with Polar sensor support.
2. **Poor artifact handling** — Most consumer HRV apps use naive filtering. WAGS implements the Lipponen & Tarvainen 2019 gold-standard artifact correction algorithm.
3. **Apnea training gaps** — Existing apnea apps lack BLE heart rate integration, Garmin watch support for underwater holds, and proper O2/CO2 table generation.
4. **Readiness scoring** — Z-score based readiness against a personal 14-day rolling baseline provides individualized, not population-based, scoring.

## User Experience Goals

- **Dark clinical aesthetic** — Designed for use in low-light environments (morning readiness, meditation)
- **Minimal interaction during sessions** — Once a session starts, the app runs autonomously with audio/haptic cues
- **Rich post-session analytics** — Detailed breakdowns of HRV metrics, coherence scores, apnea performance
- **Personal best tracking** — Apnea records with confetti celebrations for new PBs
- **History and trends** — 14-day rolling charts for readiness, session history for all modalities

## Key User Flows

1. **Morning Readiness**: Open app → Start readiness → Lie still 2–5 min → View score + 14-day trend
2. **Morning Readiness (Orthostatic)**: Supine phase → Stand prompt → Standing phase → Comprehensive readiness report
3. **Resonance Breathing**: Select rate (or run discovery) → Follow visual pacer → View coherence score
4. **Apnea Free Hold**: Select lung volume + prep type → Start hold → Contractions tracking → View results + PB check
5. **Apnea Table**: Select O2/CO2 → Auto-generated table → State machine guides through rounds
6. **Meditation/NSDR**: Select duration + optional audio → Session runs → View bradycardia + HRV analytics
