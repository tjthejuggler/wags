package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.db.entity.TelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.DrillContext
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.ProgressiveO2Phase
import com.example.wags.domain.usecase.apnea.ProgressiveO2RoundResult
import com.example.wags.domain.usecase.apnea.ProgressiveO2State
import com.example.wags.domain.usecase.apnea.ProgressiveO2StateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named

// ── UI state ────────────────────────────────────────────────────────────────

data class ProgressiveO2UiState(
    val sessionState: ProgressiveO2State = ProgressiveO2State(),
    /** User-configurable breath period in seconds (shown on setup screen). */
    val breathPeriodSec: Int = 60,
    /** Breath period history for the setup screen display. */
    val pastBreathPeriods: List<BreathPeriodHistory> = emptyList(),
    val isSessionActive: Boolean = false,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Set after session is saved — used for navigation to record detail screen. */
    val completedRecordId: Long? = null,
    // ── Apnea settings (read from SharedPreferences) ─────────────────────────
    val lungVolume: String = "FULL",
    val prepType: String = "NO_PREP",
    val timeOfDay: String = "DAY",
    val posture: String = "LAYING",
    val audio: String = "SILENCE",
    // ── Song picker / Spotify ────────────────────────────────────────────────
    val spotifyConnected: Boolean = false,
    val isMusicMode: Boolean = false,
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSong: SpotifyTrackDetail? = null,
    val loadingSelectedSong: Boolean = false,
    // ── Personal best celebration ──────────────────────────────────────────────
    val newPersonalBest: PersonalBestResult? = null
)

