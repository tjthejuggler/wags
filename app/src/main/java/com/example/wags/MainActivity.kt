package com.example.wags

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.wags.data.ble.AutoConnectManager
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.ui.navigation.WagsNavGraph
import com.example.wags.ui.theme.WagsTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
