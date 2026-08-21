## ADR: Pinned settings banner + two-line history filter header

**Date:** 2026-08-21
**Status:** Accepted

### Context
1. On Min Breath and Progressive O2, the past-session section header crammed the title and the "All" + filter-summary buttons into one Row, so long filter summaries ellipsized.
2. On most apnea session screens the one-line settings summary banner scrolled away with the content; users lost sight of the active settings while scrolling and during sessions.

### Decision
- **Two-line history header** (MinBreathScreen `SessionHistorySection`, ProgressiveO2Screen `BreathPeriodHistorySection`): title on its own Row, filter buttons on a full-width Row below it; the filter OutlinedButton takes `Modifier.weight(1f)` so the complete summary is readable.
- **Pinned settings banner**: banner moved OUT of the scroll container onto a fixed outer Column directly under the top bar.
  - MinBreath / ProgressiveO2 / ContractionTable / TableTraining: outer `Column(fillMaxSize().padding(padding))` holds the banner, then the former scrollable Column becomes `weight(1f)` inside it.
  - ApneaTableScreen: banner hoisted out of the LazyColumn into the fixed parent Column.
  - FreeHoldActiveScreen and ApneaScreen already had fixed-position banners — unchanged.
- **Session gating**: on screens with an editable-settings dialog (MinBreath, ProgressiveO2, ContractionTable) the banner's `onClick` is `if (!state.isSessionActive) {{ showSettingsDialog = true }} else null` — matching the pre-existing FreeHoldActiveScreen pattern. While a session runs the banner is a plain non-clickable label (no underline); it stays visible throughout the session. ApneaTable/TableTraining banners remain label-only (settings are chosen on the main apnea screen).

### Consequences
- Settings line is always visible on every apnea screen, idle or mid-session.
- No ViewModel/domain changes; purely layout restructuring. Banner component itself unchanged (its nullable `onClick` already supported the label mode).