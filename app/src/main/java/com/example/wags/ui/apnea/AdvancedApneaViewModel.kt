package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.GuidedAudioEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.model.WonkaConfig
import com.example.wags.domain.usecase.apnea.AdvancedApneaPhase
import com.example.wags.domain.usecase.apnea.AdvancedApneaState
import com.example.wags.domain.usecase.apnea.AdvancedApneaStateMachine
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.domain.usecase.apnea.ProgressiveO2Generator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class AdvancedApneaScreenUiState(
    val sessionState: AdvancedApneaState = AdvancedApneaState(),
    val spotifyConnected: Boolean = false,
    val isMusicMode: Boolean = false,
    val isGuidedMode: Boolean = false,
    val guidedAudios: List<GuidedAudioEntity> = emptyList(),
    val guidedSelectedId: Long = -1L,
    val guidedSelectedName: String = "",
    val guidedCompletionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    val loadingSongs: Boolean = false,
    val selectedSong: SpotifyTrackDetail? = null,
    val loadingSelectedSong: Boolean = false,
    // Current apnea settings (read from SharedPreferences at init)
    val lungVolume: String = "FULL",
    val prepType: String = "NO_PREP",
    val timeOfDay: String = "DAY",
    val posture: String = "LAYING",
    val audio: String = "SILENCE"
)

