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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
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
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.OximeterConnectionState
import com.example.wags.ui.theme.*
import com.polar.sdk.api.model.PolarDeviceInfo

// Which default slot is being assigned from the scan list
private enum class DefaultSlot { NONE, MORNING, DAY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    // Which slot the user is currently assigning (NONE = normal connect mode)
    var assigningSlot by remember { mutableStateOf(DefaultSlot.NONE) }

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
                    assigningSlot = assigningSlot,
                    onConnectH10 = {
                        viewModel.stopScan()
                        viewModel.connectH10(device)
                    },
                    onConnectVerity = {
                        viewModel.stopScan()
                        viewModel.connectVerity(device)
                    },
                    onSetAsH10Default = {
                        when (assigningSlot) {
                            DefaultSlot.MORNING -> viewModel.setMorningDefaultPolar(device, isH10 = true)
                            DefaultSlot.DAY     -> viewModel.setDayDefaultPolar(device, isH10 = true)
                            DefaultSlot.NONE    -> {}
                        }
                        assigningSlot = DefaultSlot.NONE
                        viewModel.stopScan()
                    },
                    onSetAsVerityDefault = {
                        when (assigningSlot) {
                            DefaultSlot.MORNING -> viewModel.setMorningDefaultPolar(device, isH10 = false)
                            DefaultSlot.DAY     -> viewModel.setDayDefaultPolar(device, isH10 = false)
                            DefaultSlot.NONE    -> {}
                        }
                        assigningSlot = DefaultSlot.NONE
                        viewModel.stopScan()
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
                    assigningSlot = assigningSlot,
                    onConnect = { viewModel.connectOximeter(result.device.address) },
                    onSetAsDefault = {
                        val name = result.device.name ?: result.device.address
                        when (assigningSlot) {
                            DefaultSlot.MORNING -> viewModel.setMorningDefaultOximeter(result.device.address, name)
                            DefaultSlot.DAY     -> viewModel.setDayDefaultOximeter(result.device.address, name)
                            DefaultSlot.NONE    -> {}
                        }
                        assigningSlot = DefaultSlot.NONE
                        viewModel.stopScan()
                    }
                )
            }

            // ── Assigning-slot banner ──────────────────────────────────────
            if (assigningSlot != DefaultSlot.NONE) {
                item {
                    val slotLabel = if (assigningSlot == DefaultSlot.MORNING) "Morning (3am–11am)" else "Day (11am–3am)"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = EcgCyan.copy(alpha = 0.15f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Tap a device below to set as $slotLabel default",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EcgCyan,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { assigningSlot = DefaultSlot.NONE }) {
                                Text("Cancel", color = TextSecondary)
                            }
                        }
                    }
                }
            }

            // ── Time-of-day defaults ───────────────────────────────────────
            item {
                TimeOfDayDefaultsCard(
                    morningDeviceName = state.morningDeviceName,
                    morningDeviceType = state.morningDeviceType,
                    dayDeviceName = state.dayDeviceName,
                    dayDeviceType = state.dayDeviceType,
                    onAssignMorning = {
                        assigningSlot = DefaultSlot.MORNING
                        requestScan()
                    },
                    onClearMorning = { viewModel.clearMorningDefault() },
                    onAssignDay = {
                        assigningSlot = DefaultSlot.DAY
                        requestScan()
                    },
                    onClearDay = { viewModel.clearDayDefault() }
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
        }
    }
}

// ── Time-of-day defaults card ─────────────────────────────────────────────────

@Composable
private fun TimeOfDayDefaultsCard(
    morningDeviceName: String,
    morningDeviceType: String,
    dayDeviceName: String,
    dayDeviceType: String,
    onAssignMorning: () -> Unit,
    onClearMorning: () -> Unit,
    onAssignDay: () -> Unit,
    onClearDay: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Auto-Connect Defaults", style = MaterialTheme.typography.titleMedium)
            Text(
                "The app will automatically connect to the appropriate device when it opens.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            HorizontalDivider(color = SurfaceVariant)

            // Morning slot
            DefaultSlotRow(
                icon = { Icon(Icons.Default.Star, contentDescription = null, tint = EcgCyan, modifier = Modifier.size(18.dp)) },
                label = "Morning  (3 am – 11 am)",
                deviceName = morningDeviceName,
                deviceType = morningDeviceType,
                onAssign = onAssignMorning,
                onClear = onClearMorning
            )

            HorizontalDivider(color = SurfaceVariant)

            // Day slot
            DefaultSlotRow(
                icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = ReadinessOrange, modifier = Modifier.size(18.dp)) },
                label = "Day  (11 am – 3 am)",
                deviceName = dayDeviceName,
                deviceType = dayDeviceType,
                onAssign = onAssignDay,
                onClear = onClearDay
            )
        }
    }
}

