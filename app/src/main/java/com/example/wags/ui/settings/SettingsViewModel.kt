package com.example.wags.ui.settings

import android.bluetooth.le.ScanResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.ble.OximeterBleManager
import com.example.wags.data.ble.PolarBleManager
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
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
    val savedH10Id: String = "",
    val savedVerityId: String = "",
    // Morning default (3 am – 11 am)
    val morningDeviceId: String = "",
    val morningDeviceType: String = "",
    val morningDeviceName: String = "",
    // Day default (11 am – 3 am)
    val dayDeviceId: String = "",
    val dayDeviceType: String = "",
    val dayDeviceName: String = "",
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
    val resonanceBreathingHabit: HabitSlotSelection = HabitSlotSelection()
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val devicePrefs: DevicePreferencesRepository,
    private val bleManager: PolarBleManager,
    private val oximeterBleManager: OximeterBleManager,
    private val habitRepo: HabitIntegrationRepository
) : ViewModel() {

    // Separate MutableStateFlow for habit-specific state so it doesn't need to
    // be folded into the 7-way BLE combine below.
    private val _habitState = MutableStateFlow(buildInitialHabitState())

    val uiState: StateFlow<SettingsUiState> = combine(
        bleManager.h10State,
        bleManager.verityState,
        bleManager.isScanning,
        bleManager.scanResults,
        oximeterBleManager.connectionState,
        oximeterBleManager.scanResults,
        devicePrefs.snapshot
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

        val oximeterScanning = oximeterState is OximeterConnectionState.Scanning

        SettingsUiState(
            h10State            = h10State,
            verityState         = verityState,
            oximeterState       = oximeterState,
            isScanning          = polarScanning || oximeterScanning,
            polarScanResults    = polarResults,
            oximeterScanResults = oximeterResults,
            savedH10Id          = snap.savedH10Id,
            savedVerityId       = snap.savedVerityId,
            morningDeviceId     = snap.morningDeviceId,
            morningDeviceType   = snap.morningDeviceType,
            morningDeviceName   = snap.morningDeviceName,
            dayDeviceId         = snap.dayDeviceId,
            dayDeviceType       = snap.dayDeviceType,
            dayDeviceName       = snap.dayDeviceName
        )
    }.combine(_habitState) { bleState, habit ->
        // Merge the habit sub-state into the BLE-derived state
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
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            savedH10Id              = devicePrefs.savedH10Id,
            savedVerityId           = devicePrefs.savedVerityId,
            morningDeviceId         = devicePrefs.morningDeviceId,
            morningDeviceType       = devicePrefs.morningDeviceType,
            morningDeviceName       = devicePrefs.morningDeviceName,
            dayDeviceId             = devicePrefs.dayDeviceId,
            dayDeviceType           = devicePrefs.dayDeviceType,
            dayDeviceName           = devicePrefs.dayDeviceName,
            freeHoldHabit           = slotSelection(Slot.FREE_HOLD),
            apneaNewRecordHabit     = slotSelection(Slot.APNEA_NEW_RECORD),
            tableTrainingHabit      = slotSelection(Slot.TABLE_TRAINING),
            morningReadinessHabit   = slotSelection(Slot.MORNING_READINESS),
            hrvReadinessHabit       = slotSelection(Slot.HRV_READINESS),
            resonanceBreathingHabit = slotSelection(Slot.RESONANCE_BREATHING)
        )
    )

    // ── Unified scan ─────────────────────────────────────────────────────────

    /** Starts both Polar and Oximeter scans simultaneously. */
    fun startScan() {
        bleManager.startScan()
        oximeterBleManager.startScan()
    }

    /** Stops both scans. */
    fun stopScan() {
        bleManager.stopScan()
        oximeterBleManager.stopScan()
    }

    // ── Polar connections ─────────────────────────────────────────────────────

    fun connectH10(device: PolarDeviceInfo) {
        oximeterBleManager.disconnect()
        devicePrefs.savedH10Id = device.deviceId
        devicePrefs.refresh()
        bleManager.connectDevice(device.deviceId, isH10 = true)
    }

    fun connectVerity(device: PolarDeviceInfo) {
        oximeterBleManager.disconnect()
        devicePrefs.savedVerityId = device.deviceId
        devicePrefs.refresh()
        bleManager.connectDevice(device.deviceId, isH10 = false)
    }

    fun disconnectH10() {
        val id = devicePrefs.savedH10Id
        if (id.isNotBlank()) bleManager.disconnectDevice(id)
    }

    fun disconnectVerity() {
        val id = devicePrefs.savedVerityId
        if (id.isNotBlank()) bleManager.disconnectDevice(id)
    }

    // ── Oximeter connections ──────────────────────────────────────────────────

    fun connectOximeter(address: String, name: String = "") {
        stopScan()
        disconnectH10()
        disconnectVerity()
        devicePrefs.savedOximeterAddress = address
        devicePrefs.refresh()
        oximeterBleManager.connect(address)
    }

    fun disconnectOximeter() = oximeterBleManager.disconnect()

    // ── Morning default ───────────────────────────────────────────────────────

    fun setMorningDefaultPolar(device: PolarDeviceInfo, isH10: Boolean) {
        devicePrefs.morningDeviceId   = device.deviceId
        devicePrefs.morningDeviceType = if (isH10) "h10" else "verity"
        devicePrefs.morningDeviceName = device.name.ifBlank { device.deviceId }
        devicePrefs.refresh()
    }

    fun setMorningDefaultOximeter(address: String, name: String) {
        devicePrefs.morningDeviceId   = address
        devicePrefs.morningDeviceType = "oximeter"
        devicePrefs.morningDeviceName = name.ifBlank { address }
        devicePrefs.refresh()
    }

    fun clearMorningDefault() {
        devicePrefs.morningDeviceId   = ""
        devicePrefs.morningDeviceType = ""
        devicePrefs.morningDeviceName = ""
        devicePrefs.refresh()
    }

    // ── Day default ───────────────────────────────────────────────────────────

    fun setDayDefaultPolar(device: PolarDeviceInfo, isH10: Boolean) {
        devicePrefs.dayDeviceId   = device.deviceId
        devicePrefs.dayDeviceType = if (isH10) "h10" else "verity"
        devicePrefs.dayDeviceName = device.name.ifBlank { device.deviceId }
        devicePrefs.refresh()
    }

    fun setDayDefaultOximeter(address: String, name: String) {
        devicePrefs.dayDeviceId   = address
        devicePrefs.dayDeviceType = "oximeter"
        devicePrefs.dayDeviceName = name.ifBlank { address }
        devicePrefs.refresh()
    }

    fun clearDayDefault() {
        devicePrefs.dayDeviceId   = ""
        devicePrefs.dayDeviceType = ""
        devicePrefs.dayDeviceName = ""
        devicePrefs.refresh()
    }

    // ── Habit / Tail integration ──────────────────────────────────────────────

    /**
     * Queries the Habit app's Content Provider on the IO dispatcher and updates
     * [_habitState] with the result. Safe to call multiple times.
     */
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

    /** Persists [entry] as the selected habit for [slot] and updates UI state. */
    fun selectHabit(slot: Slot, entry: HabitEntry) {
        habitRepo.setHabit(slot, entry)
        val selection = HabitSlotSelection(entry.habitId, entry.habitName)
        _habitState.update { it.copySlot(slot, selection) }
    }

    /** Clears the habit selection for [slot]. */
    fun clearHabit(slot: Slot) {
        habitRepo.clearHabit(slot)
        _habitState.update { it.copySlot(slot, HabitSlotSelection()) }
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
    /** Returns a copy with the given [slot]'s selection replaced by [value]. */
    fun copySlot(slot: HabitIntegrationRepository.Slot, value: HabitSlotSelection) = when (slot) {
        HabitIntegrationRepository.Slot.FREE_HOLD           -> copy(freeHoldHabit = value)
        HabitIntegrationRepository.Slot.APNEA_NEW_RECORD    -> copy(apneaNewRecordHabit = value)
        HabitIntegrationRepository.Slot.TABLE_TRAINING      -> copy(tableTrainingHabit = value)
        HabitIntegrationRepository.Slot.MORNING_READINESS   -> copy(morningReadinessHabit = value)
        HabitIntegrationRepository.Slot.HRV_READINESS       -> copy(hrvReadinessHabit = value)
        HabitIntegrationRepository.Slot.RESONANCE_BREATHING -> copy(resonanceBreathingHabit = value)
    }
}
