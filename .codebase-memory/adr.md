# ADR: Auto-recording eucapnic breathing configurations on session use

## Status
Accepted (2026-08-14, issue 1786693848339)

## Context
Users complained that eucapnic diaphragmatic breathing configurations only entered the "Past Configurations" list when explicitly saved via the Save Configuration dialog. Any configuration actually used for a session was lost unless manually saved first.

## Decision
Record every eucapnic configuration at the single funnel point through which ALL eucapnic sessions run: `EucapnicPacerViewModel.startPrep()` → `EucapnicConfigRepository.recordSessionUse(config)`. Verified via graph trace that standalone prep and all four apnea prep flows (FREE_HOLD, PROGRESSIVE_O2, MIN_BREATH, APNEA_TABLE) navigate to EucapnicPacerScreen, whose LaunchedEffect(Unit) always invokes startPrep with an initialConfig built from nav-route args.

Dedup strategy: fetch all rows (small table) via new `EucapnicPastConfigurationDao.getAll()` and match with epsilon-tolerant float comparison (0.01f) because config values round-trip through nav-route string interpolation. Matches bump `useCount`/`lastUsedAtMs` via atomic `incrementUseCount`; misses insert an auto-named entry ("Auto · <bpm> BPM · <prep>").

## Consequences
- Every session-used config is preserved without an explicit save; the manual save dialog remains for custom naming.
- Recording happens at session START (a started session counts as a use), not completion.
- New insertion point requirement: any future eucapnic session runner MUST route through startPrep (or call recordSessionUse itself) to stay recorded. SlidingWindowPacerEngine (RF assessment) is a separate feature and intentionally excluded.