data class BreathPeriodHistory(
    val breathPeriodSec: Int,
    /** Longest completed hold (in seconds) across all sessions with this breath period. */
    val maxHoldReachedSec: Int,
    /** How many sessions used this breath period. */
    val sessionCount: Int
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class ProgressiveO2ViewModel @Inject constructor(
    private val stateMachine: ProgressiveO2StateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val hrDataSource: HrDataSource,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressiveO2UiState())

    val uiState: StateFlow<ProgressiveO2UiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        stateMachine.state,
        spotifyAuthManager.isConnected
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val ui = args[0] as ProgressiveO2UiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val session = args[3] as ProgressiveO2State
        val connected = args[4] as Boolean
        ui.copy(sessionState = session, liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProgressiveO2UiState()
    )

    // Telemetry collection
    private data class TelemetrySample(val timestampMs: Long, val hr: Int?, val spO2: Int?)
    private val telemetrySamples = mutableListOf<TelemetrySample>()
    private var telemetryJob: Job? = null
    private var sessionStartMs: Long = 0L

    // Track previous phase for audio/haptic cues
    private var previousPhase: ProgressiveO2Phase = ProgressiveO2Phase.IDLE

    init {
        // Restore persisted breath period
        val savedBreathPeriod = prefs.getInt("prog_o2_breath_period_sec", 60)

        // Read persisted apnea settings
        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
        val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"
        val savedAudio      = prefs.getString("setting_audio", "SILENCE") ?: "SILENCE"

        _uiState.update {
            it.copy(
                breathPeriodSec = savedBreathPeriod,
                lungVolume  = savedLungVolume,
                prepType    = savedPrepType,
                timeOfDay   = TimeOfDay.fromCurrentTime().name,
                posture     = savedPosture,
                audio       = savedAudio,
                isMusicMode = savedAudio == AudioSetting.MUSIC.name
            )
        }

        // Observe state machine for audio/haptic cues
        viewModelScope.launch {
            stateMachine.state.collect { state ->
                handlePhaseTransition(state)
                previousPhase = state.phase
            }
        }
    }

    // ── Settings setters ────────────────────────────────────────────────────

    fun setLungVolume(v: String) {
        prefs.edit().putString("setting_lung_volume", v).apply()
        _uiState.update { it.copy(lungVolume = v) }
    }

    fun setPrepType(v: String) {
        prefs.edit().putString("setting_prep_type", v).apply()
        _uiState.update { it.copy(prepType = v) }
    }

    fun setTimeOfDay(v: String) {
        prefs.edit().putString("setting_time_of_day", v).apply()
        _uiState.update { it.copy(timeOfDay = v) }
    }

    fun setPosture(v: String) {
        prefs.edit().putString("setting_posture", v).apply()
        _uiState.update { it.copy(posture = v) }
    }

    fun setAudio(v: String) {
        prefs.edit().putString("setting_audio", v).apply()
        _uiState.update { it.copy(audio = v, isMusicMode = v == AudioSetting.MUSIC.name) }
    }

    // ── Song picker ─────────────────────────────────────────────────────────

    fun loadPreviousSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSongs = true) }
            val dbSongs = apneaRepository.getDistinctSongs()
            val prefsSongs = loadSongHistoryFromPrefs()
            val merged = mergeSongs(dbSongs, prefsSongs)
            val details = merged.map { song ->
                var uri = song.spotifyUri
                if (uri == null && spotifyAuthManager.isConnected.value) {
                    uri = spotifyApiClient.searchTrack(song.title, song.artist)
                }
                if (uri != null) {
                    spotifyApiClient.getTrackDetail(uri) ?: SpotifyTrackDetail(
                        spotifyUri = uri, title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt
                    )
                } else {
                    SpotifyTrackDetail(spotifyUri = "", title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt)
                }
            }
            _uiState.update { it.copy(previousSongs = deduplicateTracks(details), loadingSongs = false) }
        }
    }

    fun selectSong(track: SpotifyTrackDetail) {
        _uiState.update { it.copy(selectedSong = track, loadingSelectedSong = true) }
        if (track.spotifyUri.isNotBlank() && spotifyAuthManager.isConnected.value) {
            viewModelScope.launch {
                spotifyManager.ensureSpotifyActive()
                val success = spotifyApiClient.startPlayback(track.spotifyUri)
                if (success) {
                    delay(1_200L)
                    spotifyManager.sendPauseAndRewindCommand()
                }
                _uiState.update { it.copy(loadingSelectedSong = false) }
            }
        } else {
            _uiState.update { it.copy(loadingSelectedSong = false) }
        }
    }

    fun clearSelectedSong() {
        _uiState.update { it.copy(selectedSong = null) }
    }

    private fun loadSongHistoryFromPrefs(): List<SpotifySong> {
        val raw = prefs.getString("song_history", null) ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) return@mapNotNull null
            SpotifySong(
                title = parts[0], artist = parts[1], albumArt = null,
                spotifyUri = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                startedAtMs = 0L, endedAtMs = 0L
            )
        }
    }

    private fun mergeSongs(dbSongs: List<SpotifySong>, prefsSongs: List<SpotifySong>): List<SpotifySong> {
        val seenByUri = mutableSetOf<String>()
        val seenByTitleArtist = mutableSetOf<String>()
        val result = mutableListOf<SpotifySong>()
        for (song in dbSongs + prefsSongs) {
            val titleArtistKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
            if (!seenByTitleArtist.add(titleArtistKey)) continue
            if (!song.spotifyUri.isNullOrBlank()) {
                if (!seenByUri.add(song.spotifyUri!!)) continue
            }
            result.add(song)
        }
        return result
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun loadBreathPeriodHistory() {
        viewModelScope.launch {
            try {
                val sessions = sessionRepository.getSessionsByType("PROGRESSIVE_O2")
                val history = buildBreathPeriodHistory(sessions)
                _uiState.update { it.copy(pastBreathPeriods = history) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load breath period history", e)
            }
        }
    }

    fun setBreathPeriod(seconds: Int) {
        _uiState.update { it.copy(breathPeriodSec = seconds) }
        prefs.edit().putInt("prog_o2_breath_period_sec", seconds).apply()
    }

    fun startSession() {
        val breathPeriodMs = _uiState.value.breathPeriodSec * 1000L
        sessionStartMs = System.currentTimeMillis()
        _uiState.update { it.copy(isSessionActive = true, completedRecordId = null) }

        // Start telemetry collection
        telemetrySamples.clear()
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (true) {
                val hr = hrDataSource.liveHr.value
                val spo2 = hrDataSource.liveSpO2.value
                if (hr != null || spo2 != null) {
                    telemetrySamples.add(
                        TelemetrySample(System.currentTimeMillis(), hr, spo2)
                    )
                }
                delay(1000L)
            }
        }

        stateMachine.start(breathPeriodMs, viewModelScope)
    }

    fun stopSession() {
        // Stop telemetry collection first for a stable snapshot
        telemetryJob?.cancel()
        telemetryJob = null

        stateMachine.stop()
        val finalState = stateMachine.state.value
        _uiState.update { it.copy(isSessionActive = false) }

        viewModelScope.launch {
            try {
                val recordId = saveSession(finalState)
                _uiState.update { it.copy(completedRecordId = recordId) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session", e)
            }
        }
    }

    /** Clears completedRecordId after the UI has navigated to the detail screen. */
    fun onSessionNavigated() {
        _uiState.update { it.copy(completedRecordId = null) }
    }

    /** Dismiss the PB celebration dialog. */
    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    // ── Audio / haptic cues ─────────────────────────────────────────────────

    private fun handlePhaseTransition(state: ProgressiveO2State) {
        if (state.phase == previousPhase) {
            // Same phase — check for breathing countdown tick
            if (state.phase == ProgressiveO2Phase.BREATHING && state.timerMs in 1000..10_000) {
                audioHapticEngine.vibrateBreathingCountdownTick()
            }
            return
        }

        when (state.phase) {
            ProgressiveO2Phase.HOLD -> {
                audioHapticEngine.announceHoldBegin()
            }
            ProgressiveO2Phase.BREATHING -> {
                audioHapticEngine.vibrateHoldEnd()
                audioHapticEngine.announceBreath()
            }
            ProgressiveO2Phase.COMPLETE -> {
                audioHapticEngine.announceSessionComplete()
            }
            ProgressiveO2Phase.IDLE -> { /* no cue */ }
        }
    }

    // ── Session saving ──────────────────────────────────────────────────────

    private suspend fun saveSession(finalState: ProgressiveO2State): Long {
        val now = System.currentTimeMillis()
        val totalDurationMs = now - sessionStartMs
        val breathPeriodSec = _uiState.value.breathPeriodSec
        val deviceLabel = hrDataSource.activeHrDeviceLabel()
        val telemetrySnapshot = telemetrySamples.toList()
        telemetrySamples.clear()

        // Compute aggregates from telemetry
        val maxHr = telemetrySnapshot.mapNotNull { it.hr }.maxOrNull()
        val minHr = telemetrySnapshot.mapNotNull { it.hr }.minOrNull()
        val lowestSpO2 = telemetrySnapshot.mapNotNull { it.spO2 }.minOrNull()

        // Build tableParamsJson
        val paramsJson = buildParamsJson(breathPeriodSec, finalState.roundResults)

        // Count completed rounds
        val completedRounds = finalState.roundResults.count { it.completed }
        val totalRoundsAttempted = finalState.roundResults.size

        // 1. Save ApneaSessionEntity
        val sessionEntity = ApneaSessionEntity(
            timestamp = now,
            tableType = "PROGRESSIVE_O2",
            tableVariant = "ENDLESS",
            tableParamsJson = paramsJson,
            pbAtSessionMs = 0L,
            totalSessionDurationMs = totalDurationMs,
            contractionTimestampsJson = "[]",
            maxHrBpm = maxHr,
            lowestSpO2 = lowestSpO2,
            roundsCompleted = completedRounds,
            totalRounds = totalRoundsAttempted,
            hrDeviceId = deviceLabel
        )
        val sessionId = sessionRepository.saveSession(sessionEntity)

        // 2. Save ApneaRecordEntity (longest completed hold as durationMs)
        val longestCompletedHoldMs = finalState.roundResults
            .filter { it.completed }
            .maxOfOrNull { it.targetHoldMs } ?: 0L

        // Use settings from UI state (already in sync with SharedPreferences)
        val currentState = _uiState.value
        val lungVolume = currentState.lungVolume
        val prepType = currentState.prepType
        val timeOfDay = currentState.timeOfDay
        val posture = currentState.posture
        val audio = currentState.audio

        // Check broader PB BEFORE saving so queries compare against prior records only
        val drill = DrillContext.progressiveO2(breathPeriodSec)
        val pbResult = if (longestCompletedHoldMs > 0L) {
            apneaRepository.checkBroaderPersonalBest(
                drill, longestCompletedHoldMs, lungVolume, prepType, timeOfDay, posture, audio
            )
        } else null

        val recordId = apneaRepository.saveRecord(
            ApneaRecordEntity(
                timestamp = now,
                durationMs = longestCompletedHoldMs,
                lungVolume = lungVolume,
                prepType = prepType,
                minHrBpm = minHr?.toFloat() ?: 0f,
                maxHrBpm = maxHr?.toFloat() ?: 0f,
                tableType = "PROGRESSIVE_O2",
                lowestSpO2 = lowestSpO2,
                timeOfDay = timeOfDay,
                hrDeviceId = deviceLabel,
                posture = posture,
                audio = audio,
                drillParamValue = breathPeriodSec
            )
        )

        // Show PB celebration if applicable
        if (pbResult != null) {
            _uiState.update { it.copy(newPersonalBest = pbResult) }
        }

        // 3. Save FreeHoldTelemetryEntity rows (linked to recordId)
        if (recordId > 0 && telemetrySnapshot.isNotEmpty()) {
            val freeHoldSamples = telemetrySnapshot.map { sample ->
                FreeHoldTelemetryEntity(
                    recordId = recordId,
                    timestampMs = sample.timestampMs,
                    heartRateBpm = sample.hr,
                    spO2 = sample.spO2
                )
            }
            apneaRepository.saveTelemetry(freeHoldSamples)
        }

        // 4. Save TelemetryEntity rows (linked to sessionId)
        if (sessionId > 0 && telemetrySnapshot.isNotEmpty()) {
            val sessionTelemetry = telemetrySnapshot.map { sample ->
                TelemetryEntity(
                    sessionId = sessionId,
                    timestampMs = sample.timestampMs,
                    spO2 = sample.spO2,
                    heartRateBpm = sample.hr,
                    source = if (hrDataSource.isOximeterPrimaryDevice()) "OXIMETER" else "POLAR"
                )
            }
            sessionRepository.saveTelemetry(sessionTelemetry)
        }

        return recordId
    }

    // ── JSON helpers ────────────────────────────────────────────────────────

    private fun buildParamsJson(
        breathPeriodSec: Int,
        rounds: List<ProgressiveO2RoundResult>
    ): String {
        val root = JSONObject()
        root.put("breathPeriodSec", breathPeriodSec)
        val roundsArray = JSONArray()
        for (r in rounds) {
            val obj = JSONObject()
            obj.put("round", r.roundNumber)
            obj.put("targetMs", r.targetHoldMs)
            obj.put("actualMs", r.actualHoldMs)
            obj.put("completed", r.completed)
            roundsArray.put(obj)
        }
        root.put("rounds", roundsArray)
        return root.toString()
    }

    private fun buildBreathPeriodHistory(
        sessions: List<ApneaSessionEntity>
    ): List<BreathPeriodHistory> {
        // Parse each session's tableParamsJson to extract breathPeriodSec and max completed hold
        data class Parsed(val breathPeriodSec: Int, val maxCompletedHoldSec: Int)

        val parsed = sessions.mapNotNull { entity ->
            try {
                val json = JSONObject(entity.tableParamsJson)
                val breathPeriod = json.optInt("breathPeriodSec", 60)
                val roundsArray = json.optJSONArray("rounds") ?: JSONArray()

                var maxHoldMs = 0L
                for (i in 0 until roundsArray.length()) {
                    val r = roundsArray.getJSONObject(i)
                    if (r.optBoolean("completed", false)) {
                        val targetMs = r.optLong("targetMs", 0L)
                        if (targetMs > maxHoldMs) maxHoldMs = targetMs
                    }
                }
                Parsed(breathPeriod, (maxHoldMs / 1000).toInt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse session ${entity.sessionId}", e)
                null
            }
        }

        // Group by breath period, compute max hold and session count
        return parsed.groupBy { it.breathPeriodSec }
            .map { (bp, group) ->
                BreathPeriodHistory(
                    breathPeriodSec = bp,
                    maxHoldReachedSec = group.maxOf { it.maxCompletedHoldSec },
                    sessionCount = group.size
                )
            }
            .sortedBy { it.breathPeriodSec }
    }

    companion object {
        private const val TAG = "ProgressiveO2VM"
    }
}
