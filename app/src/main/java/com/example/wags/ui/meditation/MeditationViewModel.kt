package com.example.wags.ui.meditation

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.R
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
import com.example.wags.data.db.entity.MeditationTelemetryEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.MeditationRepository
import com.example.wags.data.repository.YouTubeMetadataFetcher
import com.example.wags.di.IoDispatcher
import com.example.wags.di.MathDispatcher
import com.example.wags.domain.usecase.hrv.ArtifactCorrectionUseCase
import com.example.wags.domain.usecase.session.HrSonificationEngine
import com.example.wags.domain.usecase.session.NsdrAnalyticsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named

// ── Session state machine ──────────────────────────────────────────────────────

enum class MeditationSessionState { IDLE, ACTIVE, PROCESSING, COMPLETE }

enum class MeditationPosture(val label: String) {
    LAYING("Laying"),
    SITTING("Sitting"),
    WALKING("Walking")
}

// ── UI state ──────────────────────────────────────────────────────────────────

data class MeditationUiState(
    // Audio list (all)
    val audios: List<MeditationAudioEntity> = emptyList(),
    val selectedAudio: MeditationAudioEntity? = null,
    val isLoadingAudios: Boolean = false,
    val audioDirUri: String = "",
    // Channel filter
    val availableChannels: List<String> = emptyList(),
    val selectedChannelFilter: String? = null,   // null = "All"
    // Posture selection (persists across sessions until changed)
    val selectedPosture: MeditationPosture = MeditationPosture.LAYING,
    // Session
    val sessionState: MeditationSessionState = MeditationSessionState.IDLE,
    val elapsedSeconds: Long = 0L,
    val currentHrBpm: Float? = null,
    val currentRmssd: Float? = null,
    val sonificationEnabled: Boolean = false,
    // Countdown timer (optional, indication only — does not stop session)
    val timerEnabled: Boolean = false,
    val timerHours: Int = 0,
    val timerMinutes: Int = 20,
    val timerSeconds: Int = 0,
    /** Remaining seconds when timer is active; null when timer is off or not started. */
    val timerRemainingSeconds: Long? = null,
    val timerChimeFired: Boolean = false,
    // Post-session analytics
    val avgHrBpm: Float? = null,
    val hrSlopeBpmPerMin: Float? = null,
    val startRmssdMs: Float? = null,
    val endRmssdMs: Float? = null,
    val lnRmssdSlope: Float? = null,
    val monitorId: String? = null,
    val durationMs: Long = 0L,
    // Device
    val hasHrMonitor: Boolean = false,
    val connectedDeviceId: String? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    // URL edit dialog
    val editingAudio: MeditationAudioEntity? = null,
    val isFetchingMetadata: Boolean = false,
    val fetchedMetadata: YouTubeMetadataFetcher.YoutubeMetadata? = null,
    // Saved session id (for navigation after complete)
    val savedSessionId: Long? = null
) {
    /** The filtered audio list shown in the UI. */
    val filteredAudios: List<MeditationAudioEntity>
        get() = when {
            selectedChannelFilter == null -> audios
            else -> audios.filter { audio ->
                audio.isNone || audio.youtubeChannel == selectedChannelFilter
            }
        }

    /** Total timer duration in seconds (0 if timer disabled or all fields zero). */
    val timerTotalSeconds: Long
        get() = timerHours * 3600L + timerMinutes * 60L + timerSeconds.toLong()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class MeditationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: MeditationRepository,
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val analyticsCalculator: NsdrAnalyticsCalculator,
    private val artifactCorrection: ArtifactCorrectionUseCase,
    private val sonificationEngine: HrSonificationEngine,
    private val habitRepo: HabitIntegrationRepository,
    @Named("meditation_prefs") private val prefs: SharedPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val PREF_SONIFICATION   = "sonification_enabled"
        private const val PREF_POSTURE        = "posture"
        private const val PREF_TIMER_ENABLED  = "timer_enabled"
        private const val PREF_TIMER_HOURS    = "timer_hours"
        private const val PREF_TIMER_MINUTES  = "timer_minutes"
        private const val PREF_TIMER_SECONDS  = "timer_seconds"
        private const val PREF_CHANNEL_FILTER = "channel_filter"
    }

    private val _uiState = MutableStateFlow(
        MeditationUiState(
            sonificationEnabled   = prefs.getBoolean(PREF_SONIFICATION, false),
            selectedPosture       = prefs.getString(PREF_POSTURE, null)
                                        ?.let { runCatching { MeditationPosture.valueOf(it) }.getOrNull() }
                                        ?: MeditationPosture.LAYING,
            timerEnabled          = prefs.getBoolean(PREF_TIMER_ENABLED, false),
            timerHours            = prefs.getInt(PREF_TIMER_HOURS, 0),
            timerMinutes          = prefs.getInt(PREF_TIMER_MINUTES, 20),
            timerSeconds          = prefs.getInt(PREF_TIMER_SECONDS, 0),
            selectedChannelFilter = prefs.getString(PREF_CHANNEL_FILTER, null)
        )
    )
    val uiState: StateFlow<MeditationUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2
    ) { state, hr, spo2 ->
        state.copy(liveHr = hr, liveSpO2 = spo2)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeditationUiState()
    )

    private var sessionJob: Job? = null
    private var sessionStartMs = 0L
    private val hrTimeSeries = mutableListOf<Float>()
    // Per-second telemetry samples accumulated during the session
    private val telemetrySamples = mutableListOf<MeditationTelemetryEntity>()

    // ── Audio playback ─────────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private var chimePlayer: MediaPlayer? = null

    init {
        // Observe HR device connection
        viewModelScope.launch {
            hrDataSource.isAnyHrDeviceConnected.collect { anyConnected ->
                val activeId = hrDataSource.connectedDeviceId()
                _uiState.update {
                    it.copy(hasHrMonitor = anyConnected, connectedDeviceId = activeId)
                }
            }
        }

        // Load audio list and refresh available channels whenever the list changes
        viewModelScope.launch {
            repository.observeAudios().collect { audios ->
                _uiState.update { state ->
                    val currentSelected = state.selectedAudio
                    val newSelected = when {
                        currentSelected == null -> audios.firstOrNull { it.isNone }
                        else -> audios.firstOrNull { it.audioId == currentSelected.audioId }
                            ?: audios.firstOrNull { it.isNone }
                    }
                    // Rebuild channel list from the fresh audio list
                    val channels = audios
                        .mapNotNull { it.youtubeChannel?.takeIf { c -> c.isNotBlank() } }
                        .distinct()
                        .sorted()
                    // If the currently selected filter no longer exists, reset to "All"
                    val validFilter = state.selectedChannelFilter?.takeIf { it in channels }
                    state.copy(
                        audios = audios,
                        selectedAudio = newSelected,
                        availableChannels = channels,
                        selectedChannelFilter = validFilter
                    )
                }
            }
        }

        // Initial sync of audio directory
        viewModelScope.launch(ioDispatcher) {
            val dirUri = repository.getAudioDirUri()
            _uiState.update { it.copy(audioDirUri = dirUri, isLoadingAudios = true) }
            repository.syncAudioDirectory(dirUri)
            _uiState.update { it.copy(isLoadingAudios = false) }
        }
    }

    // ── Audio selection ────────────────────────────────────────────────────────

    fun selectAudio(audio: MeditationAudioEntity) {
        if (_uiState.value.sessionState == MeditationSessionState.IDLE) {
            _uiState.update { it.copy(selectedAudio = audio) }
        }
    }

    fun refreshAudios() {
        viewModelScope.launch(ioDispatcher) {
            val dirUri = repository.getAudioDirUri()
            _uiState.update { it.copy(isLoadingAudios = true) }
            repository.syncAudioDirectory(dirUri)
            _uiState.update { it.copy(isLoadingAudios = false) }
        }
    }

    // ── Channel filter ─────────────────────────────────────────────────────────

    /** Pass null to clear the filter (show all). */
    fun setChannelFilter(channel: String?) {
        _uiState.update { it.copy(selectedChannelFilter = channel) }
        if (channel != null) {
            prefs.edit().putString(PREF_CHANNEL_FILTER, channel).apply()
        } else {
            prefs.edit().remove(PREF_CHANNEL_FILTER).apply()
        }
    }

    // ── URL edit dialog ────────────────────────────────────────────────────────

    fun openUrlEditor(audio: MeditationAudioEntity) {
        _uiState.update {
            it.copy(
                editingAudio     = audio,
                fetchedMetadata  = null,
                isFetchingMetadata = false
            )
        }
    }

    fun dismissUrlEditor() {
        _uiState.update {
            it.copy(
                editingAudio       = null,
                fetchedMetadata    = null,
                isFetchingMetadata = false
            )
        }
    }

    /**
     * Fetches YouTube metadata for [url] and stores it in [MeditationUiState.fetchedMetadata]
     * so the dialog can preview it before the user confirms.
     */
    fun fetchMetadataPreview(url: String) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isFetchingMetadata = true, fetchedMetadata = null) }
            val meta = repository.fetchYouTubeMetadata(url)
            _uiState.update { it.copy(isFetchingMetadata = false, fetchedMetadata = meta) }
        }
    }

    /**
     * Saves the URL (and auto-fetches YouTube metadata if applicable) then closes the dialog.
     */
    fun saveAudioUrl(audioId: Long, url: String) {
        viewModelScope.launch(ioDispatcher) {
            repository.updateAudioUrl(audioId, url.trim())
        }
        _uiState.update {
            it.copy(
                editingAudio       = null,
                fetchedMetadata    = null,
                isFetchingMetadata = false
            )
        }
    }

    // ── Sonification ───────────────────────────────────────────────────────────

    fun setSonificationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sonificationEnabled = enabled) }
        prefs.edit().putBoolean(PREF_SONIFICATION, enabled).apply()
    }

    fun setPosture(posture: MeditationPosture) {
        if (_uiState.value.sessionState == MeditationSessionState.IDLE) {
            _uiState.update { it.copy(selectedPosture = posture) }
            prefs.edit().putString(PREF_POSTURE, posture.name).apply()
        }
    }

    // ── Timer settings ─────────────────────────────────────────────────────────

    fun setTimerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(timerEnabled = enabled) }
        prefs.edit().putBoolean(PREF_TIMER_ENABLED, enabled).apply()
    }

    fun setTimerHours(hours: Int) {
        val v = hours.coerceIn(0, 23)
        _uiState.update { it.copy(timerHours = v) }
        prefs.edit().putInt(PREF_TIMER_HOURS, v).apply()
    }

    fun setTimerMinutes(minutes: Int) {
        val v = minutes.coerceIn(0, 59)
        _uiState.update { it.copy(timerMinutes = v) }
        prefs.edit().putInt(PREF_TIMER_MINUTES, v).apply()
    }

    fun setTimerSeconds(seconds: Int) {
        val v = seconds.coerceIn(0, 59)
        _uiState.update { it.copy(timerSeconds = v) }
        prefs.edit().putInt(PREF_TIMER_SECONDS, v).apply()
    }

    // ── Session lifecycle ──────────────────────────────────────────────────────

    fun startSession() {
        if (_uiState.value.sessionState == MeditationSessionState.ACTIVE) return

        val activeMonitorId = _uiState.value.connectedDeviceId?.takeIf { it.isNotBlank() }
        if (activeMonitorId != null) {
            deviceManager.startRrStream(activeMonitorId)
        }

        hrTimeSeries.clear()
        telemetrySamples.clear()
        sessionStartMs = System.currentTimeMillis()

        // ── Start audio playback ───────────────────────────────────────────────
        val audio = _uiState.value.selectedAudio
        if (audio != null && !audio.isNone) {
            startAudioPlayback(audio)
        }

        val timerTotal = if (_uiState.value.timerEnabled) _uiState.value.timerTotalSeconds else 0L

        _uiState.update {
            it.copy(
                sessionState         = MeditationSessionState.ACTIVE,
                elapsedSeconds       = 0L,
                currentHrBpm         = null,
                currentRmssd         = null,
                avgHrBpm             = null,
                hrSlopeBpmPerMin     = null,
                startRmssdMs         = null,
                endRmssdMs           = null,
                lnRmssdSlope         = null,
                monitorId            = activeMonitorId,
                savedSessionId       = null,
                timerRemainingSeconds = if (timerTotal > 0L) timerTotal else null,
                timerChimeFired      = false
            )
        }

        if (_uiState.value.sonificationEnabled && activeMonitorId != null) {
            sonificationEngine.start(viewModelScope)
        }

        sessionJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1_000L

                // ── Countdown timer tick ───────────────────────────────────────
                val currentRemaining = _uiState.value.timerRemainingSeconds
                if (currentRemaining != null && !_uiState.value.timerChimeFired) {
                    val newRemaining = (currentRemaining - 1L).coerceAtLeast(0L)
                    if (newRemaining == 0L) {
                        // Fire chime and mark as done
                        playChime()
                        _uiState.update {
                            it.copy(timerRemainingSeconds = 0L, timerChimeFired = true)
                        }
                    } else {
                        _uiState.update { it.copy(timerRemainingSeconds = newRemaining) }
                    }
                }

                if (activeMonitorId != null) {
                    val rrSnapshot = deviceManager.rrBuffer.readLast(64)
                    val polarHr = if (rrSnapshot.isNotEmpty())
                        (60_000.0 / rrSnapshot.last()).toFloat() else null
                    val currentHr = polarHr ?: hrDataSource.liveHr.value?.toFloat()
                    val liveRmssd = computeLiveRmssd(rrSnapshot)

                    if (currentHr != null) {
                        hrTimeSeries.add(currentHr)
                        if (_uiState.value.sonificationEnabled) {
                            sonificationEngine.updateHr(currentHr)
                        }
                    }

                    // Accumulate telemetry sample (sessionId = 0 placeholder; set after save)
                    telemetrySamples.add(
                        MeditationTelemetryEntity(
                            sessionId      = 0L,
                            timestampMs    = System.currentTimeMillis(),
                            hrBpm          = currentHr?.let { Math.round(it) },
                            rollingRmssdMs = liveRmssd?.toDouble() ?: 0.0
                        )
                    )

                    _uiState.update {
                        it.copy(
                            elapsedSeconds = elapsed,
                            currentHrBpm   = currentHr,
                            currentRmssd   = liveRmssd
                        )
                    }
                } else {
                    _uiState.update { it.copy(elapsedSeconds = elapsed) }
                }
            }
        }
    }

    fun stopSession() {
        sessionJob?.cancel()
        sonificationEngine.stop()
        stopAudioPlayback()
        val durationMs = System.currentTimeMillis() - sessionStartMs
        // Signal Tail habit integration
        try {
            habitRepo.sendHabitIncrement(Slot.MEDITATION)
        } catch (_: Exception) { /* never crash */ }
        processSession(durationMs)
    }

    fun reset() {
        _uiState.update {
            it.copy(
                sessionState          = MeditationSessionState.IDLE,
                elapsedSeconds        = 0L,
                currentHrBpm          = null,
                currentRmssd          = null,
                avgHrBpm              = null,
                hrSlopeBpmPerMin      = null,
                startRmssdMs          = null,
                endRmssdMs            = null,
                lnRmssdSlope          = null,
                monitorId             = null,
                durationMs            = 0L,
                savedSessionId        = null,
                timerRemainingSeconds = null,
                timerChimeFired       = false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
        stopChime()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Builds a SAF document URI for [audio] inside the stored audio directory,
     * then starts MediaPlayer on it. Silently ignores any failure so a missing
     * file never crashes the session.
     */
    private fun startAudioPlayback(audio: MeditationAudioEntity) {
        stopAudioPlayback()
        val dirUriString = repository.getAudioDirUri()
        if (dirUriString.isBlank()) return
        try {
            val dirUri = Uri.parse(dirUriString)
            val treeDocId = DocumentsContract.getTreeDocumentId(dirUri)
            val childDocId = "$treeDocId/${audio.fileName}"
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, childDocId)
            val mp = MediaPlayer().apply {
                setDataSource(appContext, fileUri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (_: Exception) {
            // File may not exist or SAF permission revoked — session continues silently
            mediaPlayer = null
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }

    /** Plays the ending chime sound once. Uses chime_end.mp3 from raw resources. */
    private fun playChime() {
        stopChime()
        try {
            chimePlayer = MediaPlayer.create(appContext, R.raw.chime_end)?.apply {
                setOnCompletionListener { it.release(); chimePlayer = null }
                start()
            }
        } catch (_: Exception) { /* chime failure is non-fatal */ }
    }

    private fun stopChime() {
        chimePlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        chimePlayer = null
    }

    private fun computeLiveRmssd(rr: List<Double>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return Math.sqrt(diffs.sumOf { it * it } / diffs.size).toFloat()
    }

    private fun processSession(durationMs: Long) {
        _uiState.update { it.copy(sessionState = MeditationSessionState.PROCESSING, durationMs = durationMs) }

        viewModelScope.launch {
            val monitorId = _uiState.value.monitorId
            val audioId   = _uiState.value.selectedAudio?.audioId

            var avgHr: Float? = null
            var hrSlope: Float? = null
            var startRmssd: Float? = null
            var endRmssd: Float? = null
            var lnSlope: Float? = null

            try {
                if (monitorId != null && hrTimeSeries.isNotEmpty()) {
                    val analytics = withContext(mathDispatcher) {
                        val rrSnapshot = deviceManager.rrBuffer.readLast(1024)
                        val corrected  = artifactCorrection.execute(rrSnapshot)
                        analyticsCalculator.calculate(
                            hrTimeSeries = hrTimeSeries.toList(),
                            nnIntervals  = corrected.correctedNn
                        )
                    }
                    avgHr      = analytics.avgHrBpm
                    hrSlope    = analytics.hrSlopeBpmPerMin
                    startRmssd = analytics.startRmssdMs
                    endRmssd   = analytics.endRmssdMs
                    lnSlope    = analytics.lnRmssdSlope
                }
            } catch (_: Exception) {
                // Analytics failure — still save the session
            }

            // Persist timer duration if a timer was active for this session
            val timerMs = if (_uiState.value.timerEnabled) {
                val totalSec = _uiState.value.timerTotalSeconds
                if (totalSec > 0L) totalSec * 1_000L else null
            } else null

            val entity = MeditationSessionEntity(
                audioId          = audioId,
                timestamp        = sessionStartMs,
                durationMs       = durationMs,
                timerDurationMs  = timerMs,
                monitorId        = monitorId,
                avgHrBpm         = avgHr,
                hrSlopeBpmPerMin = hrSlope,
                startRmssdMs     = startRmssd,
                endRmssdMs       = endRmssd,
                lnRmssdSlope     = lnSlope,
                posture          = _uiState.value.selectedPosture.name
            )

            val savedId = withContext(ioDispatcher) {
                val id = repository.insertSession(entity)
                // Persist telemetry with the real session ID
                if (telemetrySamples.isNotEmpty()) {
                    repository.insertTelemetry(
                        telemetrySamples.map { it.copy(sessionId = id) }
                    )
                }
                id
            }

            _uiState.update {
                it.copy(
                    sessionState     = MeditationSessionState.COMPLETE,
                    avgHrBpm         = avgHr,
                    hrSlopeBpmPerMin = hrSlope,
                    startRmssdMs     = startRmssd,
                    endRmssdMs       = endRmssd,
                    lnRmssdSlope     = lnSlope,
                    savedSessionId   = savedId
                )
            }
        }
    }
}
