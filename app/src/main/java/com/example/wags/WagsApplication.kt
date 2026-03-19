package com.example.wags

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.example.wags.data.garmin.GarminApneaRepository
import com.example.wags.data.garmin.GarminManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WagsApplication : Application() {

    @Inject
    lateinit var garminApneaRepository: GarminApneaRepository

    @Inject
    lateinit var garminManager: GarminManager

    @Inject
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()

        // Start listening for incoming Garmin Free Hold data.
        // This runs in the background and persists data as it arrives from the watch.
        garminApneaRepository.startListening()

        // Always initialize Garmin Connect IQ SDK on app start.
        // This discovers paired devices and starts listening for watch messages.
        // The SDK will find the watch via Garmin Connect Mobile if it's paired.
        Log.i("WagsApp", "Auto-initializing Garmin Connect IQ SDK...")
        garminManager.initialize()

        // Listen for toast messages from GarminManager and show them
        scope.launch {
            garminManager.toastMessages.collect { message ->
                // Toast must be shown on main thread — launch on main
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this@WagsApplication, message, Toast.LENGTH_LONG).show()
                }
            }
        }
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
