package com.example.wags.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.db.WagsDatabase
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
    private val db: WagsDatabase,
    private val audioDao: MeditationAudioDao,
    private val sessionDao: MeditationSessionDao,
    private val telemetryDao: MeditationTelemetryDao,
    private val devicePrefs: DevicePreferencesRepository,
    private val youtubeFetcher: YouTubeMetadataFetcher,
    private val youtubeImporter: YoutubeAudioImporter
) {

    private companion object {
        /**
         * Incomplete sessions younger than this are never touched by recovery —
         * see [recoverOrphanedSessions].
         */
        private const val STALE_SESSION_CUTOFF_MS = 10 * 60 * 1000L // 10 minutes

        /**
         * Guided-apnea YouTube imports are downloaded into this subfolder of
         * the audio folder (same folder the user already keeps guided MP3s
         * in). The folder scanner only looks at direct children, so these
         * files never leak into the meditation picker.
         */
        private const val GUIDED_APNEA_SUBFOLDER = "apnea_guided"
    }

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

    /**
     * Downloads the audio of a YouTube video on-device into the configured
     * meditation audio directory (SAF tree) and registers it in the DB with
     * full metadata (title / channel / source URL) — the shared-to-Wags
     * import flow. The audio immediately appears in the picker.
     *
     * @param onEvent progress callback (invoked on the IO dispatcher).
     * @throws YoutubeAudioImporter.ImportException on failure, with a
     *         user-presentable message.
     */
    suspend fun importYoutubeAudio(
        url: String,
        onEvent: (YoutubeAudioImporter.ImportEvent) -> Unit
    ): MeditationAudioEntity =
        youtubeImporter.import(url, requireAudioDirUri(), onEvent)

    /**
     * Downloads the audio of a guided-apnea YouTube video into the
     * [GUIDED_APNEA_SUBFOLDER] subfolder of the audio folder WITHOUT any DB
     * registration — the caller registers it in the guided audio library
     * (see [com.example.wags.domain.usecase.apnea.GuidedAudioManager]).
     */
    suspend fun downloadGuidedApneaAudio(
        url: String,
        onEvent: (YoutubeAudioImporter.ImportEvent) -> Unit
    ): YoutubeAudioImporter.DownloadedAudio =
        youtubeImporter.downloadToSubfolder(url, requireAudioDirUri(), GUIDED_APNEA_SUBFOLDER, onEvent)

    private fun requireAudioDirUri(): String {
        val dirUriString = getAudioDirUri()
        if (dirUriString.isBlank()) {
            throw YoutubeAudioImporter.ImportException(
                "No meditation audio folder set. " +
                    "Choose one in Settings → Meditation Audio Directory first."
            )
        }
        return dirUriString
    }

    // ── Sessions ───────────────────────────────────────────────────────────────

    fun observeSessions(): Flow<List<MeditationSessionEntity>> = sessionDao.observeAll()

    suspend fun getAllSessions(): List<MeditationSessionEntity> = sessionDao.getAll()

    suspend fun getSessionById(id: Long): MeditationSessionEntity? = sessionDao.getById(id)

    suspend fun insertSession(session: MeditationSessionEntity): Long =
        sessionDao.insert(session)

    suspend fun deleteSessionById(id: Long) = sessionDao.deleteById(id)

    suspend fun updateSessionDuration(id: Long, durationMs: Long) =
        sessionDao.updateDurationMs(id, durationMs)

    /** Updates an existing session row (does NOT trigger CASCADE deletes on telemetry). */
    suspend fun updateSession(session: MeditationSessionEntity) =
        sessionDao.update(session)

    // ── Incremental persistence & crash recovery ──────────────────────────────

    /** Returns the most recent in-progress session, or null if none exists. */
    suspend fun getMostRecentIncompleteSession(): MeditationSessionEntity? =
        sessionDao.getMostRecentIncompleteSession()

    /** Marks a session as finalized with the given duration. */
    suspend fun finalizeSession(id: Long, durationMs: Long) =
        sessionDao.finalizeSession(id, durationMs)

    /** Deletes all telemetry rows for a session (used when replacing with a cleaner set). */
    suspend fun deleteTelemetryForSession(sessionId: Long) =
        telemetryDao.deleteBySessionId(sessionId)

    /**
     * Recovers sessions that were interrupted by process death.
     *
     * - Sessions shorter than 5 seconds with no meaningful data are deleted.
     * - Longer sessions are finalized with their last-known duration.
     *
     * Should be called on app / screen startup before starting a new session.
     */
    suspend fun recoverOrphanedSessions() {
        try {
            val now = System.currentTimeMillis()
            // Delete tiny accidental sessions (< 5 s) that are also STALE.  The
            // recency cutoff is critical: a session row created moments ago also
            // has durationMs = 0 / completed = 0, and deleting it mid-session
            // orphans all telemetry inserts (FK violation) and loses the session.
            sessionDao.deleteIncompleteShorterThan(5_000L, now - STALE_SESSION_CUTOFF_MS)

            val orphaned = sessionDao.getIncompleteSessions()
            var recoveredCount = 0
            for (session in orphaned) {
                // Only recover sessions that are truly stale — i.e. the Service is no
                // longer updating them.  The Service flushes every 15 s, so if the gap
                // between the expected end time and now exceeds 60 s, the process was
                // almost certainly killed.
                val expectedDuration = now - session.timestamp
                val gap = expectedDuration - session.durationMs
                if (gap > 60_000L) {
                    val finalDuration = if (session.durationMs > 0) {
                        session.durationMs
                    } else {
                        expectedDuration
                    }
                    sessionDao.finalizeSession(session.sessionId, finalDuration)
                    recoveredCount++
                }
            }
            if (recoveredCount > 0) {
                android.util.Log.i(
                    "MeditationRepository",
                    "Recovered $recoveredCount orphaned meditation session(s)"
                )
            }
        } catch (e: Exception) {
            // Recovery must never crash the caller (it runs in ViewModel init).
            android.util.Log.e("MeditationRepository", "Orphan recovery failed", e)
        }
    }

    /**
     * Atomically finalises an existing session row and replaces its telemetry.
     *
     * Runs the UPDATE + DELETE + INSERT inside a single Room transaction so the
     * process can never die between "delete old telemetry" and "insert new
     * telemetry" (which would lose the session's data).
     *
     * @param updated the session row copy with final analytics and completed = true.
     * @param telemetry the authoritative full telemetry set for the session.
     * @return the session row ID.
     */
    suspend fun finalizeSessionWithTelemetry(
        updated: MeditationSessionEntity,
        telemetry: List<MeditationTelemetryEntity>
    ): Long = db.withTransaction {
        sessionDao.update(updated)
        telemetryDao.deleteBySessionId(updated.sessionId)
        if (telemetry.isNotEmpty()) {
            telemetryDao.insertAll(telemetry.map { it.copy(sessionId = updated.sessionId) })
        }
        updated.sessionId
    }

    /**
     * Atomically inserts a fresh completed session together with its telemetry
     * (fallback path when no pre-existing incomplete row is found).
     */
    suspend fun insertSessionWithTelemetry(
        session: MeditationSessionEntity,
        telemetry: List<MeditationTelemetryEntity>
    ): Long = db.withTransaction {
        val id = sessionDao.insert(session)
        if (telemetry.isNotEmpty()) {
            telemetryDao.insertAll(telemetry.map { it.copy(sessionId = id) })
        }
        id
    }

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
