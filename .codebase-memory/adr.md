# ADR: App-wide portrait orientation lock

**Date:** 2026-09-08
**Status:** Accepted

## Context
Holding the phone sideways rotated the main screen and apnea session screens (Min Breath, Progressive O2, etc.) into landscape. The app should be portrait everywhere, with landscape only on select large chart screens.

## Decision
- Set `android:screenOrientation="portrait"` on `MainActivity` in `AndroidManifest.xml`. This is the single source of truth for the default orientation; runtime `requestedOrientation` overrides still work on top of it.
- Intentional landscape exceptions remain the screens that force it at runtime: `TimeChartScreen`, `TrophyChartScreen`, `PbChartScreen` (dedicated full-screen graphs).
- `RateRecommendationScreen` intentionally sets `SCREEN_ORIENTATION_UNSPECIFIED` to allow rotation (it has explicit landscape layouts); on dispose it restores the captured original (now portrait), so no rotation leaks to other screens.
- The unused `LockPortrait()` composable in `ui/common/SessionGuards.kt` is retained as a utility but not required for the manifest-level policy.

## Consequences
- All screens default to portrait regardless of device rotation.
- Landscape-capable screens must explicitly opt in via `requestedOrientation` overrides.
- PiP support (`supportsPictureInPicture`) is unaffected by the orientation lock.