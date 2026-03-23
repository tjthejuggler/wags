package com.example.wags.data.ble

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists BLE device identifiers so the auto-connect loop can reconnect to
 * previously-used devices without any manual intervention.
 *
 * ## Unified device history
 *
 * All devices (Polar, oximeter, generic BLE) are stored in a **single ordered
 * history list** (most-recently-connected first, capped at [MAX_HISTORY]).
 *
 * Each entry is stored as `identifier::name::isPolar` (double-colon separated):
 *   - `identifier` — Polar device ID (e.g. "A1B2C3D4") or BLE MAC address
 *   - `name` — advertised device name (for display and type detection)
 *   - `isPolar` — "1" if the device uses the Polar SDK, "0" otherwise
 *
 * The auto-connect loop cycles through this list until one device connects.
 */
@Singleton
class DevicePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Unified device history ──────────────────────────────────────────────────

    /**
     * All known devices, most-recently-connected first.
     * Each entry contains the identifier, name, and whether it's a Polar device.
     */
    val deviceHistory: List<SavedDevice>
        get() = getDeviceHistory(KEY_DEVICE_HISTORY)

    /**
     * Add a device to the unified history. Deduplicates by identifier and
     * caps at [MAX_HISTORY] entries.
     */
    fun addDevice(identifier: String, name: String, isPolar: Boolean) {
        if (identifier.isBlank()) return
        val entry = SavedDevice(identifier, name, isPolar)
        val current = getDeviceHistory(KEY_DEVICE_HISTORY)
        val updated = (listOf(entry) + current.filter { it.identifier != identifier })
            .take(MAX_HISTORY)
        saveDeviceHistory(KEY_DEVICE_HISTORY, updated)
        refresh()
    }

    /**
     * Remove a device from the unified history by identifier.
     */
    fun removeDevice(identifier: String) {
        val current = getDeviceHistory(KEY_DEVICE_HISTORY)
        val updated = current.filter { it.identifier != identifier }
        saveDeviceHistory(KEY_DEVICE_HISTORY, updated)
        refresh()
    }

    // ── Legacy history accessors (for backward compatibility during migration) ──

    /** All known Polar device IDs, most-recently-connected first. */
    val polarHistory: List<String>
        get() = deviceHistory.filter { it.isPolar }.map { it.identifier }

    /** All known non-Polar device addresses, most-recently-connected first. */
    val oximeterHistory: List<String>
        get() = deviceHistory.filter { !it.isPolar }.map { it.identifier }

    /** The most-recently-connected Polar device ID, or "" if none. */
    var savedPolarId: String
        get() = polarHistory.firstOrNull() ?: ""
        set(value) = addDevice(value, "", true)

    /** The most-recently-connected oximeter MAC address, or "" if none. */
    var savedOximeterAddress: String
        get() = oximeterHistory.firstOrNull() ?: ""
        set(value) = addDevice(value, "", false)

    // ── Legacy accessors (kept for backward compatibility) ─────────────────────

    var savedH10Id: String
        get() = savedPolarId
        set(value) { savedPolarId = value }

    var savedVerityId: String
        get() {
            val polars = polarHistory
            return if (polars.size > 1) polars[1] else ""
        }
        set(value) { savedPolarId = value }

    // ── Device label history (human-readable names for the edit dropdown) ─────

    val deviceLabelHistory: List<String> get() = getHistory(KEY_DEVICE_LABEL_HISTORY)

    fun recordDeviceLabel(label: String) {
        addToHistory(KEY_DEVICE_LABEL_HISTORY, label)
    }

    // ── Meditation audio directory ─────────────────────────────────────────────

    var meditationAudioDirUri: String
        get() = prefs.getString(KEY_MEDITATION_DIR_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MEDITATION_DIR_URI, value).apply()

    // ── Reactive snapshot for UI ───────────────────────────────────────────────

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<DevicePrefsSnapshot> = _snapshot.asStateFlow()

    fun refresh() {
        _snapshot.value = buildSnapshot()
    }

    private fun buildSnapshot() = DevicePrefsSnapshot(
        savedPolarId          = savedPolarId,
        savedOximeterAddress  = savedOximeterAddress,
        meditationAudioDirUri = meditationAudioDirUri
    )

    // ── Internal helpers ───────────────────────────────────────────────────────

    private fun getDeviceHistory(key: String): List<SavedDevice> {
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(LIST_SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size >= 3) {
                SavedDevice(
                    identifier = parts[0],
                    name = parts[1],
                    isPolar = parts[2] == "1"
                )
            } else null
        }
    }

    private fun saveDeviceHistory(key: String, devices: List<SavedDevice>) {
        val raw = devices.joinToString(LIST_SEPARATOR) { device ->
            "${device.identifier}${FIELD_SEPARATOR}${device.name}${FIELD_SEPARATOR}${if (device.isPolar) "1" else "0"}"
        }
        prefs.edit().putString(key, raw).apply()
    }

    fun getHistory(key: String): List<String> {
        val raw = prefs.getString(key, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(PIPE_SEPARATOR).filter { it.isNotBlank() }
    }

    fun addToHistory(key: String, id: String) {
        if (id.isBlank()) return
        val updated = (listOf(id) + getHistory(key))
            .distinct()
            .take(MAX_HISTORY)
        prefs.edit().putString(key, updated.joinToString(PIPE_SEPARATOR)).apply()
        refresh()
    }

    companion object {
        const val PREFS_NAME = "wags_device_prefs"

        // Unified device history
        const val KEY_DEVICE_HISTORY = "unified_device_history"

        // Legacy keys (kept for migration)
        private const val KEY_POLAR_HISTORY = "polar_device_history"
        private const val KEY_H10_HISTORY = "h10_device_history"
        private const val KEY_VERITY_HISTORY = "verity_device_history"
        const val KEY_OXIMETER_HISTORY = "oximeter_address_history"

        // Human-readable device label history
        const val KEY_DEVICE_LABEL_HISTORY = "device_label_history"

        // Legacy single-value keys
        private const val KEY_H10_ID_LEGACY = "h10_device_id"
        private const val KEY_VERITY_ID_LEGACY = "verity_device_id"
        private const val KEY_OXIMETER_ADDR_LEGACY = "oximeter_address"

        const val KEY_MEDITATION_DIR_URI = "meditation_audio_dir_uri"

        private const val KEY_LABEL_MIGRATION_VERSION = "device_label_migration_version"
        private const val KEY_UNIFIED_MIGRATION_DONE = "unified_device_migration_done"

        const val MAX_HISTORY = 10

        /** Separator between entries in the unified device history. */
        private const val LIST_SEPARATOR = "|"
        /** Separator between fields within a single entry. */
        private const val FIELD_SEPARATOR = "::"
        /** Separator for legacy pipe-separated lists. */
        private const val PIPE_SEPARATOR = "|"
    }

    // ── One-time migrations ───────────────────────────────────────────────────

    init {
        migrateLegacyKeys()
        migrateLabelPrefixes()
        migrateToUnifiedHistory()
    }

    /**
     * If the app was previously installed with the old single-value keys, migrate
     * those values into the legacy history lists first.
     */
    private fun migrateLegacyKeys() {
        val legacyH10 = prefs.getString(KEY_H10_ID_LEGACY, "") ?: ""
        val legacyVerity = prefs.getString(KEY_VERITY_ID_LEGACY, "") ?: ""
        val legacyOximeter = prefs.getString(KEY_OXIMETER_ADDR_LEGACY, "") ?: ""

        if (legacyH10.isNotBlank() && getHistory(KEY_H10_HISTORY).isEmpty()) {
            addToHistory(KEY_H10_HISTORY, legacyH10)
        }
        if (legacyVerity.isNotBlank() && getHistory(KEY_VERITY_HISTORY).isEmpty()) {
            addToHistory(KEY_VERITY_HISTORY, legacyVerity)
        }
        if (legacyOximeter.isNotBlank() && getHistory(KEY_OXIMETER_HISTORY).isEmpty()) {
            addToHistory(KEY_OXIMETER_HISTORY, legacyOximeter)
        }
    }

    /**
     * One-time migration: clear the device label history that was stored with
     * artificial type prefixes.
     */
    private fun migrateLabelPrefixes() {
        val currentVersion = prefs.getInt(KEY_LABEL_MIGRATION_VERSION, 0)
        if (currentVersion < 1) {
            prefs.edit()
                .remove(KEY_DEVICE_LABEL_HISTORY)
                .putInt(KEY_LABEL_MIGRATION_VERSION, 1)
                .apply()
        }
    }

    /**
     * One-time migration: merge all legacy history lists (Polar, H10, Verity,
     * Oximeter) into the new unified device history.
     */
    private fun migrateToUnifiedHistory() {
        if (prefs.getBoolean(KEY_UNIFIED_MIGRATION_DONE, false)) return

        val existing = getDeviceHistory(KEY_DEVICE_HISTORY)
        if (existing.isNotEmpty()) {
            prefs.edit().putBoolean(KEY_UNIFIED_MIGRATION_DONE, true).apply()
            return
        }

        // Gather all legacy Polar devices
        val polarDevices = (
            getHistory(KEY_POLAR_HISTORY) +
            getHistory(KEY_H10_HISTORY) +
            getHistory(KEY_VERITY_HISTORY)
        ).distinct().map { id -> SavedDevice(id, "", true) }

        // Gather all legacy oximeter devices
        val oximeterDevices = getHistory(KEY_OXIMETER_HISTORY)
            .distinct()
            .map { addr -> SavedDevice(addr, "", false) }

        val merged = (polarDevices + oximeterDevices).take(MAX_HISTORY)
        if (merged.isNotEmpty()) {
            saveDeviceHistory(KEY_DEVICE_HISTORY, merged)
        }

        prefs.edit().putBoolean(KEY_UNIFIED_MIGRATION_DONE, true).apply()
        refresh()
    }
}

/**
 * A device entry in the unified history.
 *
 * @param identifier Polar device ID or BLE MAC address.
 * @param name       Advertised device name (may be blank for legacy entries).
 * @param isPolar    True if this device uses the Polar SDK for connection.
 */
data class SavedDevice(
    val identifier: String,
    val name: String = "",
    val isPolar: Boolean = false
)

data class DevicePrefsSnapshot(
    val savedPolarId: String,
    val savedOximeterAddress: String,
    val meditationAudioDirUri: String = ""
) {
    val savedH10Id: String get() = savedPolarId
    val savedVerityId: String get() = ""
}
