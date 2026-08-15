package com.example.wags.data.meditation

import android.content.Context
import android.util.Log
import com.example.wags.data.db.dao.MeditationAudioDao
import com.example.wags.data.db.dao.MeditationSessionDao
import com.example.wags.data.db.dao.MeditationTelemetryDao
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.db.entity.MeditationTelemetryEntity
import com.example.wags.di.IoDispatcher
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.session.NsdrAnalyticsCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles meditation session recording and persistence independently of UI.
 * Used by MeditationService to ensure sessions are saved even when app is closed.
 *
 * ## Incremental Persistence
 *
 * The recorder creates a database row for the session **immediately** when
 * [startSession] is called (with `completed = false`).  Telemetry samples are
 * flushed to the database periodically via [flushTelemetry], so that even if
 * the process is killed mid-session, all data collected so far survives.
 *
 * Sessions left with `completed = false` are detected and recovered on the
 * next app launch via [recoverOrphanedSessions].
 */
@Singleton
class MeditationSessionRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: MeditationSessionDao,
    private val telemetryDao: MeditationTelemetryDao,
    private val audioDao: MeditationAudioDao,
    private val analyticsCalculator: NsdrAnalyticsCalculator,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) {

    private companion object {
        /**
         * Incomplete sessions younger than this are never touched by recovery —
         * they may belong to a session that is starting right now (or is running
         * without an HR monitor, so its durationMs is only refreshed by the
         * periodic duration update).
         */
        private const val STALE_SESSION_CUTOFF_MS = 10 * 60 * 1000L // 10 minutes
    }

    private var sessionStartMs: Long = 0
    private var audioFileName: String? = null
    private val telemetrySamples = mutableListOf<MeditationTelemetryEntity>()
    private var activeMonitorId: String? = null
    private val hrTimeSeries = mutableListOf<Float>()

    /** Database row ID of the current in-progress session, or null when idle. */
    @Volatile
    private var currentSessionId: Long? = null

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Start a new meditation session.
     *
     * Creates a database row immediately (with `completed = false`) so that
     * the session survives even if the process is killed seconds later.
     */
    fun startSession(
        startMs: Long,
        audioFileName: String?,
        posture: String = "LAYING",
        monitorId: String? = null,
        timerDurationMs: Long? = null
    ) {
        sessionStartMs = startMs
        this.audioFileName = audioFileName
        this.activeMonitorId = monitorId
        telemetrySamples.clear()
        hrTimeSeries.clear()

        // Create the session row synchronously — this is the critical safety net.
        // A single INSERT takes < 1 ms and guarantees the session exists in the DB
        // even if the process is killed immediately afterwards.
        currentSessionId = runBlocking(ioDispatcher) {
            val audioId = audioFileName?.let { findAudioId(it) }
            sessionDao.insert(
                MeditationSessionEntity(
                    audioId = audioId,
                    timestamp = sessionStartMs,
                    durationMs = 0L,
                    timerDurationMs = timerDurationMs,
                    monitorId = monitorId,
                    posture = posture,
                    completed = false
                )
            )
        }
        Log.d("MeditationSessionRecorder", "Session row created (id=$currentSessionId) at $startMs")
    }

    /** Set the active monitor ID for this session. */
    fun setMonitorId(monitorId: String) {
        activeMonitorId = monitorId
    }

    /** Add a telemetry sample to the current session (in-memory buffer). */
    fun addTelemetrySample(
        timestampMs: Long,
        hrBpm: Int?,
        rollingRmssdMs: Double
    ) {
        val sid = currentSessionId ?: return
        telemetrySamples.add(
            MeditationTelemetryEntity(
                sessionId = sid,
                timestampMs = timestampMs,
                hrBpm = hrBpm,
                rollingRmssdMs = rollingRmssdMs
            )
        )
        hrBpm?.let { hrTimeSeries.add(it.toFloat()) }
    }

    /**
     * Flush all buffered telemetry samples to the database and update the
     * session duration.  Called periodically by the service (every ~15 s)
     * so that data survives process death.
     */
    suspend fun flushTelemetry() {
        val sid = currentSessionId ?: return

        val toFlush = if (telemetrySamples.isNotEmpty()) {
            ArrayList(telemetrySamples).also { telemetrySamples.clear() }
        } else {
            emptyList()
        }

        withContext(ioDispatcher) {
            if (toFlush.isNotEmpty()) {
                telemetryDao.insertAll(toFlush)
            }
            // ALWAYS update the session duration — even when there are no telemetry
            // samples (e.g. no HR monitor connected).  This keeps the row "fresh"
            // so that recoverOrphanedSessions() can never mistake the ACTIVE
            // session for an orphan (durationMs = 0 forever) and delete/finalise
            // it mid-session.
            val durationMs = System.currentTimeMillis() - sessionStartMs
            sessionDao.updateDurationMs(sid, durationMs)
        }
        if (toFlush.isNotEmpty()) {
            Log.d("MeditationSessionRecorder", "Flushed ${toFlush.size} telemetry samples for session $sid")
        }
    }

    /**
     * Stop the current session and finalise it in the database with analytics.
     *
     * @param durationMs total session duration in milliseconds.
     * @param onComplete called with the saved session row ID.
     */
    suspend fun stopSession(
        durationMs: Long,
        onComplete: (savedId: Long) -> Unit
    ) = withContext(ioDispatcher) {
        val sid = currentSessionId
        try {
            // Flush any remaining telemetry
            if (telemetrySamples.isNotEmpty()) {
                telemetryDao.insertAll(ArrayList(telemetrySamples))
                telemetrySamples.clear()
            }

            // Calculate analytics if we have HR data
            var avgHr: Float? = null
            var hrSlope: Float? = null
            var startRmssd: Float? = null
            var endRmssd: Float? = null
            var lnSlope: Float? = null

            if (activeMonitorId != null && hrTimeSeries.isNotEmpty()) {
                try {
                    val analytics = withContext(mathDispatcher) {
                        analyticsCalculator.calculate(
                            hrTimeSeries = hrTimeSeries.toList(),
                            nnIntervals = doubleArrayOf()
                        )
                    }
                    avgHr = analytics.avgHrBpm
                    hrSlope = analytics.hrSlopeBpmPerMin
                    startRmssd = analytics.startRmssdMs
                    endRmssd = analytics.endRmssdMs
                    lnSlope = analytics.lnRmssdSlope
                } catch (e: Exception) {
                    Log.e("MeditationSessionRecorder", "Analytics calculation failed", e)
                }
            }

            if (sid != null) {
                // Update the existing session row with final data.
                // Uses @Update (not INSERT OR REPLACE) to avoid CASCADE-deleting
                // the telemetry rows that were already flushed to the DB.
                val existing = sessionDao.getById(sid)
                if (existing != null) {
                    sessionDao.update(
                        existing.copy(
                            durationMs = durationMs,
                            avgHrBpm = avgHr,
                            hrSlopeBpmPerMin = hrSlope,
                            startRmssdMs = startRmssd,
                            endRmssdMs = endRmssd,
                            lnRmssdSlope = lnSlope,
                            completed = true
                        )
                    )
                    Log.d("MeditationSessionRecorder", "Session $sid finalised with analytics")
                    onComplete(sid)
                } else {
                    Log.w("MeditationSessionRecorder", "Session row $sid vanished, inserting fresh")
                    val newId = insertFreshSession(durationMs, avgHr, hrSlope, startRmssd, endRmssd, lnSlope)
                    onComplete(newId)
                }
            } else {
                // No session row was created (edge case) — insert a new one
                val newId = insertFreshSession(durationMs, avgHr, hrSlope, startRmssd, endRmssd, lnSlope)
                onComplete(newId)
            }
        } catch (e: Exception) {
            Log.e("MeditationSessionRecorder", "Failed to save meditation session", e)
            onComplete(-1L)
        } finally {
            resetState()
        }
    }

    /**
     * Emergency save — called from [MeditationService.onDestroy].
     *
     * Flushes remaining telemetry and marks the session as completed with the
     * current elapsed duration.  Does NOT calculate analytics (no time for that
     * during shutdown).  The session row already exists in the DB from
     * [startSession], so this just updates it.
     */
    suspend fun emergencySave() {
        val sid = currentSessionId ?: return
        try {
            // Flush remaining telemetry
            if (telemetrySamples.isNotEmpty()) {
                telemetryDao.insertAll(ArrayList(telemetrySamples))
                telemetrySamples.clear()
            }
            val durationMs = System.currentTimeMillis() - sessionStartMs
            sessionDao.finalizeSession(sid, durationMs)
            Log.d("MeditationSessionRecorder", "Emergency save for session $sid, duration=${durationMs}ms")
        } catch (e: Exception) {
            Log.e("MeditationSessionRecorder", "Emergency save failed for session $sid", e)
        } finally {
            resetState()
        }
    }

    /**
     * Recover sessions that were interrupted by process death.
     *
     * Deletes tiny accidental sessions (< 5 s) and finalises longer ones
     * with their last-known duration.
     */
    suspend fun recoverOrphanedSessions() {
        try {
            withContext(ioDispatcher) {
                val now = System.currentTimeMillis()
                // Only delete short sessions that are also STALE (created more than
                // 10 minutes ago).  A just-created session row has durationMs = 0,
                // so without this cutoff a recovery pass racing with session start
                // would delete the ACTIVE session row and orphan every telemetry
                // insert for the rest of the session (FK violation → data loss).
                sessionDao.deleteIncompleteShorterThan(5_000L, now - STALE_SESSION_CUTOFF_MS)
                val orphaned = sessionDao.getIncompleteSessions()
                var recoveredCount = 0
                for (session in orphaned) {
                    // Only recover sessions that are truly stale (gap > 60 s since last
                    // duration update).  This prevents finalising sessions that are still
                    // being actively recorded by a running foreground service.
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
                    Log.i("MeditationSessionRecorder", "Recovered $recoveredCount orphaned session(s)")
                }
            }
        } catch (e: Exception) {
            // Recovery must NEVER throw: callers run it inside service coroutines
            // where an unhandled exception would tear down the whole scope
            // (including the wake-lock renewal job) or crash the app.
            Log.e("MeditationSessionRecorder", "Orphan recovery failed", e)
        }
    }

    /** Returns the database row ID of the current in-progress session, or null. */
    fun getCurrentSessionId(): Long? = currentSessionId

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun insertFreshSession(
        durationMs: Long,
        avgHr: Float?,
        hrSlope: Float?,
        startRmssd: Float?,
        endRmssd: Float?,
        lnSlope: Float?
    ): Long {
        val audioId = audioFileName?.let { findAudioId(it) }
        val entity = MeditationSessionEntity(
            audioId = audioId,
            timestamp = sessionStartMs,
            durationMs = durationMs,
            monitorId = activeMonitorId,
            avgHrBpm = avgHr,
            hrSlopeBpmPerMin = hrSlope,
            startRmssdMs = startRmssd,
            endRmssdMs = endRmssd,
            lnRmssdSlope = lnSlope,
            posture = "LAYING",
            completed = true
        )
        val newId = sessionDao.insert(entity)
        if (telemetrySamples.isNotEmpty()) {
            telemetryDao.insertAll(telemetrySamples.map { it.copy(sessionId = newId) })
        }
        return newId
    }

    private fun resetState() {
        telemetrySamples.clear()
        hrTimeSeries.clear()
        activeMonitorId = null
        audioFileName = null
        currentSessionId = null
    }

    private suspend fun findAudioId(fileName: String): Long? {
        return try {
            audioDao.getByFileName(fileName)?.audioId
        } catch (e: Exception) {
            Log.e("MeditationSessionRecorder", "Failed to find audio ID for $fileName", e)
            null
        }
    }

}
