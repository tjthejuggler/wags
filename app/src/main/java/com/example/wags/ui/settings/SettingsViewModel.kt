package com.example.wags.ui.settings

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.AutoConnectManager
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.debug.DebugPreferences
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.data.garmin.GarminManager
import com.example.wags.data.ipc.HabitBackfillManager
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaTimeDimensionStore
import com.example.wags.data.repository.DataExportImportRepository
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.HabitEntry
import com.example.wags.domain.model.ScannedDevice
import com.example.wags.domain.model.TimeDimension
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.ApneaVibrationWarningConfig
import com.example.wags.domain.usecase.apnea.HyperLockManager
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

// ── Apnea audio/haptics settings (mirrored from ApneaAudioHapticEngine) ───────

data class ApneaVibrationSettings(
    val voiceEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val holdWarning: ApneaVibrationWarningConfig = ApneaVibrationWarningConfig.HOLD_DEFAULT,
    val breathWarning: ApneaVibrationWarningConfig = ApneaVibrationWarningConfig.BREATH_DEFAULT,
    /** When true, breaths use the [holdWarning] config as well. */
    val breathSameAsHold: Boolean = false
)

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
    val o2TableHabit: HabitSlotSelection = HabitSlotSelection(),
    val co2TableHabit: HabitSlotSelection = HabitSlotSelection(),
    val morningReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val hrvReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val resonanceBreathingHabit: HabitSlotSelection = HabitSlotSelection(),
    val meditationHabit: HabitSlotSelection = HabitSlotSelection(),
    val rapidHrChangeHabit: HabitSlotSelection = HabitSlotSelection(),
    val progressiveO2Habit: HabitSlotSelection = HabitSlotSelection(),
    val minBreathHabit: HabitSlotSelection = HabitSlotSelection(),
    val tillContractionHabit: HabitSlotSelection = HabitSlotSelection(),
    val contractionCountHabit: HabitSlotSelection = HabitSlotSelection(),
    val musicHabit: HabitSlotSelection = HabitSlotSelection(),
    // ── Habit backfill (retroactive minute export to Tail) ─────────────────────
    val isBackfilling: Boolean = false,
    val backfillMessage: String? = null,
    val backfillError: String? = null,
    // ── Spotify account ───────────────────────────────────────────────────────
    val spotifyConnected: Boolean = false,
    // ── Data Export / Import ────────────────────────────────────────────────────
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportImportMessage: String? = null,
    val exportImportError: String? = null,
    val exportFileName: String = "",
    // ── Debug Mode ────────────────────────────────────────────────────────────
    val debugModeEnabled: Boolean = false,
    val debugFileDirUri: String = "",
    // ── Apnea ─────────────────────────────────────────────────────────────────
    /** Days required between HYPER prep sessions (0 disables the lock). */
    val hyperLockDays: Int = HyperLockManager.DEFAULT_LOCK_DAYS,
    /** Apnea voice/vibration indication + customizable warning vibrations. */
    val apneaVibration: ApneaVibrationSettings = ApneaVibrationSettings(),
    /** How apnea records are bucketed by time: Morning/Day/Night or by the hour. */
    val apneaTimeDimension: TimeDimension = TimeDimension.TIME_OF_DAY
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val devicePrefs: DevicePreferencesRepository,
    private val deviceManager: UnifiedDeviceManager,
    private val autoConnectManager: AutoConnectManager,
    private val habitRepo: HabitIntegrationRepository,
    private val habitBackfillManager: HabitBackfillManager,
    private val meditationRepository: com.example.wags.data.repository.MeditationRepository,
    private val garminManager: GarminManager,
    private val dataExportImportRepo: DataExportImportRepository,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val debugPrefs: DebugPreferences,
    private val hyperLockManager: HyperLockManager,
    private val apneaAudioHapticEngine: ApneaAudioHapticEngine,
    private val timeDimensionStore: ApneaTimeDimensionStore
) : ViewModel() {

    private val _habitState = MutableStateFlow(buildInitialHabitState())
    private val _exportImportState = MutableStateFlow(ExportImportPartialState())
    private val _backfillState = MutableStateFlow(BackfillPartialState())
    private val _hyperLockDays = MutableStateFlow(hyperLockManager.lockDays)
    private val _apneaVibrationState = MutableStateFlow(loadApneaVibrationSettings())

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
            o2TableHabit            = habit.o2TableHabit,
            co2TableHabit           = habit.co2TableHabit,
            morningReadinessHabit   = habit.morningReadinessHabit,
            hrvReadinessHabit       = habit.hrvReadinessHabit,
            resonanceBreathingHabit = habit.resonanceBreathingHabit,
            meditationHabit         = habit.meditationHabit,
            rapidHrChangeHabit      = habit.rapidHrChangeHabit,
            progressiveO2Habit      = habit.progressiveO2Habit,
            minBreathHabit          = habit.minBreathHabit,
            tillContractionHabit    = habit.tillContractionHabit,
            contractionCountHabit   = habit.contractionCountHabit,
            musicHabit              = habit.musicHabit
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
    }.combine(debugPrefs.snapshot) { state, debugSnap ->
        state.copy(
            debugModeEnabled = debugSnap.debugModeEnabled,
            debugFileDirUri  = debugSnap.debugFileDirUri
        )
    }.combine(_backfillState) { state, backfill ->
        state.copy(
            isBackfilling   = backfill.isBackfilling,
            backfillMessage = backfill.backfillMessage,
            backfillError   = backfill.backfillError
        )
    }.combine(_hyperLockDays) { state, days ->
        state.copy(hyperLockDays = days)
    }.combine(_apneaVibrationState) { state, apneaVibration ->
        state.copy(apneaVibration = apneaVibration)
    }.combine(timeDimensionStore.dimension) { state, dimension ->
        state.copy(apneaTimeDimension = dimension)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            meditationAudioDirUri   = devicePrefs.meditationAudioDirUri,
            freeHoldHabit           = slotSelection(Slot.FREE_HOLD),
            apneaNewRecordHabit     = slotSelection(Slot.APNEA_NEW_RECORD),
            o2TableHabit            = slotSelection(Slot.O2_TABLE),
            co2TableHabit           = slotSelection(Slot.CO2_TABLE),
            morningReadinessHabit   = slotSelection(Slot.MORNING_READINESS),
            hrvReadinessHabit       = slotSelection(Slot.HRV_READINESS),
            resonanceBreathingHabit = slotSelection(Slot.RESONANCE_BREATHING),
            meditationHabit         = slotSelection(Slot.MEDITATION),
            rapidHrChangeHabit      = slotSelection(Slot.RAPID_HR_CHANGE),
            progressiveO2Habit      = slotSelection(Slot.PROGRESSIVE_O2),
            minBreathHabit          = slotSelection(Slot.MIN_BREATH),
            tillContractionHabit    = slotSelection(Slot.TILL_CONTRACTION),
            contractionCountHabit   = slotSelection(Slot.CONTRACTION_COUNT),
            debugModeEnabled        = debugPrefs.debugModeEnabled,
            debugFileDirUri         = debugPrefs.debugFileDirUri
        )
    )

    // ── Apnea ─────────────────────────────────────────────────────────────────

    /** Set how many days must pass between HYPER sessions (0 disables the lock). */
    fun setHyperLockDays(days: Int) {
        hyperLockManager.setLockDays(days)
        _hyperLockDays.value = hyperLockManager.lockDays
    }

    /**
     * Switch the apnea time dimension (Time of Day vs By the Hour). Applies
     * instantly and retroactively — records/PBs/trophies/stats/forecasts are
     * recalculated against the chosen bucketing on the next query.
     */
    fun setApneaTimeDimension(dimension: TimeDimension) {
        timeDimensionStore.set(dimension)
    }

    private fun loadApneaVibrationSettings() = ApneaVibrationSettings(
        voiceEnabled = apneaAudioHapticEngine.voiceEnabled,
        vibrationEnabled = apneaAudioHapticEngine.vibrationEnabled,
        holdWarning = apneaAudioHapticEngine.holdWarning,
        breathWarning = apneaAudioHapticEngine.breathWarning,
        breathSameAsHold = apneaAudioHapticEngine.breathSameAsHold
    )

    private fun refreshApneaVibrationState() {
        _apneaVibrationState.value = loadApneaVibrationSettings()
    }

    fun setApneaVoiceEnabled(enabled: Boolean) {
        apneaAudioHapticEngine.voiceEnabled = enabled
        refreshApneaVibrationState()
    }

    fun setApneaVibrationEnabled(enabled: Boolean) {
        apneaAudioHapticEngine.vibrationEnabled = enabled
        refreshApneaVibrationState()
    }

    fun setApneaHoldWarning(config: ApneaVibrationWarningConfig) {
        apneaAudioHapticEngine.holdWarning = config
        refreshApneaVibrationState()
    }

    fun setApneaBreathWarning(config: ApneaVibrationWarningConfig) {
        apneaAudioHapticEngine.breathWarning = config
        refreshApneaVibrationState()
    }

    /** When enabled, the breath warning mirrors the hold warning config. */
    fun setApneaBreathSameAsHold(same: Boolean) {
        apneaAudioHapticEngine.breathSameAsHold = same
        refreshApneaVibrationState()
    }

    /** Preview the configured hold-ending warning vibration. */
    fun testApneaHoldWarning() = apneaAudioHapticEngine.playHoldWarning()

    /** Preview the configured breath-ending warning vibration. */
    fun testApneaBreathWarning() = apneaAudioHapticEngine.playBreathWarning()

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
        autoConnectManager.notifyUserConnect()
        deviceManager.connect(device)
    }

    /**
     * Disconnect whichever device is currently connected.
     * Notifies [AutoConnectManager] to suppress auto-reconnect for 60 s
     * so the user has time to connect a different device.
     */
    fun disconnectDevice() {
        autoConnectManager.notifyUserDisconnect()
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
        backfillSlotHistory(slot)
    }

    /**
     * Auto-backfill: when a slot gets a NEW habit connection, push that
     * slot's entire per-date history backlog to Tail so the habit starts
     * with complete data. Silently does nothing for slots without history.
     */
    private fun backfillSlotHistory(slot: Slot) {
        viewModelScope.launch {
            try {
                val result = habitBackfillManager.backfillSlot(slot)
                if (!result.skipped && result.dates > 0) {
                    _backfillState.update {
                        it.copy(
                            backfillMessage = "Backfilled ${slot.label}: ${result.dates} dates " +
                                    "(${result.minutes} min, ${result.sessions} sessions) sent to Tail."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("SettingsVM", "Auto-backfill failed for ${slot.name}", e)
            }
        }
    }

    fun clearHabit(slot: Slot) {
        habitRepo.clearHabit(slot)
        _habitState.update { it.copySlot(slot, HabitSlotSelection()) }
    }

    // ── Retroactive backfill ───────────────────────────────────────────────────

    /**
     * Aggregates minutes from all past sessions, then sends per-date totals
     * to Tail for every connected habit slot. Idempotent — Tail SETS
     * (replaces) the value for each date.
     */
    fun backfillHabitMinutes() {
        viewModelScope.launch {
            _backfillState.update {
                it.copy(isBackfilling = true, backfillMessage = null, backfillError = null)
            }
            try {
                val result = habitBackfillManager.backfill()
                val msg = buildString {
                    append("Sent ${result.totalDates} dates ")
                    append("(${result.totalMinutes} min")
                    if (result.totalSessions > 0) {
                        append(", ${result.totalSessions} sessions")
                    }
                    append(") to Tail.")
                    result.skippedSlots.forEach { slot ->
                        append(" ${slot.label} habit not selected — skipped.")
                    }
                }
                _backfillState.update {
                    it.copy(isBackfilling = false, backfillMessage = msg)
                }
            } catch (e: Exception) {
                _backfillState.update {
                    it.copy(isBackfilling = false, backfillError = "Backfill failed: ${e.message}")
                }
            }
        }
    }

    fun clearBackfillMessage() {
        _backfillState.update { it.copy(backfillMessage = null, backfillError = null) }
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

    // ── Debug Mode ─────────────────────────────────────────────────────────────

    fun setDebugModeEnabled(enabled: Boolean) {
        debugPrefs.debugModeEnabled = enabled
    }

    fun setDebugFileDir(uriString: String) {
        debugPrefs.debugFileDirUri = uriString
    }

    fun clearDebugFileDir() {
        debugPrefs.debugFileDirUri = ""
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
        o2TableHabit            = slotSelection(Slot.O2_TABLE),
        co2TableHabit           = slotSelection(Slot.CO2_TABLE),
        morningReadinessHabit   = slotSelection(Slot.MORNING_READINESS),
        hrvReadinessHabit       = slotSelection(Slot.HRV_READINESS),
        resonanceBreathingHabit = slotSelection(Slot.RESONANCE_BREATHING),
        meditationHabit         = slotSelection(Slot.MEDITATION),
        rapidHrChangeHabit      = slotSelection(Slot.RAPID_HR_CHANGE),
        progressiveO2Habit      = slotSelection(Slot.PROGRESSIVE_O2),
        minBreathHabit          = slotSelection(Slot.MIN_BREATH),
        tillContractionHabit    = slotSelection(Slot.TILL_CONTRACTION),
        contractionCountHabit   = slotSelection(Slot.CONTRACTION_COUNT),
        musicHabit              = slotSelection(Slot.MUSIC)
    )
}

// ── Private sub-state ─────────────────────────────────────────────────────────

private data class HabitPartialState(
    val habitList: List<HabitEntry> = emptyList(),
    val isLoadingHabits: Boolean = false,
    val habitAppUnavailable: Boolean = false,
    val freeHoldHabit: HabitSlotSelection = HabitSlotSelection(),
    val apneaNewRecordHabit: HabitSlotSelection = HabitSlotSelection(),
    val o2TableHabit: HabitSlotSelection = HabitSlotSelection(),
    val co2TableHabit: HabitSlotSelection = HabitSlotSelection(),
    val morningReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val hrvReadinessHabit: HabitSlotSelection = HabitSlotSelection(),
    val resonanceBreathingHabit: HabitSlotSelection = HabitSlotSelection(),
    val meditationHabit: HabitSlotSelection = HabitSlotSelection(),
    val rapidHrChangeHabit: HabitSlotSelection = HabitSlotSelection(),
    val progressiveO2Habit: HabitSlotSelection = HabitSlotSelection(),
    val minBreathHabit: HabitSlotSelection = HabitSlotSelection(),
    val tillContractionHabit: HabitSlotSelection = HabitSlotSelection(),
    val contractionCountHabit: HabitSlotSelection = HabitSlotSelection(),
    val musicHabit: HabitSlotSelection = HabitSlotSelection()
) {
    fun copySlot(slot: HabitIntegrationRepository.Slot, value: HabitSlotSelection) = when (slot) {
        HabitIntegrationRepository.Slot.FREE_HOLD           -> copy(freeHoldHabit = value)
        HabitIntegrationRepository.Slot.APNEA_NEW_RECORD    -> copy(apneaNewRecordHabit = value)
        HabitIntegrationRepository.Slot.O2_TABLE            -> copy(o2TableHabit = value)
        HabitIntegrationRepository.Slot.CO2_TABLE           -> copy(co2TableHabit = value)
        HabitIntegrationRepository.Slot.MORNING_READINESS   -> copy(morningReadinessHabit = value)
        HabitIntegrationRepository.Slot.HRV_READINESS       -> copy(hrvReadinessHabit = value)
        HabitIntegrationRepository.Slot.RESONANCE_BREATHING -> copy(resonanceBreathingHabit = value)
        HabitIntegrationRepository.Slot.MEDITATION          -> copy(meditationHabit = value)
        HabitIntegrationRepository.Slot.RAPID_HR_CHANGE     -> copy(rapidHrChangeHabit = value)
        HabitIntegrationRepository.Slot.PROGRESSIVE_O2      -> copy(progressiveO2Habit = value)
        HabitIntegrationRepository.Slot.MIN_BREATH          -> copy(minBreathHabit = value)
        HabitIntegrationRepository.Slot.TILL_CONTRACTION    -> copy(tillContractionHabit = value)
        HabitIntegrationRepository.Slot.CONTRACTION_COUNT   -> copy(contractionCountHabit = value)
        HabitIntegrationRepository.Slot.MUSIC               -> copy(musicHabit = value)
    }
}

private data class ExportImportPartialState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportImportMessage: String? = null,
    val exportImportError: String? = null
)

private data class BackfillPartialState(
    val isBackfilling: Boolean = false,
    val backfillMessage: String? = null,
    val backfillError: String? = null
)
