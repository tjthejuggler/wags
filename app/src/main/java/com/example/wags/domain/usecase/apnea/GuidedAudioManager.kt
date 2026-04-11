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
        stopPlayback()
        val uriStr = _cachedUri
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
}
