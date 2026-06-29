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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Handles meditation session recording and persistence independently of UI.
 * Used by MeditationService to ensure sessions are saved even when app is closed.
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
    private var sessionStartMs: Long = 0
    private var audioFileName: String? = null
    private val telemetrySamples = mutableListOf<MeditationTelemetryEntity>()
    private var activeMonitorId: String? = null
    private val hrTimeSeries = mutableListOf<Float>()

    /**
     * Start a new meditation session
     */
    fun startSession(startMs: Long, audioFileName: String?) {
        sessionStartMs = startMs
        this.audioFileName = audioFileName
        telemetrySamples.clear()
        hrTimeSeries.clear()
        activeMonitorId = null
        Log.d("MeditationSessionRecorder", "Session started at $startMs")
    }

    /**
     * Set the active monitor ID for this session
     */
    fun setMonitorId(monitorId: String) {
        activeMonitorId = monitorId
    }

    /**
     * Add a telemetry sample to the current session
     */
    fun addTelemetrySample(
        timestampMs: Long,
        hrBpm: Int?,
        rollingRmssdMs: Double
    ) {
        telemetrySamples.add(
            MeditationTelemetryEntity(
                sessionId = 0L, // Will be set when session is saved
                timestampMs = timestampMs,
                hrBpm = hrBpm,
                rollingRmssdMs = rollingRmssdMs
            )
        )
        
        hrBpm?.let { hrTimeSeries.add(it.toFloat()) }
    }

    /**
     * Stop the current session and persist it to the database
     */
    suspend fun stopSession(
        durationMs: Long,
        onComplete: (savedId: Long) -> Unit
    ) = withContext(ioDispatcher) {
        try {
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
                            nnIntervals = doubleArrayOf() // Simplified for service use
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

            // Determine audio ID from file name
            val audioId = audioFileName?.let { findAudioId(it) }

            // Create and save session entity
            val entity = MeditationSessionEntity(
                audioId = audioId,
                timestamp = sessionStartMs,
                durationMs = durationMs,
                timerDurationMs = null, // Timer duration not tracked in service
                monitorId = activeMonitorId,
                avgHrBpm = avgHr,
                hrSlopeBpmPerMin = hrSlope,
                startRmssdMs = startRmssd,
                endRmssdMs = endRmssd,
                lnRmssdSlope = lnSlope,
                posture = "LAYING" // Default posture for service sessions
            )

            val savedId = sessionDao.insert(entity)

            // Save telemetry samples with the real session ID
            if (telemetrySamples.isNotEmpty()) {
                telemetryDao.insertAll(
                    telemetrySamples.map { it.copy(sessionId = savedId) }
                )
            }

            Log.d("MeditationSessionRecorder", "Session saved with ID $savedId")
            onComplete(savedId)
        } catch (e: Exception) {
            Log.e("MeditationSessionRecorder", "Failed to save meditation session", e)
            onComplete(-1L)
        } finally {
            // Reset state
            telemetrySamples.clear()
            hrTimeSeries.clear()
            activeMonitorId = null
            audioFileName = null
        }
    }

    /**
     * Find the audio ID for a given file name
     */
    private suspend fun findAudioId(fileName: String): Long? {
        return try {
            audioDao.getByFileName(fileName)?.audioId
        } catch (e: Exception) {
            Log.e("MeditationSessionRecorder", "Failed to find audio ID for $fileName", e)
            null
        }
    }
}