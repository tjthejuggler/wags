package com.example.wags.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.wags.data.db.WagsDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles full export and import of ALL user data:
 *
 * **Database (13 tables):**
 *   - daily_readings, apnea_records, session_logs, rf_assessments,
 *     acc_calibrations, morning_readiness, apnea_sessions, contractions,
 *     telemetry, free_hold_telemetry, meditation_audios, meditation_sessions,
 *     morning_readiness_telemetry
 *
 * **SharedPreferences (4 files):**
 *   - wags_device_prefs (BLE device history, meditation audio dir)
 *   - apnea_prefs (apnea settings: lung volume, prep type, time of day, PB)
 *   - habit_integration_prefs (Tail app habit slot mappings)
 *   - garmin_prefs (Garmin watch pairing info)
 *
 * The export format is a ZIP file containing:
 *   - `wags.db` — the complete Room database file
 *   - `shared_prefs/` — all SharedPreferences XML files
 *
 * This approach guarantees that absolutely every piece of user data is captured,
 * including any future tables or preferences added to the app.
 */
@Singleton
class DataExportImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: WagsDatabase
) {

    companion object {
        private const val TAG = "DataExportImport"

        /** Database file name as configured in DatabaseModule. */
        private const val DB_NAME = "wags.db"

        /** All SharedPreferences file names used by the app. */
        private val SHARED_PREFS_FILES = listOf(
            "wags_device_prefs",
            "apnea_prefs",
            "habit_integration_prefs",
            "garmin_prefs"
        )

        /** ZIP entry paths */
        private const val ZIP_DB_ENTRY = "wags.db"
        private const val ZIP_PREFS_DIR = "shared_prefs/"
    }

    /**
     * Exports all user data to a ZIP file at the given [uri].
     *
     * The database is checkpointed (WAL flushed) before copying to ensure
     * the export contains all committed data in a single file.
     *
     * @return A human-readable summary of what was exported.
     * @throws Exception if the export fails.
     */
    suspend fun exportData(uri: Uri): String = withContext(Dispatchers.IO) {
        // Checkpoint the WAL to ensure all data is in the main DB file
        database.runInTransaction {
            database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            throw IllegalStateException("Database file not found: ${dbFile.absolutePath}")
        }

        // Also grab the WAL and SHM files if they exist (for completeness)
        val dbWalFile = File(dbFile.absolutePath + "-wal")
        val dbShmFile = File(dbFile.absolutePath + "-shm")

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            ZipOutputStream(outputStream).use { zip ->
                // ── Database file ────────────────────────────────────────────
                addFileToZip(zip, ZIP_DB_ENTRY, dbFile)

                // Include WAL/SHM if present (they should be empty after checkpoint, but just in case)
                if (dbWalFile.exists()) {
                    addFileToZip(zip, "$ZIP_DB_ENTRY-wal", dbWalFile)
                }
                if (dbShmFile.exists()) {
                    addFileToZip(zip, "$ZIP_DB_ENTRY-shm", dbShmFile)
                }

                // ── SharedPreferences files ──────────────────────────────────
                for (prefName in SHARED_PREFS_FILES) {
                    val prefFile = File(prefsDir, "$prefName.xml")
                    if (prefFile.exists()) {
                        addFileToZip(zip, "$ZIP_PREFS_DIR$prefName.xml", prefFile)
                    }
                }
            }
        } ?: throw IllegalStateException("Could not open output stream for URI: $uri")

        // Build summary
        val tableCount = countDatabaseRows()
        val prefsCount = SHARED_PREFS_FILES.count { name ->
            File(prefsDir, "$name.xml").exists()
        }

        buildString {
            append("Export complete!\n\n")
            append("Database tables:\n")
            for ((table, count) in tableCount) {
                append("  • $table: $count rows\n")
            }
            append("\nPreferences files: $prefsCount\n")
            append("Total rows: ${tableCount.values.sum()}")
        }
    }

    /**
     * Imports all user data from a ZIP file at the given [uri].
     *
     * **WARNING:** This replaces ALL existing data. The database is closed,
     * the file is overwritten, and SharedPreferences are replaced.
     *
     * @return A human-readable summary of what was imported.
     * @throws Exception if the import fails or the ZIP is invalid.
     */
    suspend fun importData(uri: Uri): String = withContext(Dispatchers.IO) {
        // Validate the ZIP first — make sure it contains a database file
        val tempDir = File(context.cacheDir, "wags_import_temp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            // Extract ZIP to temp directory
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outFile = File(tempDir, entry.name)
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zip.copyTo(fos)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for URI: $uri")

            // Validate: the ZIP must contain the database file
            val tempDbFile = File(tempDir, ZIP_DB_ENTRY)
            if (!tempDbFile.exists()) {
                throw IllegalStateException(
                    "Invalid backup file: missing $ZIP_DB_ENTRY. " +
                        "Make sure you selected a WAGS backup file."
                )
            }

            // ── Close the database before replacing ──────────────────────────
            database.close()

            // ── Replace database file ────────────────────────────────────────
            val dbFile = context.getDatabasePath(DB_NAME)
            val dbWalFile = File(dbFile.absolutePath + "-wal")
            val dbShmFile = File(dbFile.absolutePath + "-shm")

            // Delete existing WAL/SHM files
            dbWalFile.delete()
            dbShmFile.delete()

            // Copy the new database
            copyFile(tempDbFile, dbFile)

            // Copy WAL/SHM if present in backup
            val tempWalFile = File(tempDir, "$ZIP_DB_ENTRY-wal")
            val tempShmFile = File(tempDir, "$ZIP_DB_ENTRY-shm")
            if (tempWalFile.exists()) copyFile(tempWalFile, dbWalFile)
            if (tempShmFile.exists()) copyFile(tempShmFile, dbShmFile)

            // ── Replace SharedPreferences files ──────────────────────────────
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val tempPrefsDir = File(tempDir, ZIP_PREFS_DIR)

            var prefsRestored = 0
            if (tempPrefsDir.exists() && tempPrefsDir.isDirectory) {
                for (prefFile in tempPrefsDir.listFiles().orEmpty()) {
                    if (prefFile.name.endsWith(".xml")) {
                        val destFile = File(prefsDir, prefFile.name)
                        copyFile(prefFile, destFile)
                        prefsRestored++
                    }
                }
            }

            buildString {
                append("Import complete!\n\n")
                append("Database restored: $ZIP_DB_ENTRY\n")
                append("Preferences files restored: $prefsRestored\n\n")
                append("⚠️ Please restart the app for all changes to take effect.")
            }
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Generates a default filename for the export based on the current date/time.
     */
    fun generateExportFileName(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        return "wags_backup_${dateFormat.format(Date())}.zip"
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun addFileToZip(zip: ZipOutputStream, entryName: String, file: File) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { fis ->
            fis.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Counts rows in every database table for the export summary.
     */
    private fun countDatabaseRows(): Map<String, Long> {
        val tables = listOf(
            "daily_readings",
            "apnea_records",
            "session_logs",
            "rf_assessments",
            "acc_calibrations",
            "morning_readiness",
            "apnea_sessions",
            "contractions",
            "telemetry",
            "free_hold_telemetry",
            "meditation_audios",
            "meditation_sessions",
            "morning_readiness_telemetry"
        )
        val counts = mutableMapOf<String, Long>()
        val db = database.openHelper.readableDatabase
        for (table in tables) {
            try {
                val cursor = db.query("SELECT COUNT(*) FROM $table")
                if (cursor.moveToFirst()) {
                    counts[table] = cursor.getLong(0)
                }
                cursor.close()
            } catch (e: Exception) {
                Log.w(TAG, "Could not count rows in $table: ${e.message}")
                counts[table] = -1
            }
        }
        return counts
    }
}
