package com.example.wags

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WagsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    /**
     * Installs a last-resort uncaught exception logger that writes the crash
     * stack trace to Logcat before the default handler kills the process.
     *
     * This does NOT suppress the crash — the original default handler is still
     * invoked — but it guarantees the full stack trace is captured in Logcat
     * even when the device is not connected to Android Studio at the time.
     *
     * To retrieve the crash log after the fact:
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
            // Delegate to the original handler (which shows the crash dialog / kills the process)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
