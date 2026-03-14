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
 * Persists BLE device identifiers and time-of-day default device preferences.
 *
 * Morning window : 03:00 – 10:59  (3 am to 11 am)
 * Day window     : 11:00 – 02:59  (11 am to 3 am next day)
 *
 * Each "default slot" stores:
 *   - deviceId   : Polar device ID  OR  BLE MAC address for oximeter
 *   - deviceType : "h10" | "verity" | "oximeter"
 */
@Singleton
class DevicePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Last-connected device IDs (used for reconnect after disconnect) ────────

    var savedH10Id: String
        get() = prefs.getString(KEY_H10_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_H10_ID, value).apply()

    var savedVerityId: String
        get() = prefs.getString(KEY_VERITY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VERITY_ID, value).apply()

    var savedOximeterAddress: String
        get() = prefs.getString(KEY_OXIMETER_ADDR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OXIMETER_ADDR, value).apply()

    // ── Morning default (3 am – 11 am) ────────────────────────────────────────

    var morningDeviceId: String
        get() = prefs.getString(KEY_MORNING_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MORNING_ID, value).apply()

    var morningDeviceType: String          // "h10" | "verity" | "oximeter" | ""
        get() = prefs.getString(KEY_MORNING_TYPE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MORNING_TYPE, value).apply()

    var morningDeviceName: String
        get() = prefs.getString(KEY_MORNING_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MORNING_NAME, value).apply()

    // ── Day default (11 am – 3 am) ────────────────────────────────────────────

    var dayDeviceId: String
        get() = prefs.getString(KEY_DAY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DAY_ID, value).apply()

    var dayDeviceType: String              // "h10" | "verity" | "oximeter" | ""
        get() = prefs.getString(KEY_DAY_TYPE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DAY_TYPE, value).apply()

    var dayDeviceName: String
        get() = prefs.getString(KEY_DAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DAY_NAME, value).apply()

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
        savedH10Id           = savedH10Id,
        savedVerityId        = savedVerityId,
        savedOximeterAddress = savedOximeterAddress,
        morningDeviceId      = morningDeviceId,
        morningDeviceType    = morningDeviceType,
        morningDeviceName    = morningDeviceName,
        dayDeviceId          = dayDeviceId,
        dayDeviceType        = dayDeviceType,
        dayDeviceName        = dayDeviceName,
        meditationAudioDirUri = meditationAudioDirUri
    )

    companion object {
        const val PREFS_NAME              = "wags_device_prefs"
        const val KEY_H10_ID              = "h10_device_id"
        const val KEY_VERITY_ID           = "verity_device_id"
        const val KEY_OXIMETER_ADDR       = "oximeter_address"
        const val KEY_MORNING_ID          = "morning_device_id"
        const val KEY_MORNING_TYPE        = "morning_device_type"
        const val KEY_MORNING_NAME        = "morning_device_name"
        const val KEY_DAY_ID              = "day_device_id"
        const val KEY_DAY_TYPE            = "day_device_type"
        const val KEY_DAY_NAME            = "day_device_name"
        const val KEY_MEDITATION_DIR_URI  = "meditation_audio_dir_uri"

        /** Returns true if the current hour falls in the morning window (3–10 inclusive). */
        fun isMorningWindow(): Boolean {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return hour in 3..10
        }
    }
}

data class DevicePrefsSnapshot(
    val savedH10Id: String,
    val savedVerityId: String,
    val savedOximeterAddress: String,
    val morningDeviceId: String,
    val morningDeviceType: String,
    val morningDeviceName: String,
    val dayDeviceId: String,
    val dayDeviceType: String,
    val dayDeviceName: String,
    val meditationAudioDirUri: String = ""
)
