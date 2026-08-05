## Coherence strip chart scroll anchoring fix (2026-08-05)

**Context:** The short-term coherence foreground chart in resonance sessions
(ResonanceSessionScreen) and assessments (AssessmentRunScreen) exhibited a
jarring "halfway → 3/4 → halfway" scroll cycle. The RR/RMSSD strip charts on
the same screens scrolled correctly.

**Root cause:** `CoherenceStripChartState.ingest()` anchored the newest raw
sample at `wallTimeMs - SAMPLE_INTERVAL_MS` (5 s behind the cursor). With a
20 s window, that placed the right edge at 75 %, drifting to 50 % before the
next sample snapped it back. The RR/RMSSD charts anchor the newest beat at the
cursor (`wallTimeMs`), keeping the right edge at ~100 %–95 %.

**Decision:** Rewrote the coherence ingest anchoring to spread new samples
evenly between the last ingested sample time and `wallTimeMs`, with the newest
landing exactly at the cursor — identical to `RrStripChartState.ingest()`.
The densification (1 sub-point per SUB_POINT_MS) is unchanged.

**Impact:** Single file (`CoherenceStripChart.kt`), internal method only.
Public composable API unchanged; both call sites unaffected.