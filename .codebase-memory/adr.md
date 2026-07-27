## Multi-song Spotify selection in apnea song picker (2026-07-27)

**Decision:** The apnea "Choose a Song" picker now supports an ordered multi-select playlist (1,2,3…). Selection order is the playback order.

**Key design points:**
- `selectedSongs: List<SpotifyTrackDetail>` in each apnea ViewModel holds the ordered selection; tapping toggles membership and re-numbers (removal shifts later songs down).
- Song #1 is loaded via `SpotifyManager.preloadTrack()` ONLY when the first selection actually changes — this avoids re-launching Spotify / stealing focus on every tap (root cause of "app minimizes to Spotify on each click").
- Songs #2..N are appended to Spotify's queue via a new `SpotifyManager.queueTracks()` that calls Web API `addToQueue` directly — never launches Spotify, never steals focus.
- UI: `SongCard` always renders album art; the selection number is a small corner badge (top-start) overlaid on the art, not a full replacement. This fixed the regression where selected songs lost their album art.

**Files:** `ui/apnea/SongPickerComponents.kt` (SongCard badge), `data/spotify/SpotifyManager.kt` (queueTracks), and `selectSong` in `FreeHoldActiveScreen.kt`, `ApneaViewModel.kt`, `ProgressiveO2ViewModel.kt`, `MinBreathViewModel.kt`, `AdvancedApneaViewModel.kt`.