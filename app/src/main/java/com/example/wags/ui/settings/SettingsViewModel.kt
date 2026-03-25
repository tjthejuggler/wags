package com.example.wags.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.data.garmin.GarminManager
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.DataExportImportRepository
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.ScannedDevice
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
    /** Unified connection state for the single connected device. */
    val deviceState: BleConnectionState = BleConnectionState.Disconnected,
    /** True while scanning for devices. */
    val isScanning: Boolean = false,
    /** Unified scan results — all device types in one list. */
    val scanResults: List<ScannedDevice> = emptyList(),
    // ── Garmin Watch ──────────────────────────────────────────────────────────
    val garminState: GarminConnectionState = GarminConnectionState.Uninitialized,
    // ── Meditation audio directory ─────────────────────────────────────────────
    val meditationAudioDirUri: String = "",
    // ── Tail / Habit app integration ──────────────────────────────────────────
    val habitList: List<HabitEntry> = emptyList(),
    val isLoadingHabits: Boolean = false,
    val habitAppUnavailable: Boolean = false,
    val freeHoldHabit: HabitSlotSelection = HabitSlotSelection(),
    val apneaNewRecordHabit: HabitSlotSelection = HabitSlotSelection(),
    val tableTrainingHabit: HabitSlotSelection = HabitSlotSelection(),
    val morningReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val hrvReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val resonanceBreathingHabit: HabitSlotSelection = HabitSlotSelection(),
    // ── Spotify account ───────────────────────────────────────────────────────
    val spotifyConnected: Boolean = false,
    // ── Data Export / Import ────────────────────────────────────────────────────
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportImportMessage: String? = null,
    val exportImportError: String? = null,
    val exportFileName: String = ""
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val devicePrefs: DevicePreferencesRepository,
    private val deviceManager: UnifiedDeviceManager,
    private val habitRepo: HabitIntegrationRepository,
    private val meditationRepository: com.example.wags.data.repository.MeditationRepository,
    private val garminManager: GarminManager,
    private val dataExportImportRepo: DataExportImportRepository,
    private val spotifyAuthManager: SpotifyAuthManager
) : ViewModel() {

    private val _habitState = MutableStateFlow(buildInitialHabitState())
    private val _exportImportState = MutableStateFlow(ExportImportPartialState())

    val uiState: StateFlow<SettingsUiState> = combine(
        deviceManager.connectionState,
        deviceManager.isScanning,
        deviceManager.scanResults,
        devicePrefs.snapshot,
        garminManager.connectionState
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val deviceState   = args[0] as BleConnectionState
        val scanning      = args[1] as Boolean
        @Suppress("UNCHECKED_CAST")
        val scanResults   = args[2] as List<ScannedDevice>
        val snap          = args[3] as com.example.wags.data.ble.DevicePrefsSnapshot
        val garminState   = args[4] as GarminConnectionState

        SettingsUiState(
            deviceState           = deviceState,
            isScanning            = scanning,
            scanResults           = scanResults,
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
    }.combine(spotifyAuthManager.isConnected) { state, spotifyConnected ->
        state.copy(spotifyConnected = spotifyConnected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
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
        deviceManager.startScan()
    }

    fun stopScan() {
        deviceManager.stopScan()
    }

    // ── Device connections (unified) ──────────────────────────────────────────

    /**
     * Connect to a scanned device. The device type is determined automatically
     * from the device name after connection.
     */
    fun connectDevice(device: ScannedDevice) {
        stopScan()
        deviceManager.connect(device)
    }

    /**
     * Disconnect whichever device is currently connected.
     */
    fun disconnectDevice() {
        deviceManager.disconnect()
    }

    // ── Legacy Polar accessors (for backward compatibility) ──────────────────

    fun disconnectPolar() = disconnectDevice()

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

    // ── Spotify account ───────────────────────────────────────────────────────

    /** Returns an Intent that opens the Spotify login page in the browser. */
    fun buildSpotifyLoginIntent(): Intent = spotifyAuthManager.buildLoginIntent()

    /** Disconnect the Spotify account — clears all stored tokens. */
    fun disconnectSpotify() = spotifyAuthManager.disconnect()

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

private data class ExportImportPartialState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportImportMessage: String? = null,
    val exportImportError: String? = null
)
