package com.example.wags.data.backup

import android.content.Context
import android.util.Log
import com.example.wags.data.repository.DataExportImportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic on-device backup manager.
 *
 * Writes one full app-data ZIP per calendar day into
 * `${filesDir}/auto_backups/wags_autobackup_YYYY-MM-DD.zip`, keeping the last
 * [KEEP_LAST_N] files and pruning older ones.
 *
 * Design notes:
 *  * The backup is a full ZIP (Room DB + WAL/SHM + every SharedPreferences
 *    file) — identical byte-for-byte to the "Export" feature in Settings,
 *    just written to app-internal storage instead of a user-picked Uri.
 *  * We write **at most once per calendar day** (skip if today's file already
 *    exists) so that repeated app starts on the same day do not thrash the
 *    disk or churn through rotation slots.
 *  * The whole thing runs on `Dispatchers.IO` off the main thread from the
 *    application-scoped `CoroutineScope`, so it never blocks UI startup.
 *  * Failure is swallowed and logged — a failed automatic backup must never
 *    prevent the app from launching.
 *
 * Storage cost: a typical backup is < 1 MB; 14 backups ≈ < 14 MB total,
 * which is negligible on any modern Android device.
 */
@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportRepository: DataExportImportRepository,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AutoBackup"

        /** Directory (under app internal storage) where auto-backups live. */
        private const val BACKUP_DIR_NAME = "auto_backups"

        /** How many daily backups to retain. Two weeks of history. */
        const val KEEP_LAST_N = 14

        /** Filename prefix — used both to write and to identify rotation candidates. */
        private const val FILE_PREFIX = "wags_autobackup_"
        private const val FILE_SUFFIX = ".zip"

        /** Date format used inside auto-backup filenames. */
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    /**
     * Kicks off a background auto-backup pass. Safe to call from
     * [android.app.Application.onCreate] — it returns immediately and does
     * all work on the IO dispatcher.
     */
    fun runOnStartup() {
        scope.launch {
            try {
                performBackupIfNeeded()
            } catch (t: Throwable) {
                // NEVER let a backup failure crash the app.
                Log.w(TAG, "Auto-backup failed: ${t.message}", t)
            }
        }
    }

    /**
     * Runs a backup if today's file doesn't exist yet, then rotates old files.
     * Exposed as suspend for potential future direct callers (Settings screen,
     * WorkManager, tests) — the normal entry point is [runOnStartup].
     */
    suspend fun performBackupIfNeeded(): BackupResult = withContext(Dispatchers.IO) {
        val dir = backupDir()
        dir.mkdirs()

        val today = DATE_FORMAT.format(Date())
        val target = File(dir, "$FILE_PREFIX$today$FILE_SUFFIX")

        if (target.exists() && target.length() > 0) {
            Log.i(TAG, "Auto-backup for $today already exists (${target.length()} bytes) — skipping")
            pruneOldBackups(dir)
            return@withContext BackupResult.Skipped(target)
        }

        val started = System.currentTimeMillis()
        val bytes = exportRepository.exportToFile(target)
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "Auto-backup written: ${target.name} ($bytes bytes, ${elapsed}ms)")

        pruneOldBackups(dir)
        BackupResult.Created(target, bytes, elapsed)
    }

    /**
     * Lists existing auto-backup files, newest first.
     * Useful for a future in-app "restore from local backup" UI.
     */
    fun listBackups(): List<File> {
        val dir = backupDir()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { it.name } ?: emptyList()
    }

    /** Returns the internal-storage directory that holds auto-backups. */
    fun backupDir(): File = File(context.filesDir, BACKUP_DIR_NAME)

    private fun pruneOldBackups(dir: File) {
        val all = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        } ?: return
        if (all.size <= KEEP_LAST_N) return

        // Sort by filename (which is date-formatted YYYY-MM-DD, so lexical == chronological).
        val sortedNewestFirst = all.sortedByDescending { it.name }
        val toDelete = sortedNewestFirst.drop(KEEP_LAST_N)
        for (f in toDelete) {
            val ok = f.delete()
            Log.i(TAG, "Pruned old auto-backup ${f.name} (deleted=$ok)")
        }
    }

    /** Result of a single auto-backup attempt. */
    sealed class BackupResult {
        data class Created(val file: File, val bytes: Long, val elapsedMs: Long) : BackupResult()
        data class Skipped(val existingFile: File) : BackupResult()
    }
}
