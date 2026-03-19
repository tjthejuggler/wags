package com.example.wags.ui.garmin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.data.garmin.GarminManager
import com.example.wags.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class GarminViewModel @Inject constructor(
    private val garminManager: GarminManager
) : ViewModel() {

    val connectionState: StateFlow<GarminConnectionState> = garminManager.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GarminConnectionState.Uninitialized
        )

    val hasPairedDevice: Boolean get() = garminManager.hasPairedDevice()
    val pairedDeviceName: String get() = garminManager.getPairedDeviceName()

    fun initializeGarmin() {
        val current = garminManager.connectionState.value
        if (current is GarminConnectionState.WagsAppNotFound ||
            current is GarminConnectionState.Error
        ) {
            garminManager.shutdown()
        }
        garminManager.initialize()
    }

    fun shutdownGarmin() {
        garminManager.shutdown()
    }

    fun forgetDevice() {
        garminManager.shutdown()
        garminManager.clearPairedDevice()
    }

    fun requestSync() {
        garminManager.requestSync()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminScreen(
    navController: NavController,
    viewModel: GarminViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Garmin Watch", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Connection Status Card ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Connection Status",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val (statusText, statusColor) = when (val state = connectionState) {
                        is GarminConnectionState.Uninitialized -> {
                            if (viewModel.hasPairedDevice) {
                                "Saved: ${viewModel.pairedDeviceName}\nNot yet connected" to Color.Gray
                            } else {
                                "Not Connected" to Color.Gray
                            }
                        }
                        is GarminConnectionState.Initializing ->
                            "Initializing SDK..." to ReadinessOrange
                        is GarminConnectionState.SdkReady ->
                            "SDK Ready — Searching..." to ReadinessOrange
                        is GarminConnectionState.DeviceFound ->
                            "Found: ${state.deviceName}\nWaiting for connection..." to ReadinessOrange
                        is GarminConnectionState.WagsAppNotFound ->
                            "Connected to ${state.deviceName}\nbut WAGS app not found" to ButtonDanger
                        is GarminConnectionState.Connected ->
                            "Connected: ${state.deviceName}" to ButtonSuccess
                        is GarminConnectionState.Error ->
                            state.message to ButtonDanger
                    }

                    Text(
                        text = "●",
                        style = MaterialTheme.typography.headlineLarge,
                        color = statusColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Action Buttons ────────────────────────────────────────────────
            when (connectionState) {
                is GarminConnectionState.Uninitialized,
                is GarminConnectionState.Error -> {
                    Button(
                        onClick = { viewModel.initializeGarmin() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EcgCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(
                            text = if (viewModel.hasPairedDevice) "Reconnect" else "Connect to Garmin Watch",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (viewModel.hasPairedDevice) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.forgetDevice() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Forget Device", color = ButtonDanger)
                        }
                    }
                }

                is GarminConnectionState.WagsAppNotFound -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "⚠ WAGS App Not Found",
                                style = MaterialTheme.typography.titleSmall,
                                color = ButtonDanger,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Your watch is connected but the WAGS Connect IQ app " +
                                        "was not detected. Make sure you've copied wags.prg " +
                                        "to GARMIN/APPS/ on the watch and rebooted it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.initializeGarmin() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EcgCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Retry", fontWeight = FontWeight.Bold)
                    }
                }

                is GarminConnectionState.Initializing,
                is GarminConnectionState.SdkReady,
                is GarminConnectionState.DeviceFound -> {
                    CircularProgressIndicator(
                        color = EcgCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when (connectionState) {
                            is GarminConnectionState.DeviceFound ->
                                "Found ${(connectionState as GarminConnectionState.DeviceFound).deviceName}..."
                            else -> "Searching for Garmin devices..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.shutdownGarmin() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = Color.LightGray)
                    }
                }

                is GarminConnectionState.Connected -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "✓ Watch Connected",
                                style = MaterialTheme.typography.titleSmall,
                                color = ButtonSuccess,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Free Hold sessions recorded on the watch will " +
                                        "automatically sync to this phone. You'll see a " +
                                        "toast notification when holds are transferred.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.requestSync() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EcgCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Sync Now", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { viewModel.forgetDevice() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Forget Device", color = ButtonDanger)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Instructions ────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How It Works",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val steps = listOf(
                        "1. Connect your watch once using the button above",
                        "2. The app will auto-connect on future launches",
                        "3. Do Free Holds on your watch — they're saved locally",
                        "4. When the phone connects, holds sync automatically",
                        "5. You'll see a toast when each hold transfers",
                        "6. Garmin holds appear in your history with a ⌚ icon"
                    )
                    for (step in steps) {
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
