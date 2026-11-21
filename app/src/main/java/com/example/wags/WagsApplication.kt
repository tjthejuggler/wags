package com.example.wags

import android.app.Application
import android.util.Log
import com.example.wags.data.backup.AutoBackupManager
import com.example.wags.data.crash.CrashLogWriter
import com.example.wags.data.repository.EucapnicConfigRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WagsApplication : Application() {

    @Inject
    lateinit var autoBackupManager: AutoBackupManager

    @Inject
    lateinit var eucapnicConfigRepository: EucapnicConfigRepository

    @Inject
    lateinit var morningReadinessBackfillRunner: com.example.wags.data.repository.MorningReadinessBackfillRunner

    @Inject
    lateinit var localTimeBackfillRunner: com.example.wags.data.repository.LocalTimeBackfillRunner

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()

        // Kick off the automatic daily on-device backup. This writes a full
        // app-data ZIP to internal storage (${filesDir}/auto_backups/) once
        // per calendar day, keeping the last N days on rotation. Runs on IO
        // dispatcher; never blocks startup and swallows its own errors.
        autoBackupManager.runOnStartup()

        // Seed default eucapnic configurations on first run
        eucapnicConfigRepository.seedDefaultConfigurationsIfNeeded()

        // One-shot (2026-10-22): recompute legacy morning-readiness supine HRV
        // with pipeline v2 (dropped-beat reconstruction). No-op after first run.
        morningReadinessBackfillRunner.runIfNeeded()

        // One-shot (2026-11-21): stamp the true IANA zone (from the Tail-derived
        // travel seed) + frozen local hour on every pre-fix session row. No-op
        // after first run.
        localTimeBackfillRunner.runIfNeeded()
    }

    /**
     * Installs a last-resort uncaught exception logger that writes the crash
     * stack trace to both Logcat AND a file in internal storage.
     *
     * This does NOT suppress the crash — the original default handler is still
     * invoked — but it guarantees the full stack trace is captured even when
     * the device is not connected to Android Studio / ADB at the time.
     *
     * Crash logs are stored in: {filesDir}/crash_logs/crash_*.txt
     * They can be viewed in-app via Settings → Crash Logs.
     *
     * To retrieve via ADB:
     *   adb logcat -d -s WAGS_CRASH:E
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(
                    "WAGS_CRASH",
                    "Uncaught exception on thread '${thread.name}': ${throwable.message}",
                    throwable
                )
            } catch (_: Exception) {
                // If logging itself fails, don't mask the original crash.
            }
            // Write crash to file so it can be reviewed later without ADB
            try {
                CrashLogWriter.writeCrash(this@WagsApplication, thread.name, throwable)
            } catch (_: Exception) {
                // Never let file-writing failure mask the original crash.
            }
            // Delegate to the original handler (which shows the crash dialog / kills the process)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
