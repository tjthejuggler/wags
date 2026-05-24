# ADR: Breathing Rate 0.05 BPM Increment Alignment

**Date:** 2026-05-24
**Status:** Accepted

## Context
RF Assessment protocols apply a random period offset (±0.9s) to breathing rates, then convert back to BPM. The `offsetBpm()` function was rounding to 0.01 BPM increments, and `deduplicateGrid()` was nudging duplicates by 0.01 BPM. This produced rates like 4.44 and 4.49 BPM, which violate the 0.05 BPM increment rule the app enforces elsewhere (e.g., `ResonanceRateRecommender.roundToTwentieth()`).

## Decision
All breathing rates in the system must align to 0.05 BPM increments (1/20th BPM). Changed:
1. `RfAssessmentOrchestrator.offsetBpm()`: Round to 0.05 (`* 20 / 20`) instead of 0.01 (`* 100 / 100`)
2. `RfAssessmentOrchestrator.deduplicateGrid()`: Nudge by 0.05 instead of 0.01
3. `SlidingWindowAnalytics`: Round `resonanceFrequencyBpm` to 0.05 before returning

## Consequences
- All stepped protocol rates (EXPRESS, STANDARD, DEEP, TARGETED, CONTINUOUS, CUSTOM, BEST_RATES) now produce 0.05-aligned BPM values
- Sliding Window protocol's resonance frequency result is also 0.05-aligned
- Downstream consumers (ResonanceRateRecommender, pacer, UI) are compatible — they already expect or round to 0.05
- Slightly less rate diversity per session (0.05 vs 0.01 granularity), but this matches the intended UX constraint