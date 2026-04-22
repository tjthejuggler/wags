package com.example.wags.data.debug

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.wags.data.debug.ScreenContextMapper.ScreenContext
import com.example.wags.domain.model.NoteType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DebugNoteRepo"
private const val FILE_NAME = "debug_wags.json"

/**
 * A single debug note entry as stored in the JSON file.
 */
data class DebugNoteEntry(
    val id: String,
    val timestamp: String,
    val screenRoute: String,
    val screenLabel: String,
    val sourceFile: String,
    val sourceFunctions: String,
    val noteType: String,
    val noteText: String
)

/**
 * Manages debug notes: in-memory tracking for the bubble indicator
 * and persistence to [debug_wags.json].
 *
 * When a SAF directory URI is configured, writes via [ContentResolver].
 * Otherwise falls back to app-internal storage.
 */
@Singleton
class DebugNoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debugPrefs: DebugPreferences
) {
    /** All notes loaded from the JSON file, keyed by screen route for fast lookup. */
    private val _notesByScreen = MutableStateFlow<Map<String, List<DebugNoteEntry>>>(emptyMap())
    val notesByScreen: StateFlow<Map<String, List<DebugNoteEntry>>> = _notesByScreen.asStateFlow()

    /** Total count of notes, for quick badge display. */
    private val _totalNoteCount = MutableStateFlow(0)
    val totalNoteCount: StateFlow<Int> = _totalNoteCount.asStateFlow()

    init {
        loadFromFile()
    }

    /**
     * Add a new debug note and persist it to the JSON file.
     */
    suspend fun addNote(
        screenRoute: String,
        screenContext: ScreenContext,
        noteType: NoteType,
        noteText: String
    ) = withContext(Dispatchers.IO) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = DebugNoteEntry(
            id = System.currentTimeMillis().toString(),
            timestamp = now,
            screenRoute = screenRoute,
            screenLabel = screenContext.label,
            sourceFile = screenContext.sourceFile,
            sourceFunctions = screenContext.sourceFunctions,
            noteType = noteType.name,
            noteText = noteText
        )

        // Update in-memory state
        val current = _notesByScreen.value.toMutableMap()
        val screenNotes = (current[screenRoute] ?: emptyList()).toMutableList()
        screenNotes.add(entry)
        current[screenRoute] = screenNotes
        _notesByScreen.value = current
        _totalNoteCount.value = current.values.sumOf { it.size }

        // Persist
        writeToFile(current)
    }

    /**
     * Returns whether any notes exist for the given screen route.
     */
    fun hasNotesForScreen(route: String?): Boolean {
        if (route == null) return false
        return (_notesByScreen.value[route]?.size ?: 0) > 0
    }

    /**
     * Returns the count of notes for the given screen route.
     */
    fun noteCountForScreen(route: String?): Int {
        if (route == null) return 0
        return _notesByScreen.value[route]?.size ?: 0
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private fun loadFromFile() {
        try {
            val dirUri = debugPrefs.debugFileDirUri
            if (dirUri.isNotBlank()) {
                loadFromSaf(dirUri)
            } else {
                loadFromInternal()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load debug notes", e)
        }
    }

    private fun loadFromInternal() {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            _notesByScreen.value = emptyMap()
            _totalNoteCount.value = 0
            return
        }
        parseAndSet(file.readText())
    }

    private fun loadFromSaf(dirUriString: String) {
        try {
            val dirUri = Uri.parse(dirUriString)
            val docFile = DocumentFile.fromTreeUri(context, dirUri)
                ?: return
            val existing = docFile.findFile(FILE_NAME)
            if (existing == null || !existing.exists()) {
                _notesByScreen.value = emptyMap()
                _totalNoteCount.value = 0
                return
            }
            val contents = context.contentResolver.openInputStream(existing.uri)?.bufferedReader()?.readText()
            if (contents.isNullOrBlank()) return
            parseAndSet(contents)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from SAF", e)
            // Fall back to internal
            loadFromInternal()
        }
    }

    private fun parseAndSet(contents: String) {
        val root = JSONObject(contents)
        val arr = root.optJSONArray("notes") ?: return
        val allNotes = mutableListOf<DebugNoteEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            allNotes.add(DebugNoteEntry(
                id = obj.getString("id"),
                timestamp = obj.getString("timestamp"),
                screenRoute = obj.getString("screenRoute"),
                screenLabel = obj.getString("screenLabel"),
                sourceFile = obj.getString("sourceFile"),
                sourceFunctions = obj.getString("sourceFunctions"),
                noteType = obj.getString("noteType"),
                noteText = obj.getString("noteText")
            ))
        }
        val map = allNotes.groupBy { it.screenRoute }
        _notesByScreen.value = map
        _totalNoteCount.value = allNotes.size
    }

    private fun writeToFile(notesMap: Map<String, List<DebugNoteEntry>>) {
        try {
            val allNotes = notesMap.values.flatten()
            val arr = JSONArray()
            allNotes.forEach { entry ->
                arr.put(JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("screenRoute", entry.screenRoute)
                    put("screenLabel", entry.screenLabel)
                    put("sourceFile", entry.sourceFile)
                    put("sourceFunctions", entry.sourceFunctions)
                    put("noteType", entry.noteType)
                    put("noteText", entry.noteText)
                })
            }
            val jsonText = JSONObject().apply { put("notes", arr) }.toString(2)

            val dirUri = debugPrefs.debugFileDirUri
            if (dirUri.isNotBlank()) {
                writeToSaf(dirUri, jsonText)
            } else {
                writeToInternal(jsonText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug notes", e)
        }
    }

    private fun writeToInternal(jsonText: String) {
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(jsonText)
    }

    private fun writeToSaf(dirUriString: String, jsonText: String) {
        val dirUri = Uri.parse(dirUriString)
        val docFile = DocumentFile.fromTreeUri(context, dirUri)
            ?: run {
                Log.e(TAG, "Cannot access SAF directory, falling back to internal")
                writeToInternal(jsonText)
                return
            }

        // Delete existing file if present, then create new
        docFile.findFile(FILE_NAME)?.delete()

        val newFile = docFile.createFile("application/json", FILE_NAME) ?: run {
            Log.e(TAG, "Cannot create file in SAF directory, falling back to internal")
            writeToInternal(jsonText)
            return
        }

        context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
            os.write(jsonText.toByteArray(Charsets.UTF_8))
            os.flush()
        } ?: run {
            Log.e(TAG, "Cannot open output stream, falling back to internal")
            writeToInternal(jsonText)
        }
    }
}
