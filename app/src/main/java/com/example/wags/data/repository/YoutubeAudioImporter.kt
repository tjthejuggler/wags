package com.example.wags.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.wags.data.db.dao.MeditationAudioDao
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the audio track of a YouTube video directly on-device into the
 * meditation audio SAF folder and registers it in the DB with full metadata
 * (title / channel / source URL).
 *
 * Stream resolution is done with the NewPipe Extractor (the same library that
 * powers the NewPipe app); the actual bytes are downloaded with OkHttp and
 * streamed straight into the SAF document — no storage permissions needed.
 *
 * The saved file is the native audio stream (usually .m4a, which both the
 * folder scanner in [MeditationRepository.syncAudioDirectory] and the
 * MediaPlayer playback path accept).
 */
@Singleton
class YoutubeAudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioDao: MeditationAudioDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Events emitted during an import, for UI progress display. */
    sealed interface ImportEvent {
        /** Resolving the video / stream metadata. */
        data object Resolving : ImportEvent

        /** Stream resolved; contains what we are about to download. */
        data class Resolved(
            val title: String,
            val channel: String?,
            val durationSeconds: Long
        ) : ImportEvent

        /** Byte-level download progress. [totalBytes] is -1 when unknown. */
        data class Progress(val bytesDownloaded: Long, val totalBytes: Long) : ImportEvent
    }

    /** User-presentable import failure. */
    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Imports [videoUrl] into the SAF directory [dirUriString].
     *
     * @param onEvent invoked (on the IO dispatcher) with progress updates.
     * @return the persisted DB entity for the downloaded audio.
     * @throws ImportException on any failure; partial files are cleaned up.
     */
    /** A fully downloaded audio file, not yet registered in any DB table. */
    data class DownloadedAudio(
        val docUri: Uri,
        val fileName: String,
        val title: String,
        val channel: String?
    )

    suspend fun import(
        videoUrl: String,
        dirUriString: String,
        onEvent: (ImportEvent) -> Unit
    ): MeditationAudioEntity {
        val downloaded = download(videoUrl, dirUriString, null, onEvent)

        // Persist the DB entry with full metadata
        val entity = MeditationAudioEntity(
            fileName = downloaded.fileName,
            sourceUrl = videoUrl,
            youtubeTitle = downloaded.title,
            youtubeChannel = downloaded.channel
        )
        val existing = audioDao.getByFileName(downloaded.fileName)
        return if (existing != null) {
            val updated = entity.copy(audioId = existing.audioId)
            audioDao.update(updated)
            updated
        } else {
            entity.copy(audioId = audioDao.insert(entity))
        }
    }

    /**
     * Downloads the audio into `subdirectory` of the SAF tree (created on
     * demand) WITHOUT registering it anywhere — the caller decides which
     * library the file belongs to.
     */
    suspend fun downloadToSubfolder(
        videoUrl: String,
        dirUriString: String,
        subdirectory: String,
        onEvent: (ImportEvent) -> Unit
    ): DownloadedAudio = download(videoUrl, dirUriString, subdirectory, onEvent)

    private suspend fun download(
        videoUrl: String,
        dirUriString: String,
        subdirectory: String?,
        onEvent: (ImportEvent) -> Unit
    ): DownloadedAudio = withContext(ioDispatcher) {
        onEvent(ImportEvent.Resolving)
        ensureInitialized()

        // 1. Resolve video + stream metadata
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ImportException(friendlyError(e), e)
        }

        val stream = selectBestAudioStream(info)
            ?: throw ImportException(
                "No downloadable audio stream found. The video may be a live stream, " +
                    "DRM-protected, or region-blocked. Try the desktop fallback (wags-audio)."
            )

        val title = info.name?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube Audio"
        val channel = info.uploaderName?.trim().takeUnless { it.isNullOrBlank() }
        val durationSeconds = info.duration

        onEvent(ImportEvent.Resolved(title, channel, durationSeconds))

        // 2. Resolve the target directory (root or a subfolder of the tree)
        val treeUri = Uri.parse(dirUriString)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val parentDocUri = if (subdirectory.isNullOrBlank()) {
            rootDocUri
        } else {
            ensureSubdirectory(treeUri, rootDocUri, subdirectory)
        }

        // Build a unique file name inside the target directory
        val extension = extensionFor(stream)
        val baseName = sanitizeFileName(title)
        val existingNames = listChildFileNames(treeUri, parentDocUri)

        var candidate = "$baseName.$extension"
        var counter = 1
        while (candidate in existingNames) {
            candidate = "${baseName} ($counter).$extension"
            counter++
        }

        // 3. Create the document and stream the audio into it
        val docUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeTypeFor(extension),
                candidate
            )
        } catch (e: SecurityException) {
            throw ImportException(
                "The app only has read access to the audio folder. Re-select the folder " +
                    "once in Settings → Meditation Audio Directory to grant write access.", e
            )
        } catch (e: Exception) {
            throw ImportException(
                "Could not create the file in the meditation audio folder.", e
            )
        } ?: throw ImportException(
            "Could not create the file in the meditation audio folder."
        )

        try {
            downloadTo(stream.content, docUri, onEvent)
        } catch (e: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }
            throw e
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }
            if (e is ImportException) throw e
            throw ImportException("Download failed: ${e.message ?: "network error"}", e)
        }

        // The provider may have de-duplicated the requested name — read it back.
        val actualName = queryDisplayName(docUri) ?: candidate

        DownloadedAudio(
            docUri = docUri,
            fileName = actualName,
            title = title,
            channel = channel
        )
    }

    // ── Stream selection ───────────────────────────────────────────────────────

    /**
     * Picks the best progressive (directly downloadable) audio stream.
     * M4A is strongly preferred — MediaPlayer plays it everywhere and the
     * folder scanner recognises the extension.
     */
    private fun selectBestAudioStream(info: StreamInfo): AudioStream? {
        val progressive = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank()
        }
        val byFormat = progressive.groupBy { it.format?.suffix?.lowercase(Locale.US) }
        return byFormat["m4a"]?.maxByOrNull { it.averageBitrate }
            ?: byFormat["mp3"]?.maxByOrNull { it.averageBitrate }
            ?: byFormat["aac"]?.maxByOrNull { it.averageBitrate }
            ?: byFormat["ogg"]?.maxByOrNull { it.averageBitrate }
            ?: progressive.maxByOrNull { it.averageBitrate }
    }

    private fun extensionFor(stream: AudioStream): String =
        stream.format?.suffix?.lowercase(Locale.US)?.takeUnless { it.isBlank() } ?: "m4a"

    private fun mimeTypeFor(extension: String): String = when (extension) {
        "m4a", "m4b" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    // ── Download ───────────────────────────────────────────────────────────────

    /** Streams [streamUrl] into the SAF document [docUri] with progress reporting. */
    private suspend fun downloadTo(
        streamUrl: String,
        docUri: Uri,
        onEvent: (ImportEvent) -> Unit
    ) {
        val request = okhttp3.Request.Builder().url(streamUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ImportException("Audio server returned HTTP ${response.code}")
            }
            val body = response.body ?: throw ImportException("Empty download response")
            val totalBytes = body.contentLength() // -1 when unknown

            context.contentResolver.openOutputStream(docUri, "w")?.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1L
                    var lastBytesReported = 0L

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = runInterruptible { input.read(buffer) }
                        if (read < 0) break
                        runInterruptible { out.write(buffer, 0, read) }
                        downloaded += read

                        val shouldReport = if (totalBytes > 0) {
                            downloaded * 100 / totalBytes != lastPercent
                        } else {
                            downloaded - lastBytesReported >= 256 * 1024
                        }
                        if (shouldReport) {
                            if (totalBytes > 0) lastPercent = downloaded * 100 / totalBytes
                            lastBytesReported = downloaded
                            onEvent(ImportEvent.Progress(downloaded, totalBytes))
                        }
                    }
                    out.flush()
                }
            } ?: throw ImportException("Could not open the audio file for writing.")
        }
    }

    // ── NewPipe initialisation ─────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var initialized = false

    private val initLock = Any()

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (!initialized) {
                NewPipe.init(
                    OkHttpDownloader(client),
                    Localization.fromLocale(Locale.getDefault()),
                    ContentCountry.DEFAULT
                )
                initialized = true
            }
        }
    }

    /** NewPipe [Downloader] implementation backed by OkHttp. */
    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

        override fun execute(request: Request): Response {
            val bodyBytes = request.dataToSend()
            val requestBody = bodyBytes?.toRequestBody(null)

            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), requestBody)
            request.headers().forEach { (name, values) ->
                for (value in values) builder.addHeader(name, value)
            }

            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException("Too many requests", request.url())
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    String(bytes, Charsets.UTF_8),
                    request.url()
                )
            }
        }
    }

    // ── SAF helpers ────────────────────────────────────────────────────────────

    /**
     * Finds (or creates) a subdirectory of [rootDocUri] with the given [name]
     * and returns its document URI.
     */
    private fun ensureSubdirectory(treeUri: Uri, rootDocUri: Uri, name: String): Uri {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(rootDocUri)
        )
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val isDir = cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir && cursor.getString(nameCol) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(idCol)
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to the create attempt
        }
        return DocumentsContract.createDocument(
            context.contentResolver,
            rootDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: throw ImportException("Could not create the \"$name\" subfolder in the audio folder.")
    }

    /** Lists all display names directly inside [dirDocUri] (empty set on failure). */
    private fun listChildFileNames(treeUri: Uri, dirDocUri: Uri): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(dirDocUri)
        )
        val names = mutableSetOf<String>()
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(nameCol)?.let { names.add(it) }
                }
            }
        } catch (_: Exception) {
            // Directory may have been revoked — fall through with what we have
        }
        return names
    }

    /** Reads back the display name of a document (providers may rename on create). */
    private fun queryDisplayName(docUri: Uri): String? =
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    // ── Misc helpers ───────────────────────────────────────────────────────────

    /** Sanitises a video title into a safe, reasonably short file name. */
    private fun sanitizeFileName(title: String): String {
        val cleaned = title
            .replace(Regex("[\\n\\r\\t]+"), " ")
            .map { c -> if (c.isLetterOrDigit() || c in " .,_-'()&+![]") c else '_' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(120)
            .trim(' ', '.')
        return cleaned.ifBlank { "YouTube Audio" }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message?.trim().orEmpty()
        return when {
            e is IOException ->
                "Network error while fetching the video. Check your connection and try again."
            msg.contains("age", ignoreCase = true) ->
                "This video is age-restricted and cannot be downloaded on-device. " +
                    "Use the desktop fallback (wags-audio)."
            msg.contains("unavailable", ignoreCase = true) ||
                msg.contains("not available", ignoreCase = true) ->
                "Video not available (removed, private or region-blocked)."
            e is ExtractionException && msg.isBlank() ->
                "Could not extract audio from this video. Try the desktop fallback (wags-audio)."
            else ->
                "Could not extract audio from this video: $msg"
        }
    }
}
