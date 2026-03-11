package com.example.wags.ui.settings

import android.Manifest
import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.OximeterConnectionState
import com.example.wags.ui.theme.*
import com.polar.sdk.api.model.PolarDeviceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun allGranted() = blePermissions.all {
        ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permissionDenied = false
            viewModel.startScan()
        } else {
            permissionDenied = true
        }
    }

    fun requestScan() {
        if (allGranted()) viewModel.startScan()
        else permissionLauncher.launch(blePermissions)
    }

    // Stop all scans when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Device Settings", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Permission denied banner ───────────────────────────────────
            if (permissionDenied) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = ReadinessRed.copy(alpha = 0.2f))) {
                        Text(
                            text = "Bluetooth permissions are required. " +
                                "Please grant them in system Settings → Apps → WAGS → Permissions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ReadinessRed,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // ── Connected devices status ───────────────────────────────────
            item {
                ConnectedDevicesCard(
                    h10State = state.h10State,
                    verityState = state.verityState,
                    oximeterState = state.oximeterState,
                    savedH10Id = state.savedH10Id,
                    savedVerityId = state.savedVerityId,
                    onDisconnectH10 = { viewModel.disconnectH10() },
                    onDisconnectVerity = { viewModel.disconnectVerity() },
                    onDisconnectOximeter = { viewModel.disconnectOximeter() }
                )
            }

            // ── Single unified scan button ─────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Nearby Sensors", style = MaterialTheme.typography.titleLarge)
                    if (state.isScanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = EcgCyan,
                                strokeWidth = 2.dp
                            )
                            OutlinedButton(
                                onClick = { viewModel.stopScan() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Text("Stop")
                            }
                        }
                    } else {
                        Button(onClick = { requestScan() }) {
                            Text("Scan")
                        }
                    }
                }
            }

            // ── Empty state ────────────────────────────────────────────────
            val totalResults = state.polarScanResults.size + state.oximeterScanResults.size
            if (totalResults == 0 && !state.isScanning) {
                item {
                    Text(
                        "No devices found. Make sure your sensors are powered on, then tap Scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            // ── Polar scan results ─────────────────────────────────────────
            if (state.polarScanResults.isNotEmpty()) {
                item {
                    Text(
                        "Polar Devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                }
            }

            items(state.polarScanResults, key = { "polar-${it.deviceId}" }) { device ->
                PolarDeviceResultCard(
                    device = device,
                    h10State = state.h10State,
                    verityState = state.verityState,
                    onConnectH10 = {
                        viewModel.stopScan()
                        viewModel.connectH10(device)
                    },
                    onConnectVerity = {
                        viewModel.stopScan()
                        viewModel.connectVerity(device)
                    }
                )
            }

            // ── Oximeter scan results ──────────────────────────────────────
            if (state.oximeterScanResults.isNotEmpty()) {
                item {
                    Text(
                        "Oximeter / SpO₂ Devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                }
            }

            items(state.oximeterScanResults, key = { "oxy-${it.device.address}" }) { result ->
                OximeterDeviceResultCard(
                    result = result,
                    oximeterState = state.oximeterState,
                    onConnect = { viewModel.connectOximeter(result.device.address) }
                )
            }
        }
    }
}

// ── Connected devices summary card ────────────────────────────────────────────

@Composable
private fun ConnectedDevicesCard(
    h10State: BleConnectionState,
    verityState: BleConnectionState,
    oximeterState: OximeterConnectionState,
    savedH10Id: String,
    savedVerityId: String,
    onDisconnectH10: () -> Unit,
    onDisconnectVerity: () -> Unit,
    onDisconnectOximeter: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Connected Devices", style = MaterialTheme.typography.titleMedium)
            DeviceStatusRow(
                label = "Polar H10",
                deviceId = savedH10Id,
                state = h10State,
                onDisconnect = onDisconnectH10
            )
            DeviceStatusRow(
                label = "Polar Verity Sense",
                deviceId = savedVerityId,
                state = verityState,
                onDisconnect = onDisconnectVerity
            )

            // Oximeter row
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Oximeter (SpO₂)", style = MaterialTheme.typography.bodyLarge)
                    val (statusText, statusColor) = when (oximeterState) {
                        is OximeterConnectionState.Connected ->
                            "Connected: ${oximeterState.deviceName}" to ReadinessGreen
                        is OximeterConnectionState.Connecting ->
                            "Connecting…" to ReadinessOrange
                        is OximeterConnectionState.Scanning ->
                            "Scanning…" to EcgCyan
                        is OximeterConnectionState.Error ->
                            "Error: ${oximeterState.message}" to ReadinessRed
                        is OximeterConnectionState.Disconnected ->
                            "Not connected" to TextSecondary
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                if (oximeterState is OximeterConnectionState.Connected) {
                    OutlinedButton(
                        onClick = onDisconnectOximeter,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusRow(
    label: String,
    deviceId: String,
    state: BleConnectionState,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            val statusText = when (state) {
                is BleConnectionState.Connected -> "Connected: ${state.deviceName}"
                is BleConnectionState.Connecting -> "Connecting…"
                is BleConnectionState.Disconnected ->
                    if (deviceId.isNotBlank()) "Last: $deviceId" else "Not connected"
                is BleConnectionState.Error -> "Error: ${state.message}"
            }
            val statusColor = when (state) {
                is BleConnectionState.Connected -> ReadinessGreen
                is BleConnectionState.Connecting -> ReadinessOrange
                is BleConnectionState.Disconnected -> TextSecondary
                is BleConnectionState.Error -> ReadinessRed
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
        if (state is BleConnectionState.Connected) {
            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Disconnect") }
        }
    }
}

// ── Polar device result card ──────────────────────────────────────────────────

@Composable
private fun PolarDeviceResultCard(
    device: PolarDeviceInfo,
    h10State: BleConnectionState,
    verityState: BleConnectionState,
    onConnectH10: () -> Unit,
    onConnectVerity: () -> Unit
) {
    val h10Connected = h10State is BleConnectionState.Connected &&
        (h10State as BleConnectionState.Connected).deviceId == device.deviceId
    val verityConnected = verityState is BleConnectionState.Connected &&
        (verityState as BleConnectionState.Connected).deviceId == device.deviceId
    val alreadyConnected = h10Connected || verityConnected

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (alreadyConnected) SurfaceVariant else SurfaceDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = device.name.ifBlank { "Unknown Polar Device" },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (alreadyConnected) {
                    Text("Connected", style = MaterialTheme.typography.bodySmall, color = ReadinessGreen)
                }
            }
            if (!alreadyConnected) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Button(
                        onClick = onConnectH10,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("H10", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(
                        onClick = onConnectVerity,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Verity", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

// ── Oximeter device result card ───────────────────────────────────────────────

@Composable
private fun OximeterDeviceResultCard(
    result: ScanResult,
    oximeterState: OximeterConnectionState,
    onConnect: () -> Unit
) {
    val address = result.device.address
    val name = result.device.name ?: address
    val isConnected = oximeterState is OximeterConnectionState.Connected &&
        oximeterState.deviceAddress == address
    val isConnecting = oximeterState is OximeterConnectionState.Connecting &&
        oximeterState.deviceAddress == address

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) SurfaceVariant else SurfaceDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(address, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                when {
                    isConnected -> Text(
                        "Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessGreen
                    )
                    isConnecting -> Text(
                        "Connecting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessOrange
                    )
                }
            }
            when {
                isConnected -> { /* no button — disconnect from the top card */ }
                isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = EcgCyan,
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("Connect", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
