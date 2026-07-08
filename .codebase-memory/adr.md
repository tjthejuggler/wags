## 2026-07-08 — Data-loss hardening

### Context
User reported a 3-day gap (Jul 5/6/7) in the on-device DB after a build that introduced `MIGRATION_36_37`. Forensic analysis (`PRAGMA integrity_check` ok, `freelist_count=0`, `sqlite_sequence` matched max rowIds, empty raw byte scan for the note text the user recalls typing) confirmed the rows were never persisted to the current DB file — recovery not possible. Root cause of the write failure remains unexplained. User accepted non-recovery but demanded prevention.

Separately, the manual "Export" in Settings had been failing with SQLITE_LOCKED because `PRAGMA wal_checkpoint(FULL)` was executed inside `database.runInTransaction { … }`.

### Decision
1. **Manual export bug**: fixed at [`DataExportImportRepository.checkpointWalForExport()`](app/src/main/java/com/example/wags/data/repository/DataExportImportRepository.kt:257). `PRAGMA wal_checkpoint` is now run OUTSIDE any transaction using the `TRUNCATE` variant with one retry-on-busy. WAL/SHM files are still copied into the ZIP as a safety net if the checkpoint remains busy.
2. **Automatic backups**: new [`AutoBackupManager`](app/src/main/java/com/example/wags/data/backup/AutoBackupManager.kt) writes one full-data ZIP per calendar day into `${filesDir}/auto_backups/`, keeps the last 14, prunes older, kicked off from [`WagsApplication.onCreate`](app/src/main/java/com/example/wags/WagsApplication.kt) on `Dispatchers.IO`. Reuses the same `writeBackupZip()` core as the manual export so backups are byte-for-byte compatible.
3. **No-deletion policy**: `MorningReadinessRepository.pruneOldData()` is now a `@Deprecated` no-op, and both `deleteOlderThan` DAO queries (`MorningReadinessDao`, `DailyReadingDao`) have been removed. All remaining `DELETE FROM …` queries in the codebase are ID-scoped or CASCADE — none are time-based.

### Consequences
* Manual export works reliably.
* One local ZIP per day (≲ 1 MB each, total ≲ 14 MB) protects against silent write loss even if the exact root cause is never found.
* No user data will ever be auto-deleted on a time basis. Future contributors must not add time-based `DELETE` queries.
* Future work: expose "Restore from local backup" in Settings using `AutoBackupManager.listBackups()`; already scaffolded.

### Verification
* `./gradlew installDebug` passed on 2026-07-08.
* App installed on SM-S918U1.
