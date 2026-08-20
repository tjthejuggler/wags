package com.example.wags.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.ui.common.AdviceDialog
import com.example.wags.ui.common.AdviceViewModel
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.common.LockPortrait
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

/**
 * Settings screen — organized into collapsible category cards (same pattern
 * as the apnea drill cards): Sensors & Devices, Apnea, Integrations,
 * Data & Backup, Advice, and Developer. Each category expands in place to
 * reveal its sub-sections.
 */
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

    LockPortrait()

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
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setMeditationAudioDir(uri.toString())
        }
    }

    val debugDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDebugFileDir(uri.toString())
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

    // Live one-line summary for the Sensors & Devices category header, so the
    // connection state is visible without expanding the card.
    val sensorSummary = when (val ds = state.deviceState) {
        is BleConnectionState.Connected -> "Connected: ${ds.deviceName}"
        is BleConnectionState.Connecting -> "Connecting…"
        is BleConnectionState.Scanning -> "Scanning…"
        is BleConnectionState.Error -> "Sensor error"
        is BleConnectionState.Disconnected ->
            if (state.garminState is GarminConnectionState.Connected)
                "Garmin watch connected · no heart-rate sensor"
            else "Heart-rate sensor · scan · Garmin watch"
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    LiveSensorActionsNav(navController)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

            // ── Sensors & Devices ──────────────────────────────────────────
            item {
                SettingsCategoryCard(
                    emoji = "📡",
                    title = "Sensors & Devices",
                    summary = sensorSummary
                ) {
                    ConnectedDeviceSection(
                        deviceState = state.deviceState,
                        onDisconnect = { viewModel.disconnectDevice() }
                    )
                    SettingsSubSectionDivider()
                    GarminWatchSection(
                        garminState = state.garminState,
                        onManage = { navController.navigate(WagsRoutes.GARMIN) }
                    )
                    SettingsSubSectionDivider()
                    NearbySensorsSection(
                        isScanning = state.isScanning,
                        scanResults = state.scanResults,
                        deviceState = state.deviceState,
                        onScan = { requestScan() },
                        onStopScan = { viewModel.stopScan() },
                        onConnect = { viewModel.connectDevice(it) }
                    )
                }
            }

            // ── Apnea ──────────────────────────────────────────────────────
            item {
                SettingsCategoryCard(
                    emoji = "🫁",
                    title = "Apnea",
                    summary = "Hyper cooldown · voice · vibration warnings"
                ) {
                    HyperLockDaysRow(
                        days = state.hyperLockDays,
                        onDaysChange = { viewModel.setHyperLockDays(it) }
                    )
                    ApneaVibrationSettingsSection(
                        settings = state.apneaVibration,
                        onVoiceEnabledChange = viewModel::setApneaVoiceEnabled,
                        onVibrationEnabledChange = viewModel::setApneaVibrationEnabled,
                        onBreathSameAsHoldChange = viewModel::setApneaBreathSameAsHold,
                        onHoldWarningChange = viewModel::setApneaHoldWarning,
                        onBreathWarningChange = viewModel::setApneaBreathWarning,
                        onTestHoldWarning = viewModel::testApneaHoldWarning,
                        onTestBreathWarning = viewModel::testApneaBreathWarning
                    )
                }
            }

            // ── Integrations ───────────────────────────────────────────────
            item {
                SettingsCategoryCard(
                    emoji = "🔗",
                    title = "Integrations",
                    summary = "Spotify · Tail habits · meditation audio"
                ) {
                    SpotifySection(
                        spotifyConnected = state.spotifyConnected,
                        onConnectSpotify = {
                            context.startActivity(viewModel.buildSpotifyLoginIntent())
                        },
                        onDisconnectSpotify = { viewModel.disconnectSpotify() }
                    )
                    SettingsSubSectionDivider()
                    TailAppIntegrationSection(
                        habitList               = state.habitList,
                        isLoading               = state.isLoadingHabits,
                        habitAppUnavailable     = state.habitAppUnavailable,
                        freeHoldHabit           = state.freeHoldHabit,
                        apneaNewRecordHabit     = state.apneaNewRecordHabit,
                        o2TableHabit            = state.o2TableHabit,
                        co2TableHabit           = state.co2TableHabit,
                        morningReadinessHabit   = state.morningReadinessHabit,
                        hrvReadinessHabit       = state.hrvReadinessHabit,
                        resonanceBreathingHabit = state.resonanceBreathingHabit,
                        meditationHabit         = state.meditationHabit,
                        rapidHrChangeHabit      = state.rapidHrChangeHabit,
                        progressiveO2Habit      = state.progressiveO2Habit,
                        minBreathHabit          = state.minBreathHabit,
                        tillContractionHabit    = state.tillContractionHabit,
                        contractionCountHabit   = state.contractionCountHabit,
                        musicHabit              = state.musicHabit,
                        onSelectHabit           = { slot, entry -> viewModel.selectHabit(slot, entry) },
                        onClearHabit            = { slot -> viewModel.clearHabit(slot) },
                        onRefresh               = { viewModel.loadHabits() },
                        isBackfilling           = state.isBackfilling,
                        backfillMessage         = state.backfillMessage,
                        backfillError           = state.backfillError,
                        onBackfill              = { viewModel.backfillHabitMinutes() },
                        onDismissBackfillMsg    = { viewModel.clearBackfillMessage() }
                    )
                    SettingsSubSectionDivider()
                    MeditationAudioDirectorySection(
                        dirUri = state.meditationAudioDirUri,
                        onChooseDirectory = { meditationDirLauncher.launch(null) },
                        onClearDirectory = { viewModel.clearMeditationAudioDir() }
                    )
                }
            }

            // ── Data & Backup ──────────────────────────────────────────────
            item {
                SettingsCategoryCard(
                    emoji = "💾",
                    title = "Data & Backup",
                    summary = "Export everything to a ZIP · restore from backup"
                ) {
                    DataExportImportSection(
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

            // ── Advice ─────────────────────────────────────────────────────
            item {
                SettingsCategoryCard(
                    emoji = "💬",
                    title = "Advice",
                    summary = "Personal reminders shown at the top of each screen"
                ) {
                    AdviceSettingsSection(
                        adviceBySection = adviceState.adviceBySection,
                        onOpenSection = { openAdviceSection = it }
                    )
                }
            }

            // ── Developer ──────────────────────────────────────────────────
            item {
                SettingsCategoryCard(
                    emoji = "🐛",
                    title = "Developer",
                    summary = "Debug bubble · crash logs"
                ) {
                    DebugModeSection(
                        debugModeEnabled = state.debugModeEnabled,
                        debugFileDirUri = state.debugFileDirUri,
                        onToggleDebugMode = { viewModel.setDebugModeEnabled(it) },
                        onChooseDirectory = { debugDirLauncher.launch(null) },
                        onClearDirectory = { viewModel.clearDebugFileDir() }
                    )
                    SettingsSubSectionDivider()
                    CrashLogsSection(
                        onViewLogs = { navController.navigate(WagsRoutes.CRASH_LOGS) }
                    )
                }
            }

            // ── About ──────────────────────────────────────────────────────
            item {
                OutlinedButton(
                    onClick = { navController.navigate(WagsRoutes.ABOUT) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text("About")
                }
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

// ── Hyper cooldown stepper row (inside the Apnea category) ───────────────────

@Composable
private fun HyperLockDaysRow(
    days: Int,
    onDaysChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Hyper Cooldown",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "Days required between Hyper uses",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        IconButton(
            onClick = { onDaysChange(days - 1) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("−", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
        Text(
            text = "${days}d",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        IconButton(
            onClick = { onDaysChange(days + 1) },
            modifier = Modifier.size(28.dp)
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
    }
}
