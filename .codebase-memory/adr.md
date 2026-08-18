# ADR: Dashboard days-since corner badges

**Date:** 2026-08-18
**Status:** Accepted

## Context
The apnea section shows tiny bordered corner badges (CornerBadge in ApneaScreen.kt) with whole days since each drill type was last performed (∞ when never). The user wanted the same squares on every session-type card on the main dashboard, and the 'Sessions' section label replaced by a 2.dp separator line (card borders are 1.dp).

## Decision
- Reuse the exact badge semantics: `HyperLockManager.daysSinceUsed(lastUsedMs, nowMs)` (whole days, null → ∞), with `now` captured via `remember { System.currentTimeMillis() }` per composition — day granularity makes ticking unnecessary, matching ApneaScreen.
- Data source: one lightweight `SELECT MAX(timestamp)` Flow per session table (morning_readiness, daily_readings, apnea_records, meditation_sessions, rapid_hr_sessions; resonance already had `observeLatestEnd`). Exposed as repository passthroughs and combined in DashboardViewModel into `SessionLastUse` (raw epoch-ms values; day math done in the composable).
- UI: local `CornerBadge` copy in DashboardScreen.kt (apnea's is private), floated TopEnd over each NavigationCard via Box overlay; the trailing arrow row gets 20.dp end padding when a badge is present, mirroring DrillCard.
- 'Sessions' header replaced with `HorizontalDivider(thickness = 2.dp, color = CardBorder)`.

## Consequences
- DashboardViewModel now injects ResonanceSessionRepository, ApneaRepository, MeditationRepository, RapidHrRepository (all additive; Hilt).
- Badges briefly show ∞ on first frame before flows emit — same behavior as the apnea screen.
- All DAO/repository changes are additive; no existing callers affected.