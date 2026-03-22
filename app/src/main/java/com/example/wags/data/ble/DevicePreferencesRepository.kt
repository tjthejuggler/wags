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
 * Three device types are tracked:
 *   - H10 chest strap (Polar device ID)
 *   - Verity Sense optical HR (Polar device ID)
 *   - Oximeter / SpO₂ sensor (BLE MAC address)
 *
 * For each type we keep an **ordered history list** (most-recently-connected
 * first, capped at [MAX_HISTORY]) so the auto-connect loop can cycle through
 * every device the user has ever paired, not just the last one.
 *
 * Convenience single-value accessors ([savedH10Id], [savedVerityId],
 * [savedOximeterAddress]) still work — they return the head of the list.
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

    // ── Convenience single-value accessors (head of each history list) ─────────

    /** The most-recently-connected H10 device ID, or "" if none. */
    var savedH10Id: String
        get() = getHistory(KEY_H10_HISTORY).firstOrNull() ?: ""
        set(value) = addToHistory(KEY_H10_HISTORY, value)

    /** The most-recently-connected Verity Sense device ID, or "" if none. */
    var savedVerityId: String
        get() = getHistory(KEY_VERITY_HISTORY).firstOrNull() ?: ""
        set(value) = addToHistory(KEY_VERITY_HISTORY, value)

    /** The most-recently-connected oximeter MAC address, or "" if none. */
    var savedOximeterAddress: String
        get() = getHistory(KEY_OXIMETER_HISTORY).firstOrNull() ?: ""
        set(value) = addToHistory(KEY_OXIMETER_HISTORY, value)

    // ── History list accessors used by AutoConnectManager ─────────────────────

    val h10History: List<String>        get() = getHistory(KEY_H10_HISTORY)
    val verityHistory: List<String>     get() = getHistory(KEY_VERITY_HISTORY)
    val oximeterHistory: List<String>   get() = getHistory(KEY_OXIMETER_HISTORY)

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
        savedH10Id            = savedH10Id,
        savedVerityId         = savedVerityId,
        savedOximeterAddress  = savedOximeterAddress,
        meditationAudioDirUri = meditationAudioDirUri
    )

    companion object {
        const val PREFS_NAME             = "wags_device_prefs"

        // History list keys (replacing the old single-value keys)
        const val KEY_H10_HISTORY        = "h10_device_history"
        const val KEY_VERITY_HISTORY     = "verity_device_history"
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
         * Bump the value in [migrateLabelPrefixes] when adding a new migration.
         */
        private const val KEY_LABEL_MIGRATION_VERSION = "device_label_migration_version"

        /** Maximum number of devices remembered per type. */
        const val MAX_HISTORY = 10

        private const val SEPARATOR = "|"
    }

    // ── One-time migration from legacy single-value keys ──────────────────────

    init {
        migrateLegacyKeys()
        migrateLabelPrefixes()
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

        // Only migrate if the new history key doesn't exist yet
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
     * Labels will be re-populated with the device's own advertised name the
     * next time each device connects.
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
}

data class DevicePrefsSnapshot(
    val savedH10Id: String,
    val savedVerityId: String,
    val savedOximeterAddress: String,
    val meditationAudioDirUri: String = ""
)
