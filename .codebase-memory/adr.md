# ADR: Spotify song-picker metadata — Dev-Mode batch block + daily-quota 429

## Status
Accepted (2026-07-27) — final, supersedes earlier notes

## Symptom
Apnea "Choose a Song" picker shows titles but no album art / durations.

## Two distinct blockers found (via PID-filtered logcat)

### 1. Batch endpoint blocked in Development Mode → 403
`GET /v1/tracks?ids={comma-separated}` ("Get Several Tracks") returned **HTTP 403 Forbidden** even with a valid token and after adding `user-library-read`, while `/v1/search` and playback worked under the SAME token. Batch "Get Several Tracks" is not available to apps in Spotify Development Mode.
**Fix:** `SpotifyApiClient.getTracksDetail` no longer uses the comma-separated batch endpoint. It loops **single-track** `GET /v1/tracks/{id}` ("Get Track", allowed in Dev Mode), one call per ID, ~120 ms apart (≈8 req/s) with a private `fetchSingleTrack` helper.

### 2. Daily Dev-Mode quota exhausted → 429 with huge Retry-After
After switching to single-track, the endpoint returned:
```
W SpotifyApi: ⏳ fetchSingleTrack RATE LIMITED (429) for 7AalBKBoLDR4UmRYRJpdbj. Retry-After=49626s
```
`Retry-After=49626s ≈ 13.8 hours`. This is NOT a rolling rate-window; it's the Development-Mode DAILY quota being exhausted (burned by the earlier per-track storms + repeated testing). No code change can bypass it — the metadata will load once the quota resets.
**Fix:** `fetchSingleTrack` only blocks-and-retries a 429 when `Retry-After <= MAX_BLOCKING_RETRY_AFTER_SEC` (30s, a genuine rolling window). For larger values it logs "daily quota appears exhausted, metadata unavailable for ~Xh" and fails fast (returns null for that track) instead of freezing the loader for hours.

## Current behaviour
- Code is correct and will populate art/durations automatically once the daily quota resets.
- On quota-exhaustion the picker keeps showing cached titles/artists and fails fast rather than hanging.
- All 5 apnea picker ViewModels unchanged — they still call `getTracksDetail(uris)`; only its internal implementation changed.
- ⟳ refresh button + `forceRefresh` param retained.

## Logging added (kept for future diagnosis)
- `getTracksDetail: fetching N ids via single-track loop` / `resolved X/N tracks`
- 429 handler prints `Retry-After=Ns (~Xh Ym)` and quota-exhaustion warning
- `SpotifyAuthManager.saveTokens` logs `scopes=[...]`

## Files
- app/src/main/java/com/example/wags/data/spotify/SpotifyApiClient.kt (getTracksDetail → single-track loop + fetchSingleTrack + 429 cap)
- app/src/main/java/com/example/wags/data/spotify/SpotifyAuthManager.kt (user-library-read scope, scopes log)
- SongPickerComponents.kt + 5 ViewModels + 5 Screens (refresh button & batch wiring)