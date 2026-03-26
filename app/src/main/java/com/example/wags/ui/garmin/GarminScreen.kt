package com.example.wags.ui.garmin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    val syncLog: StateFlow<List<String>> = garminManager.syncLog
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
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

    fun clearSyncLog() {
        garminManager.clearSyncLog()
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
    val syncLog by viewModel.syncLog.collectAsStateWithLifecycle()
    var showSyncLog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Garmin Watch", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Connection Status Card ──────────────────────────────────────
            item {
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
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val (statusText, statusColor) = when (val state = connectionState) {
                            is GarminConnectionState.Uninitialized -> {
                                if (viewModel.hasPairedDevice) {
                                        "Saved: ${viewModel.pairedDeviceName}\nNot yet connected" to TextSecondary
                                    } else {
                                        "Not Connected" to TextSecondary
                                    }
                            }
                            is GarminConnectionState.Initializing ->
                                "Initializing SDK..." to TextSecondary
                            is GarminConnectionState.SdkReady ->
                                "SDK Ready — Searching..." to TextSecondary
                            is GarminConnectionState.DeviceFound ->
                                "Found: ${state.deviceName}\nWaiting for connection..." to TextSecondary
                            is GarminConnectionState.WagsAppNotFound ->
                                "Connected to ${state.deviceName}\nbut WAGS app not found" to TextDisabled
                            is GarminConnectionState.Connected ->
                                "Connected: ${state.deviceName}" to TextPrimary
                            is GarminConnectionState.Error ->
                                state.message to TextDisabled
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
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ── Action Buttons ────────────────────────────────────────────────
            item {
                when (connectionState) {
                    is GarminConnectionState.Uninitialized,
                    is GarminConnectionState.Error -> {
                        Column {
                            Button(
                                onClick = { viewModel.initializeGarmin() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceVariant,
                                    contentColor = TextPrimary
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
                                    Text("Forget Device", color = TextDisabled)
                                }
                            }
                        }
                    }

                    is GarminConnectionState.WagsAppNotFound -> {
                        Column {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "⚠ WAGS App Not Found",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Your watch is connected but the WAGS Connect IQ app " +
                                                "was not detected. Make sure you've copied wags.prg " +
                                                "to GARMIN/APPS/ on the watch and rebooted it.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.initializeGarmin() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceVariant,
                                    contentColor = TextPrimary
                                )
                            ) {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is GarminConnectionState.Initializing,
                    is GarminConnectionState.SdkReady,
                    is GarminConnectionState.DeviceFound -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = TextSecondary,
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
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.shutdownGarmin() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel", color = TextSecondary)
                            }
                        }
                    }

                    is GarminConnectionState.Connected -> {
                        Column {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "✓ Watch Connected",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Free Hold sessions recorded on the watch will " +
                                                "automatically sync to this phone. You'll see a " +
                                                "toast notification when holds are transferred.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.requestSync() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceVariant,
                                    contentColor = TextPrimary
                                )
                            ) {
                                Text("Sync Now", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = { viewModel.forgetDevice() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Forget Device", color = TextDisabled)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ── Sync Log Section ─────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Garmin Sync Log",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (syncLog.isNotEmpty()) {
                                    TextButton(onClick = { viewModel.clearSyncLog() }) {
                                        Text("Clear", style = MaterialTheme.typography.bodySmall, color = TextDisabled)
                                    }
                                }
                                TextButton(onClick = { showSyncLog = !showSyncLog }) {
                                    Text(
                                        if (showSyncLog) "Hide" else "Show (${syncLog.size})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        if (showSyncLog) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = SurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))

                            if (syncLog.isEmpty()) {
                                Text(
                                    text = "No sync events yet. Connect to watch and run a hold.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // ── Sync Log Entries (shown when expanded) ──────────────────────
            if (showSyncLog && syncLog.isNotEmpty()) {
                items(syncLog.size) { index ->
                    val entry = syncLog[index]
                    val entryColor = when {
                        entry.contains("OK") || entry.contains("SYNCED") || entry.contains("connected") ->
                            TextPrimary
                        entry.contains("FAIL") || entry.contains("ERR") || entry.contains("DECODE") ->
                            TextDisabled
                        entry.contains("SEND") || entry.contains("RX") || entry.contains("SYNC_REQUEST") ->
                            TextSecondary
                        else -> TextSecondary
                    }
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        color = entryColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ── Instructions ────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How It Works",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
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
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
