package com.example.wags.ui.settings

import android.bluetooth.le.ScanResult
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.ble.OximeterBleManager
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.data.garmin.GarminManager
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.DataExportImportRepository
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.OximeterConnectionState
import com.polar.sdk.api.model.PolarDeviceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Per-slot habit selection (id + display name) ──────────────────────────────

data class HabitSlotSelection(
    val habitId: String = "",
    val habitName: String = ""
) {
    val isSet: Boolean get() = habitId.isNotBlank()
    val displayName: String get() = habitName.ifBlank { habitId }
}

// ── UI state ──────────────────────────────────────────────────────────────────

data class SettingsUiState(
    val h10State: BleConnectionState = BleConnectionState.Disconnected,
    val verityState: BleConnectionState = BleConnectionState.Disconnected,
    val oximeterState: OximeterConnectionState = OximeterConnectionState.Disconnected,
    /** true while either Polar or Oximeter scan is running */
    val isScanning: Boolean = false,
    val polarScanResults: List<PolarDeviceInfo> = emptyList(),
    val oximeterScanResults: List<ScanResult> = emptyList(),
    val savedPolarId: String = "",
    // ── Garmin Watch ──────────────────────────────────────────────────────────
    val garminState: GarminConnectionState = GarminConnectionState.Uninitialized,
    // ── Meditation audio directory ─────────────────────────────────────────────
    /** SAF URI string of the chosen meditation audio folder, or "" if not set. */
    val meditationAudioDirUri: String = "",
    // ── Tail / Habit app integration ──────────────────────────────────────────
    /** Habits fetched from the Habit app's Content Provider. Empty until loaded. */
    val habitList: List<HabitEntry> = emptyList(),
    /** true while the ContentResolver query is in flight. */
    val isLoadingHabits: Boolean = false,
    /** true if the Habit app could not be reached (provider returned nothing). */
    val habitAppUnavailable: Boolean = false,
    // One selection per WAGS activity
    val freeHoldHabit: HabitSlotSelection = HabitSlotSelection(),
    val apneaNewRecordHabit: HabitSlotSelection = HabitSlotSelection(),
    val tableTrainingHabit: HabitSlotSelection = HabitSlotSelection(),
    val morningReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val hrvReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val resonanceBreathingHabit: HabitSlotSelection = HabitSlotSelection(),
    // ── Data Export / Import ────────────────────────────────────────────────────
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportImportMessage: String? = null,
    val exportImportError: String? = null,
    /** Suggested filename for the export (set when user taps Export). */
    val exportFileName: String = ""
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val devicePrefs: DevicePreferencesRepository,
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val habitRepo: HabitIntegrationRepository,
    private val meditationRepository: com.example.wags.data.repository.MeditationRepository,
    private val garminManager: GarminManager,
    private val dataExportImportRepo: DataExportImportRepository
) : ViewModel() {

    // Separate MutableStateFlow for habit-specific state so it doesn't need to
    // be folded into the BLE combine below.
    private val _habitState = MutableStateFlow(buildInitialHabitState())

    // Export/import state tracked separately
    private val _exportImportState = MutableStateFlow(ExportImportPartialState())

    val uiState: StateFlow<SettingsUiState> = combine(
        bleManager.h10State,
        bleManager.verityState,
        bleManager.isScanning,
        bleManager.scanResults,
        oximeterBleManager.connectionState,
        oximeterBleManager.scanResults,
        devicePrefs.snapshot,
        garminManager.connectionState
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val h10State        = args[0] as BleConnectionState
        @Suppress("UNCHECKED_CAST")
        val verityState     = args[1] as BleConnectionState
        val polarScanning   = args[2] as Boolean
        @Suppress("UNCHECKED_CAST")
        val polarResults    = args[3] as List<PolarDeviceInfo>
        val oximeterState   = args[4] as OximeterConnectionState
        @Suppress("UNCHECKED_CAST")
        val oximeterResults = args[5] as List<ScanResult>
        val snap            = args[6] as com.example.wags.data.ble.DevicePrefsSnapshot
        val garminState     = args[7] as GarminConnectionState

        val oximeterScanning = oximeterState is OximeterConnectionState.Scanning

        SettingsUiState(
            h10State              = h10State,
            verityState           = verityState,
            oximeterState         = oximeterState,
            isScanning            = polarScanning || oximeterScanning,
            polarScanResults      = polarResults,
            oximeterScanResults   = oximeterResults,
            savedPolarId          = snap.savedPolarId,
            garminState           = garminState,
            meditationAudioDirUri = snap.meditationAudioDirUri
        )
    }.combine(_habitState) { bleState, habit ->
        bleState.copy(
            habitList               = habit.habitList,
            isLoadingHabits         = habit.isLoadingHabits,
            habitAppUnavailable     = habit.habitAppUnavailable,
            freeHoldHabit           = habit.freeHoldHabit,
            apneaNewRecordHabit     = habit.apneaNewRecordHabit,
            tableTrainingHabit      = habit.tableTrainingHabit,
            morningReadinessHabit   = habit.morningReadinessHabit,
            hrvReadinessHabit       = habit.hrvReadinessHabit,
            resonanceBreathingHabit = habit.resonanceBreathingHabit
        )
    }.combine(_exportImportState) { state, exportImport ->
        state.copy(
            isExporting          = exportImport.isExporting,
            isImporting          = exportImport.isImporting,
            exportImportMessage  = exportImport.exportImportMessage,
            exportImportError    = exportImport.exportImportError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            savedPolarId            = devicePrefs.savedPolarId,
            meditationAudioDirUri   = devicePrefs.meditationAudioDirUri,
            freeHoldHabit           = slotSelection(Slot.FREE_HOLD),
            apneaNewRecordHabit     = slotSelection(Slot.APNEA_NEW_RECORD),
            tableTrainingHabit      = slotSelection(Slot.TABLE_TRAINING),
            morningReadinessHabit   = slotSelection(Slot.MORNING_READINESS),
            hrvReadinessHabit       = slotSelection(Slot.HRV_READINESS),
            resonanceBreathingHabit = slotSelection(Slot.RESONANCE_BREATHING)
        )
    )

    // ── Unified scan ─────────────────────────────────────────────────────────

    fun startScan() {
        bleManager.startScan()
        viewModelScope.launch {
            kotlinx.coroutines.delay(800)
            oximeterBleManager.startScan()
        }
    }

    fun stopScan() {
        bleManager.stopScan()
        oximeterBleManager.stopScan()
    }

    // ── Polar connections (simplified — no device type assignment) ─────────────

    /**
     * Connect to a Polar device. The device type (H10 vs Verity Sense) is
     * determined automatically from the device name after connection.
     */
    fun connectPolar(device: PolarDeviceInfo) {
        devicePrefs.savedPolarId = device.deviceId
        devicePrefs.refresh()
        bleManager.connectDevice(device.deviceId)
    }

    /**
     * Disconnect whichever Polar device is currently connected.
     */
    fun disconnectPolar() {
        val h10 = bleManager.h10State.value
        val verity = bleManager.verityState.value
        if (h10 is BleConnectionState.Connected) {
            bleManager.disconnectDevice(h10.deviceId)
        }
        if (verity is BleConnectionState.Connected) {
            bleManager.disconnectDevice(verity.deviceId)
        }
    }

    // ── Oximeter connections ──────────────────────────────────────────────────

    fun connectOximeter(address: String) {
        stopScan()
        devicePrefs.savedOximeterAddress = address
        devicePrefs.refresh()
        oximeterBleManager.connect(address)
    }

    fun disconnectOximeter() = oximeterBleManager.disconnect()

    // ── Meditation audio directory ────────────────────────────────────────────

    fun setMeditationAudioDir(uriString: String) {
        meditationRepository.setAudioDirUri(uriString)
    }

    fun clearMeditationAudioDir() {
        meditationRepository.setAudioDirUri("")
    }

    // ── Habit / Tail integration ──────────────────────────────────────────────

    fun loadHabits() {
        viewModelScope.launch {
            _habitState.update { it.copy(isLoadingHabits = true, habitAppUnavailable = false) }
            val habits = habitRepo.fetchHabits()
            _habitState.update {
                it.copy(
                    habitList           = habits,
                    isLoadingHabits     = false,
                    habitAppUnavailable = habits.isEmpty()
                )
            }
        }
    }

    fun selectHabit(slot: Slot, entry: HabitEntry) {
        habitRepo.setHabit(slot, entry)
        val selection = HabitSlotSelection(entry.habitId, entry.habitName)
        _habitState.update { it.copySlot(slot, selection) }
    }

    fun clearHabit(slot: Slot) {
        habitRepo.clearHabit(slot)
        _habitState.update { it.copySlot(slot, HabitSlotSelection()) }
    }

    // ── Data Export / Import ───────────────────────────────────────────────────

    fun getExportFileName(): String = dataExportImportRepo.generateExportFileName()

    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.update { it.copy(isExporting = true, exportImportMessage = null, exportImportError = null) }
            try {
                val summary = dataExportImportRepo.exportData(uri)
                _exportImportState.update { it.copy(isExporting = false, exportImportMessage = summary) }
            } catch (e: Exception) {
                _exportImportState.update {
                    it.copy(isExporting = false, exportImportError = "Export failed: ${e.message}")
                }
            }
        }
    }

    fun importData(uri: Uri) {
        viewModelScope.launch {
            _exportImportState.update { it.copy(isImporting = true, exportImportMessage = null, exportImportError = null) }
            try {
                val summary = dataExportImportRepo.importData(uri)
                _exportImportState.update { it.copy(isImporting = false, exportImportMessage = summary) }
            } catch (e: Exception) {
                _exportImportState.update {
                    it.copy(isImporting = false, exportImportError = "Import failed: ${e.message}")
                }
            }
        }
    }

    fun clearExportImportMessage() {
        _exportImportState.update { it.copy(exportImportMessage = null, exportImportError = null) }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun slotSelection(slot: Slot) = HabitSlotSelection(
        habitId   = habitRepo.getHabitId(slot),
        habitName = habitRepo.getHabitName(slot)
    )

    private fun buildInitialHabitState() = HabitPartialState(
        freeHoldHabit           = slotSelection(Slot.FREE_HOLD),
        apneaNewRecordHabit     = slotSelection(Slot.APNEA_NEW_RECORD),
        tableTrainingHabit      = slotSelection(Slot.TABLE_TRAINING),
        morningReadinessHabit   = slotSelection(Slot.MORNING_READINESS),
        hrvReadinessHabit       = slotSelection(Slot.HRV_READINESS),
        resonanceBreathingHabit = slotSelection(Slot.RESONANCE_BREATHING)
    )
}

