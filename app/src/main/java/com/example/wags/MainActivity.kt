package com.example.wags

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.example.wags.data.ble.AutoConnectManager
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.ui.common.pip.PIP_ACTION_BROADCAST
import com.example.wags.ui.common.pip.PipActionReceiver
import com.example.wags.ui.common.pip.PipController
import com.example.wags.ui.navigation.WagsNavGraph
import com.example.wags.ui.theme.WagsTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var autoConnectManager: AutoConnectManager

    @Inject
    lateinit var spotifyAuthManager: SpotifyAuthManager

    /** True while an apnea hold is active; set by ApneaTableScreen. */
    var isApneaHoldActive: Boolean = false

    /** Invoked when a volume button is pressed during an active hold. */
    var onContractionLogged: (() -> Unit)? = null

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Job for the 10-minute background shutdown timer. */
    private var backgroundShutdownJob: Job? = null

    /** Receives taps on PiP overlay buttons and forwards them to PipController. */
    private val pipActionReceiver = PipActionReceiver()

    private val blePermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            autoConnectManager.start()
        }
        // If denied, user can still connect manually from Settings
    }

    /**
     * Observes the *process-level* lifecycle (foreground / background).
     * When the app has been in the background for 10 minutes continuously,
     * the process is shut down to free resources.
     */
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // App moved to background — start the 10-minute countdown
            Log.d(TAG, "App moved to background — starting 10-min shutdown timer")
            backgroundShutdownJob = activityScope.launch {
                delay(BACKGROUND_TIMEOUT_MS)
                Log.i(TAG, "App in background for 10 min — shutting down")
                finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            // App returned to foreground — cancel the timer
            backgroundShutdownJob?.let {
                Log.d(TAG, "App returned to foreground — cancelling shutdown timer")
                it.cancel()
            }
            backgroundShutdownJob = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register the background-timeout observer on the process lifecycle
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        setContent {
            WagsTheme {
                val navController = rememberNavController()
                WagsNavGraph(navController = navController)
            }
        }

        // Start the persistent auto-connect loop.
        // If permissions are already granted this is instant; otherwise we request them.
        triggerAutoConnect()

        // Handle Spotify OAuth redirect if the activity was launched with one
        handleSpotifyRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                pipActionReceiver,
                IntentFilter(PIP_ACTION_BROADCAST),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, IntentFilter(PIP_ACTION_BROADCAST))
        }
    }

    override fun onPause() {
        super.onPause()
        // Do NOT unregister when entering PiP — onPause fires when PiP starts,
        // but we still need the receiver to handle OS overlay button taps.
        if (!isInPictureInPictureMode) {
            try { unregisterReceiver(pipActionReceiver) } catch (_: IllegalArgumentException) { }
        }
    }

    override fun onStop() {
        super.onStop()
        // Unregister when the activity is fully stopped (not just PiP).
        if (isInPictureInPictureMode) {
            try { unregisterReceiver(pipActionReceiver) } catch (_: IllegalArgumentException) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipController.requestEnterPip(this)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.notifyPipModeChanged(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        backgroundShutdownJob?.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "wags" && uri.host == "spotify-callback") {
            activityScope.launch {
                spotifyAuthManager.handleRedirect(uri)
            }
        }
    }

    private fun triggerAutoConnect() {
        val allGranted = blePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            autoConnectManager.start()
        } else {
            permissionLauncher.launch(blePermissions)
        }
    }

    /**
     * Call this from any ViewModel / screen to pause the background auto-connect
     * loop while a session is running, and resume it when the session ends.
     *
     * Example (in a ViewModel):
     *   (context as? MainActivity)?.setSessionActive(true)   // session start
     *   (context as? MainActivity)?.setSessionActive(false)  // session end
     */
    fun setSessionActive(active: Boolean) {
        autoConnectManager.setSessionActive(active)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isApneaHoldActive &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            onContractionLogged?.invoke()
            return true // consume event — prevent volume change
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "BackgroundTimeout"
        /** 10 minutes in milliseconds. */
        private const val BACKGROUND_TIMEOUT_MS = 10L * 60 * 1_000
    }
}
