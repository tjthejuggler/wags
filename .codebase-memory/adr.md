## ADR: Audio setting is authoritative — never auto-downgrade MUSIC to SILENCE

**Date:** 2026-07-10
**Status:** Accepted

### Context
Apnea drill sessions (Min-Breath, Progressive-O2, Free-Hold, and the O2-Table)
persist an `audio` field describing the audio mode used (SILENCE / MUSIC / GUIDED).
Previously, at save time each save-path computed an `effectiveAudio` that downgraded
`MUSIC` to `SILENCE` whenever the Spotify track-tracking list (`trackedSongs` /
`tableTracksPlayed` / `tracksPlayed`) came back empty:

```
val effectiveAudio = if (audio == MUSIC && trackedSongs.isEmpty()) SILENCE else audio
```

This heuristic was unreliable. A user ran a Min-Breath drill with MUSIC selected,
a song actually played, yet the session was saved as SILENCE (and in that case the
whole session appeared "missing" from the user's filtered history, since it was
filed under the wrong audio category). The track list can legitimately be empty due
to: playback outside Spotify, Spotify App Remote tracking gaps/disconnects, or API
hiccups. There is also no UI to re-attach the originally-played song after the fact,
so a mis-categorised MUSIC→SILENCE session is effectively unrecoverable.

### Decision
The user's explicitly-chosen `audio` setting is the single source of truth for the
persisted audio category. Save paths MUST persist the user's selected `audio` value
verbatim and MUST NOT reclassify MUSIC as SILENCE (or vice-versa) based on Spotify
track-tracking results. Spotify track tracking is retained solely for the song-log
(`saveSongLog`) and song-history features — it no longer influences categorisation.

### Consequences
- MUSIC sessions are always recorded as MUSIC, fixing history/stats mis-filing.
- A MUSIC session may legitimately have an empty song log (song played but not
  tracked); this is acceptable and expected.
- Six downgrade sites removed:
  - `MinBreathViewModel.saveSession`
  - `ProgressiveO2ViewModel.saveSession`
  - `ApneaViewModel.saveCompletedSession` (table music-habit + table record paths)
  - `ApneaViewModel.saveFreeHoldRecord` (two paths)
  - `FreeHoldActiveScreen.FreeHoldActiveViewModel.saveFreeHoldRecord`
