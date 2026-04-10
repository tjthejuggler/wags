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
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
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
import com.example.wags.domain.usecase.apnea.MinBreathHoldResult
import com.example.wags.domain.usecase.apnea.MinBreathPhase
import com.example.wags.domain.usecase.apnea.MinBreathState
import com.example.wags.domain.usecase.apnea.MinBreathStateMachine
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

data class MinBreathUiState(
    val sessionState: MinBreathState = MinBreathState(),
    val sessionDurationSec: Int = 300,
    val isSessionActive: Boolean = false,
    val completedRecordId: Long? = null,
    // Live vitals
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    // Past sessions
    val pastDurations: List<DurationHistory> = emptyList(),
    // 5 standard apnea settings
    val lungVolume: String = "FULL",
    val prepType: String = "NO_PREP",
    val timeOfDay: String = "DAY",
    val posture: String = "LAYING",
    val audio: String = "SILENCE",
    // Spotify
    val spotifyConnected: Boolean = false,
    val isMusicMode: Boolean = false,
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSong: SpotifyTrackDetail? = null,
    val loadingSelectedSong: Boolean = false,
    // ── Personal best celebration ──────────────────────────────────────────────
    val newPersonalBest: PersonalBestResult? = null
)

data class DurationHistory(
    val durationSec: Int,
    val bestHoldPct: Double,
    val sessionCount: Int
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class MinBreathViewModel @Inject constructor(
    private val stateMachine: MinBreathStateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val apneaRepository: ApneaRepository,
    private val hrDataSource: HrDataSource,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(MinBreathUiState())

    val uiState: StateFlow<MinBreathUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        stateMachine.state,
        spotifyAuthManager.isConnected
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val ui = args[0] as MinBreathUiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val session = args[3] as MinBreathState
        val connected = args[4] as Boolean
        ui.copy(sessionState = session, liveHr = hr, liveSpO2 = spo2, spotifyConnected = connected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MinBreathUiState()
    )

    // Telemetry collection
    private data class TelemetrySample(val timestampMs: Long, val hr: Int?, val spO2: Int?)
    private val telemetrySamples = mutableListOf<TelemetrySample>()
    private var telemetryJob: Job? = null
    private var sessionStartMs: Long = 0L

    // Per-breath-period duration tracking (indexed by hold number that preceded the breath)
    private val breathDurations = mutableMapOf<Int, Long>()

    init {
        // Restore persisted session duration
        val savedDuration = prefs.getInt("min_breath_session_duration_sec", 300)

        // Read persisted apnea settings
        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
        val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"
        val savedAudio      = prefs.getString("setting_audio", "SILENCE") ?: "SILENCE"

        _uiState.update {
            it.copy(
                sessionDurationSec = savedDuration,
                lungVolume  = savedLungVolume,
                prepType    = savedPrepType,
                timeOfDay   = TimeOfDay.fromCurrentTime().name,
                posture     = savedPosture,
                audio       = savedAudio,
                isMusicMode = savedAudio == AudioSetting.MUSIC.name
            )
        }

        // Observe state machine — only fire audio for COMPLETE
        viewModelScope.launch {
            var previousPhase = MinBreathPhase.IDLE
            stateMachine.state.collect { state ->
                if (state.phase == MinBreathPhase.COMPLETE && previousPhase != MinBreathPhase.COMPLETE) {
                    audioHapticEngine.announceSessionComplete()
                    // Auto-save when session completes naturally (timer ran out)
                    if (_uiState.value.isSessionActive) {
                        val recordId = saveSession(state)
                        telemetryJob?.cancel()
                        _uiState.update { it.copy(
                            isSessionActive = false,
                            completedRecordId = recordId
                        ) }
                    }
                }
                previousPhase = state.phase
            }
        }
    }

    // ── Settings setters ────────────────────────────────────────────────────

    fun setSessionDurationSec(sec: Int) {
        _uiState.update { it.copy(sessionDurationSec = sec) }
        prefs.edit().putInt("min_breath_session_duration_sec", sec).apply()
    }

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
                    SpotifyTrackDetail(
                        spotifyUri = "", title = song.title, artist = song.artist,
                        durationMs = 0L, albumArt = song.albumArt
                    )
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

    fun loadPastSessions() {
        viewModelScope.launch {
            try {
                val sessions = sessionRepository.getSessionsByType("MIN_BREATH")
                val history = buildDurationHistory(sessions)
                _uiState.update { it.copy(pastDurations = history) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load past sessions", e)
            }
        }
    }

    fun startSession() {
        val durationMs = _uiState.value.sessionDurationSec * 1000L
        sessionStartMs = System.currentTimeMillis()
        breathDurations.clear()
        _uiState.update { it.copy(isSessionActive = true, completedRecordId = null) }

        // Start telemetry collection
        telemetrySamples.clear()
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (true) {
                val hr = hrDataSource.liveHr.value
                val spo2 = hrDataSource.liveSpO2.value
                if (hr != null || spo2 != null) {
                    telemetrySamples.add(TelemetrySample(System.currentTimeMillis(), hr, spo2))
                }
                delay(1000L)
            }
        }

        stateMachine.start(durationMs, viewModelScope)
    }

    fun stopSession() {
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

    fun markContraction() {
        stateMachine.markContraction()
    }

    fun switchToBreathing() {
        stateMachine.switchToBreathing()
    }

    fun switchToHolding() {
        // Capture breath duration before state machine transitions
        val currentState = stateMachine.state.value
        if (currentState.phase == MinBreathPhase.BREATHING) {
            val holdNumber = currentState.currentHoldNumber
            // holdNumber is the hold that just ended; breath follows it
            breathDurations[holdNumber] = currentState.currentPhaseElapsedMs
        }
        stateMachine.switchToHolding()
    }

    /** Clears completedRecordId after the UI has navigated to the detail screen. */
    fun onSessionNavigated() {
        _uiState.update { it.copy(completedRecordId = null) }
    }

    /** Dismiss the PB celebration dialog. */
    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    // ── Session saving ──────────────────────────────────────────────────────

    private suspend fun saveSession(finalState: MinBreathState): Long {
        val now = System.currentTimeMillis()
        val totalDurationMs = now - sessionStartMs
        val sessionDurationSec = _uiState.value.sessionDurationSec
        val deviceLabel = hrDataSource.activeHrDeviceLabel()
        val telemetrySnapshot = telemetrySamples.toList()
        telemetrySamples.clear()

        // Compute aggregates from telemetry
        val maxHr = telemetrySnapshot.mapNotNull { it.hr }.maxOrNull()
        val minHr = telemetrySnapshot.mapNotNull { it.hr }.minOrNull()
        val lowestSpO2 = telemetrySnapshot.mapNotNull { it.spO2 }.minOrNull()

        // Build tableParamsJson
        val paramsJson = buildParamsJson(sessionDurationSec, finalState)

        val holdResults = finalState.holdResults

        // 1. Save ApneaSessionEntity
        val sessionEntity = ApneaSessionEntity(
            timestamp = now,
            tableType = "MIN_BREATH",
            tableVariant = "TIMED",
            tableParamsJson = paramsJson,
            pbAtSessionMs = 0L,
            totalSessionDurationMs = totalDurationMs,
            contractionTimestampsJson = "[]",
            maxHrBpm = maxHr,
            lowestSpO2 = lowestSpO2,
            roundsCompleted = holdResults.size,
            totalRounds = holdResults.size,
            hrDeviceId = deviceLabel
        )
        val sessionId = sessionRepository.saveSession(sessionEntity)

        // 2. Save ApneaRecordEntity (total hold time as durationMs for Min Breath)
        val totalHoldTimeMs = finalState.totalHoldTimeMs

        val currentState = _uiState.value

        // Check broader PB BEFORE saving so queries compare against prior records only
        val drill = DrillContext.minBreath(sessionDurationSec)
        val pbResult = if (totalHoldTimeMs > 0L) {
            apneaRepository.checkBroaderPersonalBest(
                drill, totalHoldTimeMs,
                currentState.lungVolume, currentState.prepType, currentState.timeOfDay,
                currentState.posture, currentState.audio
            )
        } else null

        val recordId = apneaRepository.saveRecord(
            ApneaRecordEntity(
                timestamp = now,
                durationMs = totalHoldTimeMs,
                lungVolume = currentState.lungVolume,
                prepType = currentState.prepType,
                minHrBpm = minHr?.toFloat() ?: 0f,
                maxHrBpm = maxHr?.toFloat() ?: 0f,
                tableType = "MIN_BREATH",
                lowestSpO2 = lowestSpO2,
                timeOfDay = currentState.timeOfDay,
                hrDeviceId = deviceLabel,
                posture = currentState.posture,
                audio = currentState.audio,
                drillParamValue = sessionDurationSec
            )
        )

        // Show PB celebration + fire Tail habit if applicable
        if (pbResult != null) {
            _uiState.update { it.copy(newPersonalBest = pbResult) }
            try { habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD) } catch (_: Exception) {}
        }

        // Fire Tail habit for every completed Min Breath session
        try { habitRepo.sendHabitIncrement(Slot.MIN_BREATH) } catch (_: Exception) {}

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

    private fun buildParamsJson(sessionDurationSec: Int, state: MinBreathState): String {
        val totalHoldTimeMs = state.totalHoldTimeMs
        val totalBreathTimeMs = state.totalBreathTimeMs
        val totalActiveMs = totalHoldTimeMs + totalBreathTimeMs
        val holdPct = if (totalActiveMs > 0) {
            (totalHoldTimeMs.toDouble() / totalActiveMs * 100.0)
        } else 0.0

        val root = JSONObject()
        root.put("sessionDurationSec", sessionDurationSec)
        root.put("totalHoldTimeMs", totalHoldTimeMs)
        root.put("totalBreathTimeMs", totalBreathTimeMs)
        root.put("holdPct", String.format("%.1f", holdPct).toDouble())

        val holdsArray = JSONArray()
        for (r in state.holdResults) {
            val obj = JSONObject()
            obj.put("hold", r.holdNumber)
            obj.put("durationMs", r.holdDurationMs)
            if (r.firstContractionMs != null) {
                obj.put("contractionMs", r.firstContractionMs)
            } else {
                obj.put("contractionMs", JSONObject.NULL)
            }
            // Add per-hold breath duration (captured when user switched back to holding)
            val breathMs = breathDurations[r.holdNumber]
            if (breathMs != null) {
                obj.put("breathDurationMs", breathMs)
            } else {
                obj.put("breathDurationMs", JSONObject.NULL)
            }
            holdsArray.put(obj)
        }
        root.put("holds", holdsArray)
        return root.toString()
    }

    private fun buildDurationHistory(sessions: List<ApneaSessionEntity>): List<DurationHistory> {
        data class Parsed(val sessionDurationSec: Int, val holdPct: Double)

        val parsed = sessions.mapNotNull { entity ->
            try {
                val json = JSONObject(entity.tableParamsJson)
                val duration = json.optInt("sessionDurationSec", 300)
                val pct = json.optDouble("holdPct", 0.0)
                Parsed(duration, pct)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse session ${entity.sessionId}", e)
                null
            }
        }

        return parsed.groupBy { it.sessionDurationSec }
            .map { (dur, group) ->
                DurationHistory(
                    durationSec = dur,
                    bestHoldPct = group.maxOf { it.holdPct },
                    sessionCount = group.size
                )
            }
            .sortedBy { it.durationSec }
    }

    companion object {
        private const val TAG = "MinBreathVM"
    }
}
