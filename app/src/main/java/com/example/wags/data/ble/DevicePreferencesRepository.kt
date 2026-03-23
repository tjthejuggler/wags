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
 * Two device categories are tracked:
 *   - Polar devices (H10, Verity Sense — identified by name after connection)
 *   - Oximeter / SpO₂ sensor (BLE MAC address)
 *
 * For each category we keep an **ordered history list** (most-recently-connected
 * first, capped at [MAX_HISTORY]) so the auto-connect loop can cycle through
 * every device the user has ever paired, not just the last one.
 *
 * Device type (H10 vs Verity Sense) is determined automatically from the
 * device name after connection — not stored as a preference.
 */
@Singleton
class DevicePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── History lists ──────────────────────────────────────────────────────────

    /**
     * Returns the full history for [key], most-recently-connected first.
     * Stored as a pipe-separated string: "ID1|ID2|ID3".
     */
    fun getHistory(key: String): List<String> {
        val raw = prefs.getString(key, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /**
     * Prepends [id] to the history for [key], deduplicates, and caps at
     * [MAX_HISTORY] entries. The most-recently-connected device is always first.
     */
    fun addToHistory(key: String, id: String) {
        if (id.isBlank()) return
        val updated = (listOf(id) + getHistory(key))
            .distinct()
            .take(MAX_HISTORY)
        prefs.edit().putString(key, updated.joinToString(SEPARATOR)).apply()
        refresh()
    }

    // ── Polar device history (unified — H10 + Verity Sense) ───────────────────

    /** All known Polar device IDs, most-recently-connected first. */
    val polarHistory: List<String> get() = getHistory(KEY_POLAR_HISTORY)

    /** The most-recently-connected Polar device ID, or "" if none. */
    var savedPolarId: String
        get() = getHistory(KEY_POLAR_HISTORY).firstOrNull() ?: ""
        set(value) = addToHistory(KEY_POLAR_HISTORY, value)

    // ── Legacy accessors (kept for backward compatibility) ─────────────────────

    /** @deprecated Use [savedPolarId] instead. Returns the first Polar device. */
    var savedH10Id: String
        get() = savedPolarId
        set(value) { savedPolarId = value }

    /** @deprecated Use [savedPolarId] instead. Returns the first Polar device. */
    var savedVerityId: String
        get() {
            // Return the second Polar device if available, otherwise ""
            val history = polarHistory
            return if (history.size > 1) history[1] else ""
        }
        set(value) { savedPolarId = value }

    // ── Oximeter history ──────────────────────────────────────────────────────

    /** The most-recently-connected oximeter MAC address, or "" if none. */
    var savedOximeterAddress: String
        get() = getHistory(KEY_OXIMETER_HISTORY).firstOrNull() ?: ""
        set(value) = addToHistory(KEY_OXIMETER_HISTORY, value)

    val oximeterHistory: List<String> get() = getHistory(KEY_OXIMETER_HISTORY)

    // ── Device label history (human-readable names for the edit dropdown) ─────

    /**
     * All device labels ever used, most-recently-connected first.
     * Labels are the device's own advertised name (no artificial type prefix),
     * e.g. "Polar H10 A1B2C3D4", "PC-60F", "Garmin Watch".
     */
    val deviceLabelHistory: List<String> get() = getHistory(KEY_DEVICE_LABEL_HISTORY)

    /**
     * Record a device label so it appears in the edit dropdown for past records.
     * Call this whenever a device is used for a hold or session.
     */
    fun recordDeviceLabel(label: String) {
        addToHistory(KEY_DEVICE_LABEL_HISTORY, label)
    }

    // ── Meditation audio directory ─────────────────────────────────────────────

    /** URI string of the user-chosen meditation audio folder (SAF persistent URI), or "". */
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

    companion object {
        const val PREFS_NAME             = "wags_device_prefs"

        // Unified Polar device history (replaces separate H10 + Verity lists)
        const val KEY_POLAR_HISTORY      = "polar_device_history"

        // Legacy keys (kept for migration)
        private const val KEY_H10_HISTORY        = "h10_device_history"
        private const val KEY_VERITY_HISTORY     = "verity_device_history"
        const val KEY_OXIMETER_HISTORY   = "oximeter_address_history"

        // Human-readable device label history (for the edit dropdown on past records)
        const val KEY_DEVICE_LABEL_HISTORY = "device_label_history"

        // Legacy single-value keys — kept so we can migrate on first run
        private const val KEY_H10_ID_LEGACY       = "h10_device_id"
        private const val KEY_VERITY_ID_LEGACY    = "verity_device_id"
        private const val KEY_OXIMETER_ADDR_LEGACY = "oximeter_address"

        const val KEY_MEDITATION_DIR_URI = "meditation_audio_dir_uri"

        /**
         * Tracks which one-time migrations have already run.
         */
        private const val KEY_LABEL_MIGRATION_VERSION = "device_label_migration_version"

        /** Maximum number of devices remembered per type. */
        const val MAX_HISTORY = 10

        private const val SEPARATOR = "|"
    }

    // ── One-time migrations ───────────────────────────────────────────────────

    init {
        migrateLegacyKeys()
        migrateLabelPrefixes()
        migrateH10VerityToUnifiedPolar()
    }

    /**
     * If the app was previously installed with the old single-value keys, migrate
     * those values into the new history lists so existing users don't lose their
     * saved devices on upgrade.
     */
    private fun migrateLegacyKeys() {
        val legacyH10 = prefs.getString(KEY_H10_ID_LEGACY, "") ?: ""
        val legacyVerity = prefs.getString(KEY_VERITY_ID_LEGACY, "") ?: ""
        val legacyOximeter = prefs.getString(KEY_OXIMETER_ADDR_LEGACY, "") ?: ""

        // Migrate legacy single-value keys to history lists
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
     * artificial type prefixes ("Polar H10 · …", "Oximeter · …").
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
     * One-time migration: merge the old separate H10 and Verity history lists
     * into the new unified Polar history list.
     */
    private fun migrateH10VerityToUnifiedPolar() {
        if (getHistory(KEY_POLAR_HISTORY).isNotEmpty()) return  // already migrated

        val h10Devices = getHistory(KEY_H10_HISTORY)
        val verityDevices = getHistory(KEY_VERITY_HISTORY)
        val merged = (h10Devices + verityDevices).distinct().take(MAX_HISTORY)

        if (merged.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_POLAR_HISTORY, merged.joinToString(SEPARATOR))
                .apply()
            refresh()
        }
    }
}

data class DevicePrefsSnapshot(
    val savedPolarId: String,
    val savedOximeterAddress: String,
    val meditationAudioDirUri: String = ""
) {
    // Backward compatibility aliases
    val savedH10Id: String get() = savedPolarId
    val savedVerityId: String get() = ""
}
