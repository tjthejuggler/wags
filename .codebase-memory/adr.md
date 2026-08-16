# ADR: On-device YouTube audio import — v2: background service + category chooser

**Date:** 2026-08-16 (v2)
**Status:** Accepted & verified end-to-end on device (SM-S918U1, app backgrounded during download)

## Context
v1 (2026-08-15) ran the import in `AudioImportViewModel.viewModelScope`: the app had to stay open (and in foreground) during downloads, and imports could only target the meditation library. User requested: (1) downloads that survive closing the app, (2) a Meditation vs Guided Apnea choice after sharing.

## Decision (v2)
1. **`AudioImportService`** (`data/meditation/`, FGS type `dataSync`): owns the import coroutine in `serviceScope`, holds a partial `WakeLock` (30 min cap) so screen-off/Doze doesn't stall the transfer, posts an ongoing progress notification (% + bytes) and a completion/failure notification, then `stopForeground(REMOVE)` + `stopSelf()`. Live UI state is a companion `StateFlow<AudioImportUiState>` (`Idle/Resolving/Downloading/Success/Failed`) — the import screen observes it; `AudioImportViewModel` was deleted.
2. **Category chooser**: `AudioImportScreen` shows two cards (🧘 Meditation/NSDR, 🤿 Guided Apnea) when idle + URL pending (`AudioImportBus.consumePendingUrl()`), then starts the service. One import at a time (service ignores starts while running).
3. **Storage**: both categories download into the SAME SAF audio tree. Meditation → root (as before, upsert `meditation_audios` by fileName). Guided apnea → `apnea_guided/` subfolder (matches the user's pre-existing manual folder), registered in `guided_audios` via `GuidedAudioManager` with the tree-based document URI (playable by `MediaPlayer` under the existing tree grant). Re-importing the same video **updates** the row by `sourceUrl` and deletes the superseded file (dedupe). The meditation folder scanner lists direct children only, so subfolder files never leak into the meditation picker.
4. **Importer refactor**: `YoutubeAudioImporter.download()` core returns `DownloadedAudio(docUri, fileName, title, channel)` with an optional subdirectory (`ensureSubdirectory` creates it via `MIME_TYPE_DIR` on demand); `import()` (meditation, DB upsert) and `downloadToSubfolder()` (no DB) wrap it.
5. **Process-lifetime guards**: MainActivity's 10-min background `killProcess` timer defers while `AudioImportService.isRunning`; manifest gains `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` (runtime-requested once, optional — imports run regardless).

## Verification
- Service started (via `run-as … am start-foreground-service --user 0` from adb) with the app backgrounded: FGS active, full 3.4 MB download into real `apnea_guided/`, `guided_audios` row created, service self-stopped, old duplicate file auto-deleted on re-import.
- Chooser UI confirmed on-screen via uiautomator dump ("What is this audio for?" + both cards).

## Gotchas discovered
- `am start-foreground-service` from plain shell fails on non-exported services; from `run-as` it needs `--user 0` (default user -2 triggers INTERACT_ACROSS_USERS denial).
- YouTube download speed is server-side throttling; not fixable client-side — mitigated by background execution instead.
- Room WAL again: DB verification must pull db+wal+shm or checkpoint first.

## Consequences
- User can share → pick category → close the app immediately; a notification reports completion.
- Guided apnea imports appear in the existing Guided MP3 picker (guided_audios Flow) with no UI changes needed there.
- dataSync FGS has a 6 h system cap (irrelevant for audio files); `onTimeout` cancels cleanly with partial-file cleanup.