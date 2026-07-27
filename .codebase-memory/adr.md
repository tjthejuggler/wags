# ADR: Coherence strip-chart foreground pattern

## Context
The live-session coherence graph on both [`ResonanceSessionScreen`](app/src/main/java/com/example/wags/ui/breathing/ResonanceSessionScreen.kt) and [`AssessmentRunScreen`](app/src/main/java/com/example/wags/ui/breathing/AssessmentRunScreen.kt) originally drew the same static line chart in both the background and the foreground, contradicting the user's two-layer convention used for HR/HRV:
- **Background** = long-term, dim/grey
- **Foreground** = recent window, bright/white, scrolling like a strip-chart recorder

The RR/RMSSD charts implement the foreground via [`RrStripChart.kt`](app/src/main/java/com/example/wags/ui/common/RrStripChart.kt), which plots every incoming beat at its wall-clock time. Coherence, however, is sampled only every ~5 s by [`CoherenceScoreCalculator`](app/src/main/java/com/example/wags/domain/usecase/breathing/CoherenceScoreCalculator.kt), so a naive port produced a sparse strip chart with very long line segments and a pile-up of points at the left edge.

## Decision
Introduce [`CoherenceStripChart.kt`](app/src/main/java/com/example/wags/ui/common/CoherenceStripChart.kt) that:
1. Stamps each new coherence sample at `now - SAMPLE_INTERVAL_MS` (5 s) — the time it should have been produced — instead of the ingestion time. This removes the "long flat lead" between points.
2. Densifies the line by emitting linearly-interpolated sub-points every `SUB_POINT_MS` (1 s) between consecutive anchors. The foreground therefore behaves like the RR/RMSSD strips: one dot per second, short segments, smooth per-second scrolling.
3. Uses the same Catmull-Rom spline + left-edge fade overlay + shimmer as `StripChartShell` so the visual style is identical to HR/HRV.
4. Uses a collision-safe fingerprint `(size shl 32) xor last.toBits()` so two near-equal coherence values cannot accidentally be deduplicated.

Background layer continues to be [`BackgroundLineChart`](app/src/main/java/com/example/wags/ui/common/BackgroundLineChart.kt), with stroke width raised to 2 dp and alpha to 0.6 to satisfy the "slightly more visible" request without competing with the foreground.

## Consequences
- Foreground coherence strip on both breathing screens now matches the RR/RMSSD strips: smooth, ~1 dot/s, short segments, left-edge fade.
- The background long-term line is now slightly more visible across **all three** charts (coherence, RR, RMSSD) since they share `BackgroundLineChart`.
- `AsmCoherenceChart` / `RsCoherenceChart` composables remain in the codebase but are no longer invoked for live strips; they can be removed in a follow-up cleanup if desired.
- No data-layer changes; the ViewModels still expose `coherenceHistory: List<Float>` at 5 s cadence.