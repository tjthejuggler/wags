package com.example.wags.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
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
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.crash.CrashLogWriter
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.ScannedDevice
import com.example.wags.ui.common.AdviceDialog
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.AdviceViewModel
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
    adviceViewModel: AdviceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val adviceState by adviceViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var openAdviceSection by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportData(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirmDialog = true
        }
    }

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

    LaunchedEffect(Unit) {
        viewModel.loadHabits()
    }

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

            // ── Connected device status ─────────────────────────────────────
            item {
                ConnectedDeviceCard(
                    deviceState = state.deviceState,
                    onDisconnect = { viewModel.disconnectDevice() }
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
                                color = TextPrimary
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
                                containerColor = SurfaceVariant,
                                contentColor = TextPrimary
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
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
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
            if (state.scanResults.isEmpty() && !state.isScanning) {
                item {
                    Text(
                        "No devices found. Make sure your sensors are powered on, then tap Scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            // ── Unified scan results ────────────────────────────────────────
            items(state.scanResults, key = { it.identifier }) { device ->
                DeviceResultCard(
                    device = device,
                    deviceState = state.deviceState,
                    onConnect = { viewModel.connectDevice(device) }
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

            // ── Spotify song detection ─────────────────────────────────────
            item {
                SpotifyIntegrationCard(
                    spotifyConnected = state.spotifyConnected,
                    onConnectSpotify = {
                        context.startActivity(viewModel.buildSpotifyLoginIntent())
                    },
                    onDisconnectSpotify = { viewModel.disconnectSpotify() }
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
                    meditationHabit         = state.meditationHabit,
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

            // ── Crash Logs ───────────────────────────────────────────────
            item {
                CrashLogsCard(
                    onViewLogs = { navController.navigate(WagsRoutes.CRASH_LOGS) }
                )
            }

            // ── Advice ──────────────────────────────────────────────────────
            item {
                AdviceSettingsCard(
                    adviceBySection = adviceState.adviceBySection,
                    onOpenSection = { openAdviceSection = it }
                )
            }
        }
    }

    // ── Advice dialog ─────────────────────────────────────────────────────────
    openAdviceSection?.let { section ->
        AdviceDialog(
            section = section,
            adviceList = adviceState.adviceBySection[section] ?: emptyList(),
            onAdd = { text -> adviceViewModel.addAdvice(section, text) },
            onUpdate = { entity, text -> adviceViewModel.updateAdvice(entity, text) },
            onDelete = { id -> adviceViewModel.deleteAdvice(id) },
            onDismiss = { openAdviceSection = null }
        )
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
                    color = TextPrimary
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
                        contentColor = TextPrimary
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
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ── Connected device summary card ─────────────────────────────────────────────

@Composable
private fun ConnectedDeviceCard(
    deviceState: BleConnectionState,
    onDisconnect: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Connected Device", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    val (statusText, statusColor) = when (deviceState) {
                        is BleConnectionState.Connected -> {
                            val typeName = when (deviceState.deviceType) {
                                com.example.wags.domain.model.DeviceType.POLAR_H10 -> "Polar H10"
                                com.example.wags.domain.model.DeviceType.POLAR_VERITY -> "Polar Verity Sense"
                                com.example.wags.domain.model.DeviceType.OXIMETER -> "Pulse Oximeter"
                                com.example.wags.domain.model.DeviceType.GENERIC_BLE -> "BLE Sensor"
                            }
                            "$typeName: ${deviceState.deviceName}" to ReadinessGreen
                        }
                        is BleConnectionState.Connecting ->
                            "Connecting…" to ReadinessOrange
                        is BleConnectionState.Scanning ->
                            "Scanning…" to EcgCyan
                        is BleConnectionState.Error ->
                            "Error: ${deviceState.message}" to ReadinessRed
                        is BleConnectionState.Disconnected ->
                            "Not connected" to TextSecondary
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)

                    // Show capabilities when connected
                    if (deviceState is BleConnectionState.Connected) {
                        val caps = deviceState.deviceType.capabilities.joinToString(", ") { it.name }
                        Text(
                            "Capabilities: $caps",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                if (deviceState is BleConnectionState.Connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) { Text("Disconnect") }
                }
            }
        }
    }
}

// ── Unified device result card ────────────────────────────────────────────────

@Composable
private fun DeviceResultCard(
    device: ScannedDevice,
    deviceState: BleConnectionState,
    onConnect: () -> Unit
) {
    val isConnected = deviceState is BleConnectionState.Connected &&
        deviceState.deviceId == device.identifier
    val isConnecting = deviceState is BleConnectionState.Connecting &&
        deviceState.deviceId == device.identifier

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
                Text(
                    text = device.name.ifBlank { device.identifier },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.identifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
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
    meditationHabit: HabitSlotSelection,
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
                Slot.RESONANCE_BREATHING to resonanceBreathingHabit,
                Slot.MEDITATION          to meditationHabit
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
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
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
        color = if (isSelected) SurfaceVariant else Color.Transparent,
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
                color = if (isSelected) TextPrimary else TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Text("✓", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
        }
    }
}

// ── Crash Logs card ────────────────────────────────────────────────────────────

@Composable
private fun CrashLogsCard(onViewLogs: () -> Unit) {
    val context = LocalContext.current
    val logCount = remember { CrashLogWriter.listLogs(context).size }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🪲 Crash Logs", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    if (logCount == 0) "No crashes recorded"
                    else "$logCount crash log${if (logCount != 1) "s" else ""} saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (logCount > 0) ReadinessRed else TextSecondary
                )
            }
            OutlinedButton(
                onClick = onViewLogs,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("View")
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
                            containerColor = SurfaceVariant,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Export")
                    }
                }
            }

            HorizontalDivider(color = SurfaceVariant)

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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Import")
                    }
                }
            }

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

