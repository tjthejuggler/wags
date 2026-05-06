## ADR — Morning Readiness Detail charts are phase-pure (2026-05-06)

### Context
On `MorningReadinessDetailScreen`, the per-session "Heart Rate" and "Rolling HRV (RMSSD)" cards were rendering both supine and standing telemetry on a single line, with the prominent **min/avg/max** chips computed over the full mixed series. Standing telemetry has higher HR and lower RMSSD, so the chip values silently differed between days where the user did vs skipped the standing portion — making day-to-day comparison meaningless. The DB schema (`MorningReadinessEntity`) was already correctly phase-segregated and the history-trend charts in `MorningReadinessHistoryViewModel` already plotted only `supineRhr` / `supineRmssdMs`, so the bug was purely in the detail-screen chart presentation layer.

### Decision
Make the primary "Heart Rate" and "Rolling HRV (RMSSD)" cards on the morning readiness detail screen **strictly supine-only**. When the user actually completed the standing portion, render two additional **smaller (compact)** cards below the orthostatic stats card titled "Heart Rate (standing)" and "Rolling HRV (standing)" containing only the standing-phase telemetry. `TelemetryChartCard` and `TelemetryLineChart` are now phase-pure: the in-chart stand-marker / "Supine→Stand avg" legend / X-axis phase labels were removed. The supine→stand transition narrative lives entirely in `OrthostasisStatsCard` which already shows pre-stand HR, peak stand HR, HR rise, and HRV change.

### Consequences
- Day-to-day comparison of the displayed avg/min/max HR & HRV is now **consistent regardless of whether the standing portion was performed** — the user's original complaint is resolved.
- No DB migration required: historical readings render correctly on the new layout because the change is presentation-only (telemetry has always been tagged with `phase = "SUPINE" | "STANDING"`).
- The orphan `HELP_STAND_MARKER_*` constants and the `standFraction` / `showStandingLabel` parameters are removed.
- Score calculation, history trend charts, dashboard "Today's Readiness" card, and `ReadingDetailCard` were already supine-anchored and were untouched.

### File touched
- `app/src/main/java/com/example/wags/ui/morning/MorningReadinessDetailScreen.kt`