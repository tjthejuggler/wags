package com.example.wags.ui.meditation

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.data.db.entity.MeditationSessionEntity
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

// ── Session state machine ──────────────────────────────────────────────────────

enum class MeditationSessionState { IDLE, ACTIVE, PROCESSING, COMPLETE }

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
    // Session
    val sessionState: MeditationSessionState = MeditationSessionState.IDLE,
    val elapsedSeconds: Long = 0L,
    val currentHrBpm: Float? = null,
    val currentRmssd: Float? = null,
    val sonificationEnabled: Boolean = false,
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MathDispatcher private val mathDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeditationUiState())
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

    // ── Audio playback ─────────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null

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
    }

    // ── Session lifecycle ──────────────────────────────────────────────────────

    fun startSession() {
        if (_uiState.value.sessionState == MeditationSessionState.ACTIVE) return

        val activeMonitorId = _uiState.value.connectedDeviceId?.takeIf { it.isNotBlank() }
        if (activeMonitorId != null) {
            deviceManager.startRrStream(activeMonitorId)
        }

        hrTimeSeries.clear()
        sessionStartMs = System.currentTimeMillis()

        // ── Start audio playback ───────────────────────────────────────────────
        val audio = _uiState.value.selectedAudio
        if (audio != null && !audio.isNone) {
            startAudioPlayback(audio)
        }

        _uiState.update {
            it.copy(
                sessionState      = MeditationSessionState.ACTIVE,
                elapsedSeconds    = 0L,
                currentHrBpm      = null,
                currentRmssd      = null,
                avgHrBpm          = null,
                hrSlopeBpmPerMin  = null,
                startRmssdMs      = null,
                endRmssdMs        = null,
                lnRmssdSlope      = null,
                monitorId         = activeMonitorId,
                savedSessionId    = null
            )
        }

        if (_uiState.value.sonificationEnabled && activeMonitorId != null) {
            sonificationEngine.start(viewModelScope)
        }

        sessionJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1_000L

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
        processSession(durationMs)
    }

    fun reset() {
        _uiState.update {
            it.copy(
                sessionState     = MeditationSessionState.IDLE,
                elapsedSeconds   = 0L,
                currentHrBpm     = null,
                currentRmssd     = null,
                avgHrBpm         = null,
                hrSlopeBpmPerMin = null,
                startRmssdMs     = null,
                endRmssdMs       = null,
                lnRmssdSlope     = null,
                monitorId        = null,
                durationMs       = 0L,
                savedSessionId   = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
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

            val entity = MeditationSessionEntity(
                audioId          = audioId,
                timestamp        = sessionStartMs,
                durationMs       = durationMs,
                monitorId        = monitorId,
                avgHrBpm         = avgHr,
                hrSlopeBpmPerMin = hrSlope,
                startRmssdMs     = startRmssd,
                endRmssdMs       = endRmssd,
                lnRmssdSlope     = lnSlope
            )

            val savedId = withContext(ioDispatcher) { repository.insertSession(entity) }

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
