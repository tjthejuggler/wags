package com.example.wags.ui.settings

import android.Manifest
import android.bluetooth.le.ScanResult
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.OximeterConnectionState
import com.example.wags.ui.navigation.WagsRoutes
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
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // SAF file picker for export (create a new ZIP file)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportData(uri)
        }
    }

    // SAF file picker for import (open an existing ZIP file)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirmDialog = true
        }
    }

    // SAF directory picker for meditation audio folder
    val meditationDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setMeditationAudioDir(uri.toString())
        }
    }

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

    // Load habits once when the screen first appears
    LaunchedEffect(Unit) {
        viewModel.loadHabits()
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

            // ── Garmin Watch ─────────────────────────────────────────────────
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⌚ Garmin Watch",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            val (garminStatusText, garminStatusColor) = when (val gs = state.garminState) {
                                is GarminConnectionState.Connected ->
                                    "Connected: ${gs.deviceName}" to ReadinessGreen
                                is GarminConnectionState.Initializing,
                                is GarminConnectionState.SdkReady ->
                                    "Connecting…" to ReadinessOrange
                                is GarminConnectionState.DeviceFound ->
                                    "Found: ${gs.deviceName}…" to ReadinessOrange
                                is GarminConnectionState.WagsAppNotFound ->
                                    "WAGS app not found on ${gs.deviceName}" to ButtonDanger
                                is GarminConnectionState.Error ->
                                    "Error" to ButtonDanger
                                is GarminConnectionState.Uninitialized ->
                                    "Not connected" to TextSecondary
                            }
                            Text(
                                text = garminStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = garminStatusColor
                            )
                        }
                        Button(
                            onClick = { navController.navigate(WagsRoutes.GARMIN) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EcgCyan,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(
                                if (state.garminState is GarminConnectionState.Connected) "Manage"
                                else "Setup"
                            )
                        }
                    }
                }
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

            // ── Meditation audio directory ─────────────────────────────────
            item {
                MeditationAudioDirectoryCard(
                    dirUri = state.meditationAudioDirUri,
                    onChooseDirectory = { meditationDirLauncher.launch(null) },
                    onClearDirectory = { viewModel.clearMeditationAudioDir() }
                )
            }

            // ── Tail App integration ───────────────────────────────────────
            item {
                TailAppIntegrationCard(
                    habitList               = state.habitList,
                    isLoading               = state.isLoadingHabits,
                    habitAppUnavailable     = state.habitAppUnavailable,
                    freeHoldHabit           = state.freeHoldHabit,
                    apneaNewRecordHabit     = state.apneaNewRecordHabit,
                    tableTrainingHabit      = state.tableTrainingHabit,
                    morningReadinessHabit   = state.morningReadinessHabit,
                    hrvReadinessHabit       = state.hrvReadinessHabit,
                    resonanceBreathingHabit = state.resonanceBreathingHabit,
                    onSelectHabit           = { slot, entry -> viewModel.selectHabit(slot, entry) },
                    onClearHabit            = { slot -> viewModel.clearHabit(slot) },
                    onRefresh               = { viewModel.loadHabits() }
                )
            }

            // ── Data Export / Import ──────────────────────────────────────
            item {
                DataExportImportCard(
                    isExporting = state.isExporting,
                    isImporting = state.isImporting,
                    message = state.exportImportMessage,
                    error = state.exportImportError,
                    onExport = {
                        exportLauncher.launch(viewModel.getExportFileName())
                    },
                    onImport = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onDismissMessage = { viewModel.clearExportImportMessage() }
                )
            }
        }
    }

    // ── Import confirmation dialog ───────────────────────────────────────────
    if (showImportConfirmDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportUri = null
            },
            containerColor = SurfaceDark,
            title = {
                Text(
                    "Restore Backup?",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            },
            text = {
                Text(
                    "This will replace ALL existing data with the backup contents. " +
                        "All current readings, sessions, records, and settings will be overwritten.\n\n" +
                        "The app will need to be restarted after import.\n\n" +
                        "Are you sure you want to continue?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingImportUri?.let { viewModel.importData(it) }
                        showImportConfirmDialog = false
                        pendingImportUri = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonDanger,
                        contentColor = Color.White
                    )
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportUri = null
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Cancel")
                }
            }
        )
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
            Column(modifier = Modifier.weight(1f)) {
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
            Column(modifier = Modifier.weight(1f)) {
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

// ── Meditation audio directory card ──────────────────────────────────────────

@Composable
private fun MeditationAudioDirectoryCard(
    dirUri: String,
    onChooseDirectory: () -> Unit,
    onClearDirectory: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Meditation Audio Directory", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose the folder that contains your meditation / NSDR audio files. " +
                    "The app will scan this folder and list all audio files in the Meditation screen.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            HorizontalDivider(color = SurfaceVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (dirUri.isNotBlank()) {
                        val displayPath = try {
                            Uri.parse(dirUri).lastPathSegment ?: dirUri
                        } catch (_: Exception) { dirUri }
                        Text(
                            displayPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadinessGreen
                        )
                    } else {
                        Text(
                            "No folder selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (dirUri.isNotBlank()) {
                        IconButton(onClick = onClearDirectory, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear directory",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onChooseDirectory,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(
                            if (dirUri.isNotBlank()) "Change" else "Choose Folder",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// ── Tail App integration card ─────────────────────────────────────────────────

@Composable
private fun TailAppIntegrationCard(
    habitList: List<HabitEntry>,
    isLoading: Boolean,
    habitAppUnavailable: Boolean,
    freeHoldHabit: HabitSlotSelection,
    apneaNewRecordHabit: HabitSlotSelection,
    tableTrainingHabit: HabitSlotSelection,
    morningReadinessHabit: HabitSlotSelection,
    hrvReadinessHabit: HabitSlotSelection,
    resonanceBreathingHabit: HabitSlotSelection,
    onSelectHabit: (Slot, HabitEntry) -> Unit,
    onClearHabit: (Slot) -> Unit,
    onRefresh: () -> Unit
) {
    var expandedSlot by remember { mutableStateOf<Slot?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tail App Integration", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose which habit to increment for each activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = EcgCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    TextButton(onClick = onRefresh) {
                        Text("Refresh", style = MaterialTheme.typography.bodySmall, color = EcgCyan)
                    }
                }
            }

            when {
                habitAppUnavailable ->
                    Text(
                        "Tail app not found. Make sure it is installed and tap Refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessOrange
                    )
                !isLoading && habitList.isEmpty() ->
                    Text(
                        "No habits found. Tap Refresh to load from the Tail app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
            }

            HorizontalDivider(color = SurfaceVariant)

            val slots = listOf(
                Slot.FREE_HOLD           to freeHoldHabit,
                Slot.APNEA_NEW_RECORD    to apneaNewRecordHabit,
                Slot.TABLE_TRAINING      to tableTrainingHabit,
                Slot.MORNING_READINESS   to morningReadinessHabit,
                Slot.HRV_READINESS       to hrvReadinessHabit,
                Slot.RESONANCE_BREATHING to resonanceBreathingHabit
            )

            slots.forEachIndexed { index, (slot, selection) ->
                HabitSlotRow(
                    slot       = slot,
                    selection  = selection,
                    habitList  = habitList,
                    isExpanded = expandedSlot == slot,
                    onToggle   = { expandedSlot = if (expandedSlot == slot) null else slot },
                    onSelect   = { entry ->
                        onSelectHabit(slot, entry)
                        expandedSlot = null
                    },
                    onClear    = { onClearHabit(slot) }
                )
                if (index < slots.lastIndex) {
                    HorizontalDivider(color = SurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HabitSlotRow(
    slot: Slot,
    selection: HabitSlotSelection,
    habitList: List<HabitEntry>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (HabitEntry) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.bodyMedium)
                if (selection.isSet) {
                    Text(
                        selection.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessGreen
                    )
                } else {
                    Text(
                        "Not set",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selection.isSet) {
                    IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = onToggle,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(
                        if (selection.isSet) "Change" else "Set",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (isExpanded) {
            Spacer(Modifier.height(8.dp))
            if (habitList.isEmpty()) {
                Text(
                    "No habits available. Tap Refresh above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            } else {
                habitList.forEach { entry ->
                    HabitPickerRow(
                        entry      = entry,
                        isSelected = entry.habitId == selection.habitId,
                        onClick    = { onSelect(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitPickerRow(
    entry: HabitEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) EcgCyan.copy(alpha = 0.12f) else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = entry.habitName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) EcgCyan else Color.White,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Text("✓", style = MaterialTheme.typography.bodyMedium, color = EcgCyan)
            }
        }
    }
}

// ── Data Export / Import card ──────────────────────────────────────────────────

@Composable
private fun DataExportImportCard(
    isExporting: Boolean,
    isImporting: Boolean,
    message: String?,
    error: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Data Backup & Restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Export all your data (readings, sessions, records, settings, device history) " +
                    "to a backup file. Import a backup to restore everything.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            HorizontalDivider(color = SurfaceVariant)

            // ── Export button ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Export Data", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Save all data to a ZIP file",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = EcgCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    Button(
                        onClick = onExport,
                        enabled = !isImporting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EcgCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Export")
                    }
                }
            }

            HorizontalDivider(color = SurfaceVariant)

            // ── Import button ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Import Data", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Restore from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = EcgCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    OutlinedButton(
                        onClick = onImport,
                        enabled = !isExporting,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Import")
                    }
                }
            }

            // ── Result / error messages ──────────────────────────────────────
            if (message != null) {
                HorizontalDivider(color = SurfaceVariant)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = ReadinessGreen.copy(alpha = 0.15f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadinessGreen
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onDismissMessage,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Dismiss", color = ReadinessGreen)
                        }
                    }
                }
            }

            if (error != null) {
                HorizontalDivider(color = SurfaceVariant)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = ReadinessRed.copy(alpha = 0.15f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadinessRed
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onDismissMessage,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Dismiss", color = ReadinessRed)
                        }
                    }
                }
            }
        }
    }
}