// ── Spotify integration card ──────────────────────────────────────────────────

@Composable
private fun SpotifyIntegrationCard(
    spotifyConnected: Boolean,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit
) {
    val context = LocalContext.current
    val isGranted = remember {
        android.service.notification.NotificationListenerService::class.java.let {
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Spotify Integration", style = MaterialTheme.typography.titleMedium)
            Text(
                "Connect your Spotify account to load songs directly into playback " +
                    "before a breath hold. Song detection also records what played during holds.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            HorizontalDivider(color = SurfaceVariant)

            // ── Spotify Account ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spotify Account", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (spotifyConnected) "✓ Connected" else "Not connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (spotifyConnected) ReadinessGreen else TextSecondary
                    )
                }
                if (spotifyConnected) {
                    OutlinedButton(
                        onClick = onDisconnectSpotify,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onConnectSpotify,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariant,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Connect Spotify")
                    }
                }
            }

            HorizontalDivider(color = SurfaceVariant)

            // ── Notification Access (song detection) ─────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Notification Access", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (isGranted) "✓ Granted" else "⚠ Required for song detection",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isGranted) ReadinessGreen else ReadinessOrange
                    )
                }
                Button(
                    onClick = {
                        context.startActivity(
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceVariant,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Open Settings")
                }
            }
        }
    }
}

// ── Advice settings card ──────────────────────────────────────────────────────

@Composable
private fun AdviceSettingsCard(
    adviceBySection: Map<String, List<com.example.wags.data.db.entity.AdviceEntity>>,
    onOpenSection: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Advice", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add personal reminders or tips that appear at the top of each section's screen.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            HorizontalDivider(color = SurfaceVariant)

            AdviceSection.all.forEach { section ->
                val count = adviceBySection[section]?.size ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            AdviceSection.label(section),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (count == 0) "No advice set"
                            else "$count piece${if (count != 1) "s" else ""} of advice",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (count > 0) ReadinessGreen else TextSecondary
                        )
                    }
                    OutlinedButton(
                        onClick = { onOpenSection(section) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text(
                            if (count > 0) "Manage" else "Add",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