@HiltViewModel
class AdvancedApneaViewModel @Inject constructor(
    private val progressiveO2Generator: ProgressiveO2Generator,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val stateMachine: AdvancedApneaStateMachine,
    private val sessionRepository: ApneaSessionRepository,
    private val hrDataSource: HrDataSource,
    private val apneaRepository: ApneaRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    // Keep the raw session state flow for backward compat
    val state: StateFlow<AdvancedApneaState> = stateMachine.state

    private val _uiState = MutableStateFlow(AdvancedApneaScreenUiState())

    val uiState: StateFlow<AdvancedApneaScreenUiState> = combine(
        _uiState,
        stateMachine.state,
        spotifyAuthManager.isConnected
    ) { ui, sessionState, connected ->
        ui.copy(sessionState = sessionState, spotifyConnected = connected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AdvancedApneaScreenUiState()
    )

    private var modality: TrainingModality = TrainingModality.PROGRESSIVE_O2
    private var length: TableLength = TableLength.MEDIUM
    private var wonkaConfig: WonkaConfig = WonkaConfig()
    private var sessionStartMs: Long = 0L
    private var audioSetting: AudioSetting = AudioSetting.SILENCE

    init {
        // Read persisted audio setting so we know whether to show the song picker
        audioSetting = runCatching {
            AudioSetting.valueOf(prefs.getString("setting_audio", AudioSetting.SILENCE.name) ?: AudioSetting.SILENCE.name)
        }.getOrDefault(AudioSetting.SILENCE)

        // Read all persisted apnea settings for the summary banner
        val savedLungVolume = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
        val savedPrepType   = prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP"
        val savedPosture    = prefs.getString("setting_posture", "LAYING") ?: "LAYING"

        _uiState.update {
            it.copy(
                isMusicMode = audioSetting == AudioSetting.MUSIC,
                isGuidedMode = audioSetting == AudioSetting.GUIDED,
                guidedSelectedId = guidedAudioManager.selectedId,
                lungVolume  = savedLungVolume,
                prepType    = savedPrepType,
                timeOfDay   = TimeOfDay.fromCurrentTime().name,
                posture     = savedPosture,
                audio       = audioSetting.name
            )
        }

        // Collect guided audio library from DB
        viewModelScope.launch {
            guidedAudioManager.allAudios.collect { audios ->
                _uiState.update { it.copy(guidedAudios = audios) }
            }
        }
        // Load the selected guided audio name from DB
        viewModelScope.launch {
            val name = guidedAudioManager.getSelectedName()
            _uiState.update { it.copy(
                guidedSelectedId = guidedAudioManager.selectedId,
                guidedSelectedName = name
            ) }
        }

        viewModelScope.launch {
            stateMachine.state.collect { advancedState ->
                if (advancedState.phase == AdvancedApneaPhase.COMPLETE) {
                    saveCompletedSession(advancedState)
                }
            }
        }
    }

    fun startSession(
        modality: TrainingModality,
        length: TableLength,
        wonkaConfig: WonkaConfig = WonkaConfig()
    ) {
        this.modality = modality
        this.length = length
        this.wonkaConfig = wonkaConfig
        sessionStartMs = System.currentTimeMillis()
        val pbMs = prefs.getLong("pb_ms", 0L)
        stateMachine.start(modality, length, pbMs, wonkaConfig, viewModelScope)
        // Start Spotify if MUSIC is selected.
        // Song was pre-loaded in selectSong() — just resume playback.
        if (audioSetting == AudioSetting.MUSIC) {
            spotifyManager.startTracking()
            spotifyManager.sendPlayCommand()
        }
        // Start guided audio if GUIDED is selected
        if (audioSetting == AudioSetting.GUIDED) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }
    }

    fun signalBreathTaken() {
        stateMachine.signalBreathTaken()
        audioHapticEngine.announceHoldBegin()
    }

    fun signalFirstContraction() {
        stateMachine.signalFirstContraction()
        audioHapticEngine.vibrateContractionLogged()
    }

    fun stopSession() {
        val tracksPlayed = if (audioSetting == AudioSetting.MUSIC) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        // Stop guided audio if GUIDED was selected
        if (audioSetting == AudioSetting.GUIDED) {
            guidedAudioManager.stopPlayback()
        }
        stateMachine.stop()
        if (tracksPlayed.isNotEmpty()) {
            val songs = tracksPlayed.map { t ->
                SpotifySong(t.title, t.artist, null, t.spotifyUri, t.startedAtMs, t.endedAtMs)
            }
            persistSongHistory(songs)
        }
    }

    // ── Guided audio library methods ─────────────────────────────────────────

    fun selectGuidedAudio(audio: GuidedAudioEntity) {
        guidedAudioManager.selectAudio(audio.audioId)
        _uiState.update { it.copy(
            guidedSelectedId = audio.audioId,
            guidedSelectedName = audio.fileName
        ) }
    }

    fun addGuidedAudio(uri: String, fileName: String, sourceUrl: String) {
        viewModelScope.launch {
            val id = guidedAudioManager.addAudio(fileName, uri, sourceUrl)
            guidedAudioManager.selectAudio(id)
            _uiState.update { it.copy(
                guidedSelectedId = id,
                guidedSelectedName = fileName
            ) }
        }
    }

    fun deleteGuidedAudio(audio: GuidedAudioEntity) {
        viewModelScope.launch {
            guidedAudioManager.deleteAudio(audio.audioId)
            if (_uiState.value.guidedSelectedId == audio.audioId) {
                _uiState.update { it.copy(guidedSelectedId = -1L, guidedSelectedName = "") }
            }
        }
    }

    fun loadGuidedCompletionStatuses() {
        viewModelScope.launch {
            val audios = _uiState.value.guidedAudios
            val map = mutableMapOf<Long, GuidedCompletionStatus>()
            for (audio in audios) {
                val ever = apneaRepository.wasGuidedAudioUsedEver(audio.fileName)
                val ui = _uiState.value
                val withSettings = apneaRepository.wasGuidedAudioUsedWithSettings(
                    audio.fileName, ui.lungVolume, ui.prepType, ui.timeOfDay, ui.posture, ui.audio
                )
                map[audio.audioId] = GuidedCompletionStatus(
                    completedEver = ever,
                    completedWithCurrentSettings = withSettings
                )
            }
            _uiState.update { it.copy(guidedCompletionStatuses = map) }
        }
    }

    // ── Song picker ──────────────────────────────────────────────────────────

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
            // Final dedup by title+artist after API enrichment
            _uiState.update { it.copy(previousSongs = deduplicateTracks(details), loadingSongs = false) }
        }
    }

    /**
     * Called when the user taps a song card in the picker.
     * Pre-loads the song into Spotify playback immediately (then pauses) so it
     * is ready to resume instantly when the user taps Start.
     */
    fun selectSong(track: SpotifyTrackDetail) {
        _uiState.update { it.copy(selectedSong = track, loadingSelectedSong = true) }
        if (track.spotifyUri.isNotBlank() && spotifyAuthManager.isConnected.value) {
            viewModelScope.launch {
                // Ensure Spotify is running before attempting playback
                spotifyManager.ensureSpotifyActive()
                val success = spotifyApiClient.startPlayback(track.spotifyUri)
                Log.d("AdvancedApnea", "selectSong pre-load: success=$success for ${track.spotifyUri}")
                if (success) {
                    kotlinx.coroutines.delay(1_200L)
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

    private fun persistSongHistory(songs: List<SpotifySong>) {
        if (songs.isEmpty()) return
        val existing = loadSongHistoryFromPrefs().toMutableList()
        for (song in songs) {
            val titleArtistKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
            val alreadyPresent = existing.any { s ->
                "${s.title.lowercase().trim()}|${s.artist.lowercase().trim()}" == titleArtistKey
            }
            if (!alreadyPresent) existing.add(0, song)
        }
        val trimmed = existing.take(50)
        val json = trimmed.joinToString(separator = "\n") { s ->
            listOf(s.title, s.artist, s.spotifyUri ?: "").joinToString("|")
        }
        prefs.edit().putString("song_history", json).apply()
    }

    private fun saveCompletedSession(advancedState: AdvancedApneaState) {
        viewModelScope.launch {
            val pbMs = prefs.getLong("pb_ms", 0L)
            val totalDurationMs = System.currentTimeMillis() - sessionStartMs
            val entity = ApneaSessionEntity(
                timestamp = System.currentTimeMillis(),
                tableType = modality.name,
                tableVariant = length.name,
                tableParamsJson = "{}",
                pbAtSessionMs = pbMs,
                totalSessionDurationMs = totalDurationMs,
                contractionTimestampsJson = "[]",
                maxHrBpm = null,
                lowestSpO2 = null,
                roundsCompleted = advancedState.currentRound,
                totalRounds = advancedState.totalRounds,
                hrDeviceId = hrDataSource.activeHrDeviceLabel()
            )
            sessionRepository.saveSession(entity)
        }
    }

    override fun onCleared() {
        guidedAudioManager.stopPlayback()
        stateMachine.stop()
        super.onCleared()
    }
}