@Composable
private fun DefaultSlotRow(
    icon: @Composable () -> Unit,
    label: String,
    deviceName: String,
    deviceType: String,
    onAssign: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            icon()
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (deviceName.isNotBlank()) {
                    val typeLabel = when (deviceType) {
                        "h10"      -> "H10"
                        "verity"   -> "Verity Sense"
                        "oximeter" -> "Oximeter"
                        else       -> deviceType
                    }
                    Text(
                        "$typeLabel · $deviceName",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessGreen
                    )
                } else {
                    Text("Not set", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (deviceName.isNotBlank()) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear default",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = onAssign,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(
                    if (deviceName.isNotBlank()) "Change" else "Set",
                    style = MaterialTheme.typography.bodySmall
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
    assigningSlot: DefaultSlot,
    onConnectH10: () -> Unit,
    onConnectVerity: () -> Unit,
    onSetAsH10Default: () -> Unit,
    onSetAsVerityDefault: () -> Unit
) {
    val h10Connected = h10State is BleConnectionState.Connected &&
        (h10State as BleConnectionState.Connected).deviceId == device.deviceId
    val verityConnected = verityState is BleConnectionState.Connected &&
        (verityState as BleConnectionState.Connected).deviceId == device.deviceId
    val alreadyConnected = h10Connected || verityConnected

    val isAssigning = assigningSlot != DefaultSlot.NONE

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
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (isAssigning) {
                    // In "assign default" mode — show Set as H10 / Set as Verity
                    Button(
                        onClick = onSetAsH10Default,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("Set H10", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(
                        onClick = onSetAsVerityDefault,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Set Verity", style = MaterialTheme.typography.bodySmall) }
                } else if (!alreadyConnected) {
                    // Normal connect mode
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
    assigningSlot: DefaultSlot,
    onConnect: () -> Unit,
    onSetAsDefault: () -> Unit
) {
    val address = result.device.address
    val name = result.device.name ?: address
    val isConnected = oximeterState is OximeterConnectionState.Connected &&
        oximeterState.deviceAddress == address
    val isConnecting = oximeterState is OximeterConnectionState.Connecting &&
        oximeterState.deviceAddress == address

    val isAssigning = assigningSlot != DefaultSlot.NONE

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
                isAssigning -> {
                    Button(
                        onClick = onSetAsDefault,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("Set Default", style = MaterialTheme.typography.bodySmall) }
                }
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

// ── Tail App integration card ─────────────────────────────────────────────────

/**
 * Card that lets the user map a Tail/Habit-app habit to each WAGS activity.
 *
 * Layout
 * ──────
 *  Header  : title + Refresh button
 *  Status  : loading spinner | "app unavailable" warning | "no habits" hint
 *  Slots   : one [HabitSlotRow] per activity, each with its own inline picker
 */
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
    // Which slot's picker is currently expanded (null = all collapsed)
    var expandedSlot by remember { mutableStateOf<Slot?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────
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

            // ── Status banner ─────────────────────────────────────────────
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

            // ── Per-activity slots ────────────────────────────────────────
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
                    slot        = slot,
                    selection   = selection,
                    habitList   = habitList,
                    isExpanded  = expandedSlot == slot,
                    onToggle    = {
                        expandedSlot = if (expandedSlot == slot) null else slot
                    },
                    onSelect    = { entry ->
                        onSelectHabit(slot, entry)
                        expandedSlot = null
                    },
                    onClear     = { onClearHabit(slot) }
                )
                if (index < slots.lastIndex) {
                    HorizontalDivider(color = SurfaceVariant)
                }
            }
        }
    }
}

/**
 * A single row for one activity slot. Shows the activity label, the currently
 * selected habit name (or "Not set"), and a toggle button to expand/collapse
 * the inline habit picker.
 */
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
        // ── Summary row ───────────────────────────────────────────────────
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

        // ── Inline picker (expanded) ──────────────────────────────────────
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
