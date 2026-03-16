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
 * Three device slots are tracked:
 *   - H10 chest strap (Polar device ID)
 *   - Verity Sense optical HR (Polar device ID)
 *   - Oximeter / SpO₂ sensor (BLE MAC address)
 *
 * Any device that has ever been manually connected from Settings is saved here
 * and will be auto-connected on subsequent app launches.
 */
@Singleton
class DevicePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Last-connected device IDs ──────────────────────────────────────────────

    var savedH10Id: String
        get() = prefs.getString(KEY_H10_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_H10_ID, value).apply()

    var savedVerityId: String
        get() = prefs.getString(KEY_VERITY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VERITY_ID, value).apply()

    var savedOximeterAddress: String
        get() = prefs.getString(KEY_OXIMETER_ADDR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OXIMETER_ADDR, value).apply()

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
        const val KEY_H10_ID             = "h10_device_id"
        const val KEY_VERITY_ID          = "verity_device_id"
        const val KEY_OXIMETER_ADDR      = "oximeter_address"
        const val KEY_MEDITATION_DIR_URI = "meditation_audio_dir_uri"
    }
}

data class DevicePrefsSnapshot(
    val savedH10Id: String,
    val savedVerityId: String,
    val savedOximeterAddress: String,
    val meditationAudioDirUri: String = ""
)
