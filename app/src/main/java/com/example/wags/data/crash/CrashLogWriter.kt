package com.example.wags.data.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught-exception stack traces to files in the app's internal storage.
 *
 * Log directory: `{filesDir}/crash_logs/`
 * File naming:   `crash_YYYY-MM-dd_HH-mm-ss.txt`
 *
 * No special permissions are required — this uses the app-private `filesDir`.
 * Keeps at most [MAX_LOG_FILES] crash logs; oldest are pruned on each write.
 */
object CrashLogWriter {

    private const val DIR_NAME = "crash_logs"
    private const val MAX_LOG_FILES = 20

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val headerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)

    /** Directory where crash logs are stored. */
    fun logDir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /**
     * Write a crash log file for the given [throwable].
     * Called from the uncaught-exception handler — must not throw.
     */
    fun writeCrash(context: Context, threadName: String, throwable: Throwable) {
        try {
            val dir = logDir(context)
            val now = Date()
            val fileName = "crash_${dateFormat.format(now)}.txt"
            val file = File(dir, fileName)

            val sw = StringWriter()
            sw.append("=== WAGS CRASH LOG ===\n")
            sw.append("Time:   ${headerFormat.format(now)}\n")
            sw.append("Thread: $threadName\n")
            sw.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            sw.append("SDK:    ${android.os.Build.VERSION.SDK_INT}\n")
            sw.append("\n--- Stack Trace ---\n")
            throwable.printStackTrace(PrintWriter(sw))
            sw.append("\n--- End ---\n")

            file.writeText(sw.toString())

            // Prune old logs
            pruneOldLogs(dir)
        } catch (_: Exception) {
            // Never let crash-logging itself cause a secondary crash.
        }
    }

    /** Return all crash log files sorted newest-first. */
    fun listLogs(context: Context): List<File> =
        logDir(context)
            .listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** Delete all crash log files. */
    fun clearAll(context: Context) {
        logDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Delete a single crash log file. */
    fun delete(file: File) {
        file.delete()
    }

    private fun pruneOldLogs(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: return
        if (files.size > MAX_LOG_FILES) {
            files.drop(MAX_LOG_FILES).forEach { it.delete() }
        }
    }
}
