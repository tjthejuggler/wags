package com.example.wags.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.db.dao.MeditationAudioDao
import com.example.wags.data.db.dao.MeditationSessionDao
import com.example.wags.data.db.dao.MeditationTelemetryDao
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.db.entity.MeditationTelemetryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeditationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioDao: MeditationAudioDao,
    private val sessionDao: MeditationSessionDao,
    private val telemetryDao: MeditationTelemetryDao,
    private val devicePrefs: DevicePreferencesRepository,
    private val youtubeFetcher: YouTubeMetadataFetcher
) {

    // ── Audio directory preference ─────────────────────────────────────────────

    fun getAudioDirUri(): String = devicePrefs.meditationAudioDirUri

    fun setAudioDirUri(uriString: String) {
        devicePrefs.meditationAudioDirUri = uriString
        devicePrefs.refresh()
    }

    // ── Audio list ─────────────────────────────────────────────────────────────

    fun observeAudios(): Flow<List<MeditationAudioEntity>> = audioDao.observeAll()

    suspend fun getAudioById(id: Long): MeditationAudioEntity? = audioDao.getById(id)

    /**
     * Returns all distinct YouTube channel names present in the DB, sorted alphabetically.
     * Used to populate the filter chip row.
     */
    suspend fun getDistinctChannels(): List<String> = audioDao.getDistinctChannels()

    /**
     * Scans the SAF directory at [dirUriString], syncs the DB:
     *  - Inserts new audio files found on disk.
     *  - Removes DB rows whose files no longer exist.
     *  - Ensures the "None" sentinel row exists.
     *
     * Returns the refreshed list of audio entities.
     */
    suspend fun syncAudioDirectory(dirUriString: String): List<MeditationAudioEntity> {
        // 1. Ensure the "None" sentinel exists
        if (audioDao.getNoneEntry() == null) {
            audioDao.insert(MeditationAudioEntity(fileName = "None", isNone = true))
        }

        if (dirUriString.isBlank()) return audioDao.getAll()

        val dirUri = Uri.parse(dirUriString)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dirUri,
            DocumentsContract.getTreeDocumentId(dirUri)
        )

        val foundFileNames = mutableListOf<String>()

        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: continue
                    if (mime.startsWith("audio/") || isAudioExtension(name)) {
                        foundFileNames.add(name)
                        // Insert if not already present
                        if (audioDao.getByFileName(name) == null) {
                            audioDao.insert(MeditationAudioEntity(fileName = name))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Directory may have been revoked; return what we have
        }

        // Remove stale entries (files no longer in the directory)
        if (foundFileNames.isNotEmpty()) {
            audioDao.deleteStale(foundFileNames)
        }

        return audioDao.getAll()
    }

    /**
     * Updates the source URL for an audio entry.
     * If the URL is a YouTube link, automatically fetches the video title and channel name
     * via the oEmbed API and stores them alongside the URL.
     * If the URL is cleared or is not a YouTube URL, clears any previously stored metadata.
     *
     * NOTE: This performs a network call — must be called from an IO coroutine.
     */
    suspend fun updateAudioUrl(audioId: Long, url: String) {
        val entity = audioDao.getById(audioId) ?: return
        val trimmed = url.trim()

        val (title, channel) = if (trimmed.isNotBlank() && youtubeFetcher.isYouTubeUrl(trimmed)) {
            val meta = youtubeFetcher.fetch(trimmed)
            Pair(meta?.title, meta?.channel)
        } else {
            Pair(null, null)
        }

        audioDao.update(
            entity.copy(
                sourceUrl      = trimmed,
                youtubeTitle   = title,
                youtubeChannel = channel
            )
        )
    }

    /**
     * Fetches YouTube metadata for the given URL without persisting anything.
     * Returns null if the URL is not a YouTube URL or the fetch fails.
     * Must be called from an IO coroutine.
     */
    suspend fun fetchYouTubeMetadata(url: String): YouTubeMetadataFetcher.YoutubeMetadata? =
        if (youtubeFetcher.isYouTubeUrl(url)) youtubeFetcher.fetch(url) else null

    // ── Sessions ───────────────────────────────────────────────────────────────

    fun observeSessions(): Flow<List<MeditationSessionEntity>> = sessionDao.observeAll()

    suspend fun getAllSessions(): List<MeditationSessionEntity> = sessionDao.getAll()

    suspend fun getSessionById(id: Long): MeditationSessionEntity? = sessionDao.getById(id)

    suspend fun insertSession(session: MeditationSessionEntity): Long =
        sessionDao.insert(session)

    suspend fun deleteSessionById(id: Long) = sessionDao.deleteById(id)

    // ── Telemetry ──────────────────────────────────────────────────────────────

    suspend fun insertTelemetry(rows: List<MeditationTelemetryEntity>) =
        telemetryDao.insertAll(rows)

    suspend fun getTelemetryForSession(sessionId: Long): List<MeditationTelemetryEntity> =
        telemetryDao.getBySessionId(sessionId)

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun isAudioExtension(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".ogg") || lower.endsWith(".wav") ||
            lower.endsWith(".flac") || lower.endsWith(".aac") ||
            lower.endsWith(".opus")
    }
}
