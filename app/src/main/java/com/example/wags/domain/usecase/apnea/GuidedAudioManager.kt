package com.example.wags.domain.usecase.apnea

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import com.example.wags.data.db.dao.GuidedAudioDao
import com.example.wags.data.db.entity.GuidedAudioEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Singleton that manages a DB-backed guided-audio library and MediaPlayer
 * playback for apnea sessions.
 *
 * The library of all guided audios lives in the `guided_audios` Room table.
 * Only the **currently selected** audio ID is stored in SharedPreferences.
 */
@Singleton
class GuidedAudioManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @Named("apnea_prefs") private val prefs: SharedPreferences,
    private val guidedAudioDao: GuidedAudioDao
) {

    companion object {
        private const val PREF_SELECTED_ID = "guided_audio_selected_id"
        // Per-MP3 setting key prefixes (keyed by audioId)
        private const val PREF_PREFIX_RELAXED  = "guided_mp3_relaxed_"
        private const val PREF_PREFIX_PURGE    = "guided_mp3_purge_"
        private const val PREF_PREFIX_TRANS    = "guided_mp3_trans_"
        private const val PREF_PREFIX_START_MP3 = "guided_mp3_start_with_hyper_"
    }

    private var mediaPlayer: MediaPlayer? = null

    /** Flow of all guided audios in the library. */
    val allAudios: Flow<List<GuidedAudioEntity>> = guidedAudioDao.observeAll()

    /** The ID of the currently selected guided audio. -1 if none selected. */
    val selectedId: Long get() = prefs.getLong(PREF_SELECTED_ID, -1L)

    /** Whether a guided audio is currently selected. */
    val hasSelection: Boolean get() = selectedId > 0

    /** Select a guided audio by ID for the next session. */
    fun selectAudio(id: Long) {
        prefs.edit().putLong(PREF_SELECTED_ID, id).apply()
    }

    /** Clear the current selection. */
    fun clearSelection() {
        prefs.edit().remove(PREF_SELECTED_ID).apply()
        stopPlayback()
    }

    /** Add a new guided audio to the library and return its ID. */
    suspend fun addAudio(fileName: String, uri: String, sourceUrl: String = ""): Long {
        return guidedAudioDao.insert(
            GuidedAudioEntity(
                fileName = fileName,
                uri = uri,
                sourceUrl = sourceUrl
            )
        )
    }

    /** Delete a guided audio from the library. If it was selected, clear the selection. */
    suspend fun deleteAudio(id: Long) {
        if (selectedId == id) clearSelection()
        guidedAudioDao.deleteById(id)
    }

    /** Get the currently selected audio entity, or null if none selected or not found. */
    suspend fun getSelectedAudio(): GuidedAudioEntity? {
        val id = selectedId
        if (id <= 0) return null
        return guidedAudioDao.getById(id)
    }

    /** Get the display name of the currently selected audio. */
    suspend fun getSelectedName(): String {
        return getSelectedAudio()?.fileName ?: ""
    }

    /** Cache the URI before starting playback (called from ViewModel coroutine). */
    suspend fun preparePlayback() {
        _cachedUri = getSelectedAudio()?.uri
    }

    private var _cachedUri: String? = null

    fun startPlayback() {
        val uriStr = _cachedUri
        stopPlayback()
        if (uriStr.isNullOrBlank()) return
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(appContext, Uri.parse(uriStr))
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.w("GuidedAudioManager", "Failed to play guided audio", e)
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        _cachedUri = null
    }

    /** Returns true if audio is currently playing. */
    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true

    // ── Per-MP3 hyper settings ───────────────────────────────────────────────

    /**
     * Settings remembered per guided MP3 for the guided hyperventilation feature.
     */
    data class PerMp3HyperSettings(
        val relaxedExhaleSec: Int = 0,
        val purgeExhaleSec: Int = 0,
        val transitionSec: Int = 0,
        val startMp3WithHyper: Boolean = false
    )

    /** Load the per-MP3 hyper settings for the given audio ID. */
    fun getPerMp3HyperSettings(audioId: Long): PerMp3HyperSettings {
        if (audioId <= 0) return PerMp3HyperSettings()
        return PerMp3HyperSettings(
            relaxedExhaleSec = prefs.getInt("$PREF_PREFIX_RELAXED$audioId", -1),
            purgeExhaleSec   = prefs.getInt("$PREF_PREFIX_PURGE$audioId", -1),
            transitionSec    = prefs.getInt("$PREF_PREFIX_TRANS$audioId", -1),
            startMp3WithHyper = prefs.getBoolean("$PREF_PREFIX_START_MP3$audioId", false)
        ).let { raw ->
            // -1 means "never saved for this MP3" — return defaults
            if (raw.relaxedExhaleSec < 0 && raw.purgeExhaleSec < 0 && raw.transitionSec < 0) {
                PerMp3HyperSettings()
            } else {
                raw.copy(
                    relaxedExhaleSec = raw.relaxedExhaleSec.coerceAtLeast(0),
                    purgeExhaleSec   = raw.purgeExhaleSec.coerceAtLeast(0),
                    transitionSec    = raw.transitionSec.coerceAtLeast(0)
                )
            }
        }
    }

    /** Check whether this MP3 has ever had per-MP3 hyper settings saved. */
    fun hasPerMp3HyperSettings(audioId: Long): Boolean {
        if (audioId <= 0) return false
        return prefs.contains("$PREF_PREFIX_RELAXED$audioId")
    }

    /** Save a single per-MP3 hyper setting. */
    fun saveRelaxedExhale(audioId: Long, sec: Int) {
        if (audioId <= 0) return
        prefs.edit().putInt("$PREF_PREFIX_RELAXED$audioId", sec).apply()
    }

    fun savePurgeExhale(audioId: Long, sec: Int) {
        if (audioId <= 0) return
        prefs.edit().putInt("$PREF_PREFIX_PURGE$audioId", sec).apply()
    }

    fun saveTransitionSec(audioId: Long, sec: Int) {
        if (audioId <= 0) return
        prefs.edit().putInt("$PREF_PREFIX_TRANS$audioId", sec).apply()
    }

    fun saveStartMp3WithHyper(audioId: Long, enabled: Boolean) {
        if (audioId <= 0) return
        prefs.edit().putBoolean("$PREF_PREFIX_START_MP3$audioId", enabled).apply()
    }
}
