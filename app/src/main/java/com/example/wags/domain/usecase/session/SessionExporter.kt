package com.example.wags.domain.usecase.session

import android.content.Context
import android.os.Environment
import com.example.wags.data.db.entity.SessionLogEntity
import com.example.wags.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Exports completed sessions to structured JSON files.
 *
 * Output format:
 * {
 *   "format_version": "1.0",
 *   "device": { "name": "Polar H10", "id": "..." },
 *   "session": { "start_utc": "...", "duration_seconds": 300, "type": "MEDITATION" },
 *   "hrv": { "avg_hr_bpm": 62.1, "start_rmssd_ms": 42.1, "end_rmssd_ms": 48.3,
 *             "ln_rmssd_slope": 0.012, "hr_slope_bpm_per_min": -0.4 },
 *   "rr_intervals": [{"t_ms": 0, "rr_ms": 832}, ...]
 * }
 *
 * Files are stored in getExternalFilesDir(DIRECTORY_DOCUMENTS)/wags_exports/.
 */
class SessionExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    companion object {
        private const val FORMAT_VERSION = "1.0"
        private const val EXPORT_DIR = "wags_exports"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    /**
     * Export a session to JSON.
     * @param session the session entity from Room
     * @param rrIntervalsMs raw RR intervals in ms (chronological order)
     * @param deviceName BLE device name (e.g. "Polar H10")
     * @param deviceId BLE device ID
     * @return the exported [File], or null if export failed
     */
    suspend fun export(
        session: SessionLogEntity,
        rrIntervalsMs: List<Double>,
        deviceName: String = "Polar H10",
        deviceId: String = ""
    ): File? = withContext(ioDispatcher) {
        try {
            val json = buildJson(session, rrIntervalsMs, deviceName, deviceId)
            val file = resolveOutputFile(session.timestamp)
            file.parentFile?.mkdirs()
            file.writeText(json)
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun buildJson(
        session: SessionLogEntity,
        rrIntervalsMs: List<Double>,
        deviceName: String,
        deviceId: String
    ): String {
        val root = JSONObject()
        root.put("format_version", FORMAT_VERSION)

        root.put("device", JSONObject().apply {
            put("name", deviceName)
            put("id", deviceId)
        })

        root.put("session", JSONObject().apply {
            put("start_utc", DATE_FORMAT.format(Date(session.timestamp)))
            put("duration_seconds", session.durationMs / 1000L)
            put("type", session.sessionType)
        })

        root.put("hrv", JSONObject().apply {
            put("avg_hr_bpm", session.avgHrBpm)
            put("start_rmssd_ms", session.startRmssdMs)
            put("end_rmssd_ms", session.endRmssdMs)
            put("ln_rmssd_slope", session.lnRmssdSlope)
            put("hr_slope_bpm_per_min", session.hrSlopeBpmPerMin)
        })

        val rrArray = JSONArray()
        var cumulativeMs = 0L
        rrIntervalsMs.forEach { rr ->
            rrArray.put(JSONObject().apply {
                put("t_ms", cumulativeMs)
                put("rr_ms", rr)
            })
            cumulativeMs += rr.toLong()
        }
        root.put("rr_intervals", rrArray)

        return root.toString(2)
    }

    private fun resolveOutputFile(timestampMs: Long): File {
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val exportDir = File(docsDir, EXPORT_DIR)
        val fileName = "session_${FILE_DATE_FORMAT.format(Date(timestampMs))}.json"
        return File(exportDir, fileName)
    }

    /** List all previously exported session files, newest first. */
    fun listExports(): List<File> {
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val exportDir = File(docsDir, EXPORT_DIR)
        return exportDir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