// ── Private sub-state ─────────────────────────────────────────────────────────

private data class HabitPartialState(
    val habitList: List<HabitEntry> = emptyList(),
    val isLoadingHabits: Boolean = false,
    val habitAppUnavailable: Boolean = false,
    val freeHoldHabit: HabitSlotSelection = HabitSlotSelection(),
    val apneaNewRecordHabit: HabitSlotSelection = HabitSlotSelection(),
    val tableTrainingHabit: HabitSlotSelection = HabitSlotSelection(),
    val morningReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val hrvReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val resonanceBreathingHabit: HabitSlotSelection = HabitSlotSelection()
) {
    fun copySlot(slot: HabitIntegrationRepository.Slot, value: HabitSlotSelection) = when (slot) {
        HabitIntegrationRepository.Slot.FREE_HOLD           -> copy(freeHoldHabit = value)
        HabitIntegrationRepository.Slot.APNEA_NEW_RECORD    -> copy(apneaNewRecordHabit = value)
        HabitIntegrationRepository.Slot.TABLE_TRAINING      -> copy(tableTrainingHabit = value)
        HabitIntegrationRepository.Slot.MORNING_READINESS   -> copy(morningReadinessHabit = value)
        HabitIntegrationRepository.Slot.HRV_READINESS       -> copy(hrvReadinessHabit = value)
        HabitIntegrationRepository.Slot.RESONANCE_BREATHING -> copy(resonanceBreathingHabit = value)
    }
}

// ── Export/Import sub-state ───────────────────────────────────────────────────

private data class ExportImportPartialState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportImportMessage: String? = null,
    val exportImportError: String? = null
)
