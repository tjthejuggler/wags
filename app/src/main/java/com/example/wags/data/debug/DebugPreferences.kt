package com.example.wags.data.debug

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists debug-mode settings: whether the floating bubble is enabled
 * and the user-chosen directory URI for [debug_wags.json].
 */
@Singleton
class DebugPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var debugModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()
            refresh()
        }

    /** User-chosen directory URI (from SAF folder picker) for debug_wags.json. */
    var debugFileDirUri: String
        get() = prefs.getString(KEY_DEBUG_DIR_URI, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DEBUG_DIR_URI, value).apply()
            refresh()
        }

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<DebugPrefsSnapshot> = _snapshot.asStateFlow()

    fun refresh() {
        _snapshot.value = buildSnapshot()
    }

    private fun buildSnapshot() = DebugPrefsSnapshot(
        debugModeEnabled = debugModeEnabled,
        debugFileDirUri = debugFileDirUri
    )

    companion object {
        private const val PREFS_NAME = "wags_debug_prefs"
        private const val KEY_DEBUG_MODE = "debug_mode_enabled"
        private const val KEY_DEBUG_DIR_URI = "debug_dir_uri"
    }
}

data class DebugPrefsSnapshot(
    val debugModeEnabled: Boolean = false,
    val debugFileDirUri: String = ""
)
