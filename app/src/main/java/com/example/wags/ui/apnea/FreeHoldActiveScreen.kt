package com.example.wags.ui.apnea

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.example.wags.data.ble.HrDataSource
import com.example.wags.data.ble.UnifiedDeviceManager
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.db.entity.GuidedAudioEntity
import com.example.wags.data.ipc.HabitIntegrationRepository
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.data.spotify.SpotifyAuthManager
import com.example.wags.data.spotify.SpotifyManager
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.data.spotify.TrackInfo
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.OximeterReading
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.NextPbTarget
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.PbThresholds
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.trophyCount
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.usecase.apnea.ApneaAudioHapticEngine
import com.example.wags.domain.usecase.apnea.GuidedAudioManager
import com.example.wags.ui.common.AdviceBanner
import com.example.wags.ui.common.AdviceSection
import com.example.wags.ui.common.KeepScreenOn
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.common.SessionBackHandler
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
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
import javax.inject.Inject
import javax.inject.Named

// ─────────────────────────────────────────────────────────────────────────────
// Physiological sanity bounds
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Any HR or SpO2 value outside these ranges is a sensor glitch and must be
 * discarded before it reaches the database or any aggregate calculation.
 *
 * HR  : 20–250 bpm
 *   Lower bound: extreme diving bradycardia can reach ~20 bpm; anything below
 *   is a BLE dropout artefact (device reports 0 when signal is lost).
 *
 * SpO2: 1–100 %
 *   Lower bound is intentionally 1, NOT 50.  Elite freedivers have documented
 *   SpO2 readings in the 25–40 % range during competitive dives, so any
 *   physiologically plausible non-zero value must be preserved.
 *   Zero is the only value that is unambiguously a "no signal" artefact.
 */
internal object PhysiologicalBounds {
    const val HR_MIN   = 20
    const val HR_MAX   = 250
    const val SPO2_MIN = 1      // reject 0 only — real extreme dives can go very low
    const val SPO2_MAX = 100

    fun isValidHr(bpm: Int): Boolean    = bpm in HR_MIN..HR_MAX
    fun isValidHr(bpm: Float): Boolean  = bpm >= HR_MIN && bpm <= HR_MAX
    fun isValidSpO2(pct: Int): Boolean  = pct in SPO2_MIN..SPO2_MAX
    fun isValidSpO2(pct: Float): Boolean = pct >= SPO2_MIN && pct <= SPO2_MAX
}

// ─────────────────────────────────────────────────────────────────────────────
// Song completion status
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tracks whether a song was completed (hold duration >= song duration) during free holds.
 * @property completedEver true if the song was completed during any past free hold.
 * @property completedWithCurrentSettings true if completed with the current 5-setting combination.
 */
data class SongCompletionStatus(
    val completedEver: Boolean = false,
    val completedWithCurrentSettings: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

data class FreeHoldActiveUiState(
    val freeHoldActive: Boolean = false,
    val showTimer: Boolean = true,
    val freeHoldFirstContractionMs: Long? = null,
    val liveHr: Int? = null,
    val liveSpO2: Int? = null,
    /** Non-null when the just-completed hold set a new PB — contains the broadest beaten category. */
    val newPersonalBest: PersonalBestResult? = null,
    /** True while the async personal-best check is still running after a hold ends. */
    val pbCheckPending: Boolean = false,
    /** The song currently playing in Spotify (null when SILENCE or Spotify not active). */
    val nowPlayingSong: TrackInfo? = null,
    // ── Song picker ──────────────────────────────────────────────────────────
    /** True when the user's Spotify account is connected (PKCE tokens present). */
    val spotifyConnected: Boolean = false,
    /** True when audio setting is MUSIC — controls whether the song picker button is shown. */
    val isMusicMode: Boolean = false,
    /** True when audio setting is GUIDED — controls whether the guided audio picker is shown. */
    val isGuidedMode: Boolean = false,
    /** All guided audios in the library (for the picker dialog). */
    val guidedAudios: List<GuidedAudioEntity> = emptyList(),
    /** ID of the currently selected guided audio (-1 if none). */
    val guidedSelectedId: Long = -1L,
    /** Display name of the currently selected guided audio file. */
    val guidedSelectedName: String = "",
    /** Guided audio completion status keyed by audioId. */
    val guidedCompletionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    /** Songs previously played during breath holds (loaded from DB + enriched via API). */
    val previousSongs: List<SpotifyTrackDetail> = emptyList(),
    /** True while previous songs are being loaded. */
    val loadingSongs: Boolean = false,
    /**
     * Song completion status keyed by title+artist (lowercase, trimmed).
     * Value: [SongCompletionStatus] indicating whether the song was completed ever / with current settings.
     */
    val songCompletionStatus: Map<String, SongCompletionStatus> = emptyMap(),
    /** The song the user selected from the picker (will be loaded into Spotify on Start). */
    val selectedSong: SpotifyTrackDetail? = null,
    /** True while a selected song is being loaded into Spotify playback. */
    val loadingSelectedSong: Boolean = false,
    // ── Guided hyperventilation ──────────────────────────────────────────────
    /** True when the prep type is HYPER — controls whether the guided hyper section is shown. */
    val isHyperPrep: Boolean = false,
    /** True when the user has checked the "Guided Hyperventilation" checkbox. */
    val guidedHyperEnabled: Boolean = false,
    /** Relaxed exhale phase duration in seconds. */
    val guidedRelaxedExhaleSec: Int = 0,
    /** Purge exhale phase duration in seconds. */
    val guidedPurgeExhaleSec: Int = 0,
    /** Transition phase duration in seconds. */
    val guidedTransitionSec: Int = 0,
    /** True while the guided hyper countdown dialog is showing. */
    val showGuidedCountdown: Boolean = false,
    /** True after the guided countdown has completed — button reverts to plain START. */
    val guidedCountdownComplete: Boolean = false,
    /** True when the user wants the guided MP3 to start playing during the hyper countdown. */
    val startMp3WithHyper: Boolean = false,
    // ── Editable settings (mirrored from ViewModel for banner display) ───────
    val currentLungVolume: String = "FULL",
    val currentPrepType: String = "NO_PREP",
    val currentTimeOfDay: String = "DAY",
    val currentPosture: String = "LAYING",
    val currentAudio: String = AudioSetting.SILENCE.name,
    // ── Real-time PB indication ────────────────────────────────────────────
    /** Master toggle: indicate new records during the hold. */
    val pbIndicationEnabled: Boolean = false,
    /** Play sound when a PB threshold is crossed during a hold. */
    val pbIndicationSound: Boolean = true,
    /** Vibrate when a PB threshold is crossed during a hold. */
    val pbIndicationVibration: Boolean = true,
    /** The broadest PB category broken so far during the current hold (for UI display). */
    val currentPbCategory: PersonalBestCategory? = null,
    /** Countdown to the next PB milestone that would earn more trophies. Null when PB indication is off or no target remains. */
    val nextPbTarget: NextPbTarget? = null
)

@HiltViewModel
class FreeHoldActiveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceManager: UnifiedDeviceManager,
    private val hrDataSource: HrDataSource,
    private val apneaRepository: ApneaRepository,
    private val audioHapticEngine: ApneaAudioHapticEngine,
    private val habitRepo: HabitIntegrationRepository,
    private val spotifyManager: SpotifyManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val guidedAudioManager: GuidedAudioManager,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    // Settings — initialized from nav arguments, mutable so the user can edit them from the banner popup
    var lungVolume: String = savedStateHandle.get<String>("lungVolume") ?: "FULL"
        private set
    var prepType: String   = savedStateHandle.get<String>("prepType")   ?: "NO_PREP"
        private set
    var timeOfDay: String  = savedStateHandle.get<String>("timeOfDay")  ?: "DAY"
        private set
    var posture: String    = savedStateHandle.get<String>("posture")    ?: "LAYING"
        private set
    var audio: String      = savedStateHandle.get<String>("audio")      ?: AudioSetting.SILENCE.name
        private set

    private var isMusicMode = audio == AudioSetting.MUSIC.name
    private var isGuidedMode = audio == AudioSetting.GUIDED.name
    private var isHyperPrep = prepType == PrepType.HYPER.name

    private val _uiState = MutableStateFlow(
        run {
            val selId = guidedAudioManager.selectedId
            // Restore per-MP3 hyper settings if this MP3 has them saved
            val perMp3 = if (isGuidedMode && isHyperPrep && selId > 0 && guidedAudioManager.hasPerMp3HyperSettings(selId)) {
                guidedAudioManager.getPerMp3HyperSettings(selId)
            } else null
            FreeHoldActiveUiState(
                showTimer = savedStateHandle.get<Boolean>("showTimer") ?: true,
                isMusicMode = isMusicMode,
                isGuidedMode = isGuidedMode,
                guidedSelectedId = selId,
                isHyperPrep = isHyperPrep,
                guidedHyperEnabled = if (isHyperPrep) prefs.getBoolean("guided_hyper_enabled", false) else false,
                guidedRelaxedExhaleSec = perMp3?.relaxedExhaleSec ?: prefs.getInt("guided_relaxed_exhale_sec", 0),
                guidedPurgeExhaleSec = perMp3?.purgeExhaleSec ?: prefs.getInt("guided_purge_exhale_sec", 0),
                guidedTransitionSec = perMp3?.transitionSec ?: prefs.getInt("guided_transition_sec", 0),
                startMp3WithHyper = perMp3?.startMp3WithHyper ?: false,
                currentLungVolume = lungVolume,
                currentPrepType = prepType,
                currentTimeOfDay = timeOfDay,
                currentPosture = posture,
                currentAudio = audio,
                pbIndicationEnabled = audioHapticEngine.pbIndicationEnabled,
                pbIndicationSound = audioHapticEngine.pbIndicationSound,
                pbIndicationVibration = audioHapticEngine.pbIndicationVibration
            )
        }
    )

    val uiState: StateFlow<FreeHoldActiveUiState> = combine(
        _uiState,
        hrDataSource.liveHr,
        hrDataSource.liveSpO2,
        spotifyManager.currentSong,
        spotifyAuthManager.isConnected
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val state = args[0] as FreeHoldActiveUiState
        val hr = args[1] as Int?
        val spo2 = args[2] as Int?
        val song = args[3] as TrackInfo?
        val connected = args[4] as Boolean
        state.copy(
            liveHr = hr,
            liveSpO2 = spo2,
            nowPlayingSong = if (state.isMusicMode) song else null,
            spotifyConnected = connected
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FreeHoldActiveUiState(
            showTimer = savedStateHandle.get<Boolean>("showTimer") ?: true,
            isMusicMode = isMusicMode,
            isGuidedMode = isGuidedMode,
            isHyperPrep = isHyperPrep
        )
    )

    init {
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

        // Check for a pending repeat song (set by "Repeat This Hold" on the detail screen).
        // If present, auto-select it so the song is pre-loaded and ready when the user taps Start.
        if (isMusicMode) {
            val pendingUri = prefs.getString("pending_repeat_song_uri", null)
            val pendingTitle = prefs.getString("pending_repeat_song_title", null)
            val pendingArtist = prefs.getString("pending_repeat_song_artist", null)
            if (!pendingUri.isNullOrBlank() && !pendingTitle.isNullOrBlank() && !pendingArtist.isNullOrBlank()) {
                // Clear the pending keys immediately so they don't fire again
                prefs.edit()
                    .remove("pending_repeat_song_uri")
                    .remove("pending_repeat_song_title")
                    .remove("pending_repeat_song_artist")
                    .apply()
                // Build a SpotifyTrackDetail and auto-select it (pre-loads into Spotify)
                val repeatTrack = SpotifyTrackDetail(
                    spotifyUri = pendingUri,
                    title = pendingTitle,
                    artist = pendingArtist,
                    durationMs = 0L,
                    albumArt = null
                )
                selectSong(repeatTrack)
            }
        } else {
            // Not music mode — clear any stale pending keys
            prefs.edit()
                .remove("pending_repeat_song_uri")
                .remove("pending_repeat_song_title")
                .remove("pending_repeat_song_artist")
                .apply()
        }
    }

    // ── Guided hyperventilation ──────────────────────────────────────────────

    fun setShowTimer(show: Boolean) {
        _uiState.update { it.copy(showTimer = show) }
        prefs.edit().putBoolean("setting_show_timer", show).apply()
    }

    fun setGuidedHyperEnabled(enabled: Boolean) {
        _uiState.update { it.copy(guidedHyperEnabled = enabled, guidedCountdownComplete = false) }
        prefs.edit().putBoolean("guided_hyper_enabled", enabled).apply()
    }

    fun setGuidedRelaxedExhaleSec(sec: Int) {
        _uiState.update { it.copy(guidedRelaxedExhaleSec = sec) }
        prefs.edit().putInt("guided_relaxed_exhale_sec", sec).apply()
        // Also save per-MP3 when in guided mode
        val id = _uiState.value.guidedSelectedId
        if (isGuidedMode && id > 0) guidedAudioManager.saveRelaxedExhale(id, sec)
    }

    fun setGuidedPurgeExhaleSec(sec: Int) {
        _uiState.update { it.copy(guidedPurgeExhaleSec = sec) }
        prefs.edit().putInt("guided_purge_exhale_sec", sec).apply()
        val id = _uiState.value.guidedSelectedId
        if (isGuidedMode && id > 0) guidedAudioManager.savePurgeExhale(id, sec)
    }

    fun setGuidedTransitionSec(sec: Int) {
        _uiState.update { it.copy(guidedTransitionSec = sec) }
        prefs.edit().putInt("guided_transition_sec", sec).apply()
        val id = _uiState.value.guidedSelectedId
        if (isGuidedMode && id > 0) guidedAudioManager.saveTransitionSec(id, sec)
    }

    fun setStartMp3WithHyper(enabled: Boolean) {
        _uiState.update { it.copy(startMp3WithHyper = enabled) }
        val id = _uiState.value.guidedSelectedId
        if (id > 0) guidedAudioManager.saveStartMp3WithHyper(id, enabled)
    }

    fun showGuidedCountdown() {
        _uiState.update { it.copy(showGuidedCountdown = true) }
        // If "Start MP3 with Hyper" is checked and we're in guided mode, start audio now
        val state = _uiState.value
        if (isGuidedMode && state.startMp3WithHyper) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }
    }

    fun onGuidedCountdownComplete() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
        // Auto-start the breath hold when audio is GUIDED and prep is HYPER —
        // the guided hyperventilation is part of the same audio flow, so the
        // user shouldn't need to tap START again. For all other audio settings
        // the user must manually tap START after the countdown finishes.
        if (isGuidedMode && isHyperPrep) {
            startFreeHold()
        }
    }

    /**
     * Called when the user cancels the guided hyperventilation countdown via the back button.
     * Dismisses the countdown dialog and marks it complete so the Start button is shown,
     * but does NOT auto-start the hold — the user must tap Start manually.
     */
    fun onGuidedCountdownCancelled() {
        _uiState.update { it.copy(showGuidedCountdown = false, guidedCountdownComplete = true) }
        // Stop guided audio if it was started with hyper
        if (isGuidedMode && _uiState.value.startMp3WithHyper) {
            guidedAudioManager.stopPlayback()
        }
    }

    // ── Settings edit (from banner popup) ────────────────────────────────────

    fun updateLungVolume(volume: String) {
        lungVolume = volume
        prefs.edit().putString("setting_lung_volume", volume).apply()
        _uiState.update { it.copy(currentLungVolume = volume) }
    }

    fun updatePrepType(type: String) {
        prepType = type
        isHyperPrep = type == PrepType.HYPER.name
        prefs.edit().putString("setting_prep_type", type).apply()
        _uiState.update { it.copy(currentPrepType = type, isHyperPrep = isHyperPrep) }
    }

    fun updateTimeOfDay(tod: String) {
        timeOfDay = tod
        prefs.edit().putString("setting_time_of_day", tod).apply()
        _uiState.update { it.copy(currentTimeOfDay = tod) }
    }

    fun updatePosture(pos: String) {
        posture = pos
        prefs.edit().putString("setting_posture", pos).apply()
        _uiState.update { it.copy(currentPosture = pos) }
    }

    fun updateAudio(aud: String) {
        audio = aud
        isMusicMode = aud == AudioSetting.MUSIC.name
        isGuidedMode = aud == AudioSetting.GUIDED.name
        prefs.edit().putString("setting_audio", aud).apply()
        _uiState.update {
            it.copy(
                currentAudio = aud,
                isMusicMode = isMusicMode,
                isGuidedMode = isGuidedMode
            )
        }
        if (isGuidedMode) {
            viewModelScope.launch {
                val name = guidedAudioManager.getSelectedName()
                _uiState.update { it.copy(
                    guidedSelectedId = guidedAudioManager.selectedId,
                    guidedSelectedName = name
                ) }
            }
        } else {
            _uiState.update { it.copy(guidedSelectedName = "") }
        }
    }

    // ── Guided audio library methods ─────────────────────────────────────────

    fun selectGuidedAudio(audio: GuidedAudioEntity) {
        guidedAudioManager.selectAudio(audio.audioId)
        // Restore per-MP3 hyper settings if they exist for this MP3
        val perMp3 = if (guidedAudioManager.hasPerMp3HyperSettings(audio.audioId)) {
            guidedAudioManager.getPerMp3HyperSettings(audio.audioId)
        } else null
        _uiState.update {
            if (perMp3 != null) {
                // Restore per-MP3 settings and also update the global prefs
                prefs.edit()
                    .putInt("guided_relaxed_exhale_sec", perMp3.relaxedExhaleSec)
                    .putInt("guided_purge_exhale_sec", perMp3.purgeExhaleSec)
                    .putInt("guided_transition_sec", perMp3.transitionSec)
                    .apply()
                it.copy(
                    guidedSelectedId = audio.audioId,
                    guidedSelectedName = audio.fileName,
                    guidedRelaxedExhaleSec = perMp3.relaxedExhaleSec,
                    guidedPurgeExhaleSec = perMp3.purgeExhaleSec,
                    guidedTransitionSec = perMp3.transitionSec,
                    startMp3WithHyper = perMp3.startMp3WithHyper
                )
            } else {
                it.copy(
                    guidedSelectedId = audio.audioId,
                    guidedSelectedName = audio.fileName
                )
            }
        }
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
                val withSettings = apneaRepository.wasGuidedAudioUsedWithSettings(
                    audio.fileName, lungVolume, prepType, timeOfDay, posture, this@FreeHoldActiveViewModel.audio
                )
                map[audio.audioId] = GuidedCompletionStatus(
                    completedEver = ever,
                    completedWithCurrentSettings = withSettings
                )
            }
            _uiState.update { it.copy(guidedCompletionStatuses = map) }
        }
    }

    private var freeHoldStartTime = 0L
    private val oximeterSamples = mutableListOf<Pair<Long, OximeterReading>>()
    private var oximeterCollectionJob: Job? = null
    /**
     * Captured at hold-start: true when the oximeter is the primary device
     * (no Polar connected). When false, any oximeter readings that arrive
     * from a background-connected oximeter are discarded at save time so
     * the record's SpO₂ fields stay null / N/A.
     */
    private var oximeterIsPrimary = false
    /**
     * Captured at hold-start so the label is guaranteed to reflect the device
     * that was actually connected when the hold began — even if it disconnects
     * before the hold ends.
     */
    private var holdStartDeviceLabel: String? = null

    // ── Real-time PB indication ────────────────────────────────────────────
    /** Pre-computed PB thresholds loaded at hold-start for real-time checking. */
    private var pbThresholds: PbThresholds? = null
    /** Job for the 1-second PB monitoring loop during a hold. */
    private var pbMonitorJob: Job? = null
    /** Tracks the broadest category already indicated so we don't re-fire. */
    private var lastIndicatedCategory: PersonalBestCategory? = null

    fun setPbIndicationEnabled(enabled: Boolean) {
        audioHapticEngine.pbIndicationEnabled = enabled
        _uiState.update { it.copy(pbIndicationEnabled = enabled) }
    }

    fun setPbIndicationSound(enabled: Boolean) {
        audioHapticEngine.pbIndicationSound = enabled
        _uiState.update { it.copy(pbIndicationSound = enabled) }
    }

    fun setPbIndicationVibration(enabled: Boolean) {
        audioHapticEngine.pbIndicationVibration = enabled
        _uiState.update { it.copy(pbIndicationVibration = enabled) }
    }

    fun startFreeHold() {
        // Use the actual connected Polar device ID for the RR stream — the old
        // placeholder caused startRrStream to silently fail, which could leave
        // the rrBuffer empty if the auto-started HR stream hadn't populated it yet.
        val polarDeviceId = hrDataSource.connectedPolarDeviceId()
        if (polarDeviceId != null) {
            deviceManager.startRrStream(polarDeviceId)
        }
        freeHoldStartTime = System.currentTimeMillis()
        oximeterIsPrimary = hrDataSource.isOximeterPrimaryDevice()
        holdStartDeviceLabel = hrDataSource.activeHrDeviceLabel()
        _uiState.update {
            it.copy(
                freeHoldActive = true,
                freeHoldFirstContractionMs = null
            )
        }
        oximeterSamples.clear()
        oximeterCollectionJob?.cancel()
        if (oximeterIsPrimary) {
            oximeterCollectionJob = viewModelScope.launch {
                deviceManager.oximeterReadings.collect { reading ->
                    oximeterSamples.add(System.currentTimeMillis() to reading)
                }
            }
        }
        // If MUSIC is selected, start Spotify and begin song tracking.
        // When a song was pre-selected via the picker, it was already loaded
        // and paused during selectSong(), so a simple play/resume is enough.
        // This avoids the ~500ms API latency that previously caused the song
        // to sometimes not be ready when the hold started.
        if (audio == AudioSetting.MUSIC.name) {
            val selected = _uiState.value.selectedSong
            val hasValidUri = selected != null
                && selected.spotifyUri.isNotBlank()
                && spotifyAuthManager.isConnected.value
            Log.d("FreeHold", "startFreeHold: hasValidUri=$hasValidUri, uri=${selected?.spotifyUri}, connected=${spotifyAuthManager.isConnected.value}")
            spotifyManager.startTracking()
            // The song was pre-loaded in selectSong() — just resume playback.
            // sendPlayCommand() resumes the paused track instantly.
            spotifyManager.sendPlayCommand()
        }
        // Start guided audio if GUIDED is selected — but skip if it was already
        // started during the hyper countdown (startMp3WithHyper == true)
        if (audio == AudioSetting.GUIDED.name && !guidedAudioManager.isPlaying) {
            viewModelScope.launch {
                guidedAudioManager.preparePlayback()
                guidedAudioManager.startPlayback()
            }
        }

        // ── Real-time PB indication ────────────────────────────────────────
        // Load PB thresholds and start monitoring loop if the feature is enabled.
        lastIndicatedCategory = null
        pbMonitorJob?.cancel()
        if (_uiState.value.pbIndicationEnabled) {
            pbMonitorJob = viewModelScope.launch {
                // Load thresholds asynchronously (DB queries)
                pbThresholds = apneaRepository.getPbThresholds(
                    lungVolume = lungVolume,
                    prepType   = prepType,
                    timeOfDay  = timeOfDay,
                    posture    = posture,
                    audio      = audio
                )
                // Monitor every second
                while (true) {
                    delay(1_000L)
                    val thresholds = pbThresholds ?: continue
                    val elapsed = System.currentTimeMillis() - freeHoldStartTime
                    val broadest = thresholds.broadestBroken(elapsed)
                    val nextTarget = thresholds.nextPbTarget(elapsed)

                    // Update next PB countdown
                    _uiState.update { it.copy(nextPbTarget = nextTarget) }

                    if (broadest == null) continue

                    // Only fire if this is a broader category than what we've already indicated
                    val lastOrdinal = lastIndicatedCategory?.ordinal ?: -1
                    if (broadest.ordinal > lastOrdinal) {
                        lastIndicatedCategory = broadest
                        _uiState.update { it.copy(currentPbCategory = broadest) }
                        audioHapticEngine.playPbIndicationSound(broadest)
                        audioHapticEngine.vibratePbIndication(broadest)
                    }
                }
            }
        }
    }

    fun cancelFreeHold() {
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        oximeterSamples.clear()
        // Stop PB monitoring
        pbMonitorJob?.cancel()
        pbMonitorJob = null
        pbThresholds = null
        lastIndicatedCategory = null
        audioHapticEngine.releasePbIndicationPlayers()
        // Pause Spotify and rewind to start of song if MUSIC was selected
        if (audio == AudioSetting.MUSIC.name) {
            spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
        }
        // Stop guided audio if GUIDED was selected
        if (audio == AudioSetting.GUIDED.name) {
            guidedAudioManager.stopPlayback()
        }
        _uiState.update { it.copy(freeHoldActive = false, freeHoldFirstContractionMs = null, currentPbCategory = null, nextPbTarget = null) }
    }

    fun recordFreeHoldFirstContraction() {
        if (_uiState.value.freeHoldFirstContractionMs != null) return
        val elapsed = System.currentTimeMillis() - freeHoldStartTime
        _uiState.update { it.copy(freeHoldFirstContractionMs = elapsed) }
        audioHapticEngine.vibrateContractionLogged()
    }

    fun stopFreeHold() {
        val duration = System.currentTimeMillis() - freeHoldStartTime
        oximeterCollectionJob?.cancel()
        oximeterCollectionJob = null
        // Stop PB monitoring
        pbMonitorJob?.cancel()
        pbMonitorJob = null
        pbThresholds = null
        lastIndicatedCategory = null
        audioHapticEngine.releasePbIndicationPlayers()
        val firstContractionMs = _uiState.value.freeHoldFirstContractionMs
        // Capture device label at hold-end as well — use whichever is non-null,
        // preferring the hold-start label (guaranteed to reflect the device that
        // was connected when the hold began).  The hold-end label acts as a
        // fallback for the edge case where the device connected *after* the hold
        // started (e.g. late auto-connect).
        val deviceLabel = holdStartDeviceLabel ?: hrDataSource.activeHrDeviceLabel()
        // Stop Spotify tracking, collect songs, then pause + rewind to start of song
        val tracksPlayed: List<TrackInfo> = if (audio == AudioSetting.MUSIC.name) {
            val tracks = spotifyManager.stopTracking()
            spotifyManager.sendPauseAndRewindCommand()
            tracks
        } else emptyList()
        // Stop guided audio if GUIDED was selected
        if (audio == AudioSetting.GUIDED.name) {
            guidedAudioManager.stopPlayback()
        }
        _uiState.update {
            it.copy(
                freeHoldActive = false,
                freeHoldFirstContractionMs = null,
                currentPbCategory = null,
                nextPbTarget = null,
                pbCheckPending = true          // prevent navigation until PB check completes
            )
        }
        audioHapticEngine.vibrateHoldEnd()
        habitRepo.sendHabitIncrement(Slot.FREE_HOLD)
        viewModelScope.launch {
            // Check broader PB categories BEFORE saving so queries compare against prior records only.
            val pbResult = apneaRepository.checkBroaderPersonalBest(
                durationMs = duration,
                lungVolume = lungVolume,
                prepType   = prepType,
                timeOfDay  = timeOfDay,
                posture    = posture,
                audio      = audio
            )
            saveFreeHoldRecord(duration, firstContractionMs, deviceLabel, tracksPlayed)
            if (pbResult != null) {
                _uiState.update { it.copy(newPersonalBest = pbResult, pbCheckPending = false) }
                habitRepo.sendHabitIncrement(Slot.APNEA_NEW_RECORD)
            } else {
                _uiState.update { it.copy(pbCheckPending = false) }
            }
        }
    }

    fun dismissNewPersonalBest() {
        _uiState.update { it.copy(newPersonalBest = null) }
    }

    // ── Song picker ──────────────────────────────────────────────────────────

    /**
     * Load distinct songs previously played during any apnea session.
     * Merges DB records (free holds) with SharedPreferences history (all session types).
     * For each song with a spotifyUri, fetches track details (duration, art) from the API.
     */
    fun loadPreviousSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSongs = true) }
            // Load from DB (free holds with saved records)
            val dbSongs = apneaRepository.getDistinctSongs()
            // Load from prefs (all session types, persisted across restarts)
            val prefsSongs = loadSongHistoryFromPrefs()
            // Merge: deduplicate by URI then title+artist
            val merged = mergeSongs(dbSongs, prefsSongs)
            val details = merged.map { song ->
                var uri = song.spotifyUri
                // If no URI saved, try resolving via Spotify Search API
                if (uri == null && spotifyAuthManager.isConnected.value) {
                    uri = spotifyApiClient.searchTrack(song.title, song.artist)
                    Log.d("FreeHold", "searchTrack backfill: '$uri' for '${song.title}' by '${song.artist}'")
                }
                if (uri != null) {
                    // Try API first for duration/art; fall back to basic info from DB
                    spotifyApiClient.getTrackDetail(uri) ?: SpotifyTrackDetail(
                        spotifyUri = uri,
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                } else {
                    // No URI and no auth — show with basic info
                    SpotifyTrackDetail(
                        spotifyUri = "",
                        title = song.title,
                        artist = song.artist,
                        durationMs = 0L,
                        albumArt = song.albumArt
                    )
                }
            }
            // Final dedup by title+artist after API enrichment (same track may
            // have been stored with different URIs or one with/without URI)
            val dedupedSongs = deduplicateTracks(details)

            // Compute completion status for each song.
            // When durationMs is 0 (API unavailable), the SQL query still detects
            // completion via the "next song exists in same record" fallback.
            val completionMap = mutableMapOf<String, SongCompletionStatus>()
            for (track in dedupedSongs) {
                val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                val ever = apneaRepository.wasSongCompletedEver(
                    track.title, track.artist, track.durationMs, track.spotifyUri
                )
                val withSettings = apneaRepository.wasSongCompletedWithSettings(
                    track.title, track.artist, track.durationMs, track.spotifyUri,
                    lungVolume, prepType, timeOfDay, posture, audio
                )
                completionMap[key] = SongCompletionStatus(
                    completedEver = ever,
                    completedWithCurrentSettings = withSettings
                )
            }

            _uiState.update {
                it.copy(
                    previousSongs = dedupedSongs,
                    songCompletionStatus = completionMap,
                    loadingSongs = false
                )
            }
        }
    }

    private fun loadSongHistoryFromPrefs(): List<SpotifySong> {
        val raw = prefs.getString("song_history", null) ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) return@mapNotNull null
            SpotifySong(
                title = parts[0],
                artist = parts[1],
                albumArt = null,
                spotifyUri = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                startedAtMs = 0L,
                endedAtMs = 0L
            )
        }
    }

    private fun mergeSongs(dbSongs: List<SpotifySong>, prefsSongs: List<SpotifySong>): List<SpotifySong> {
        val seenByUri = mutableSetOf<String>()
        val seenByTitleArtist = mutableSetOf<String>()
        val result = mutableListOf<SpotifySong>()
        for (song in dbSongs + prefsSongs) {
            val titleArtistKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
            // Skip if we already have a song with the same title+artist
            if (!seenByTitleArtist.add(titleArtistKey)) continue
            // Also track by URI to avoid URI-based duplicates
            if (!song.spotifyUri.isNullOrBlank()) {
                if (!seenByUri.add(song.spotifyUri!!)) continue
            }
            result.add(song)
        }
        return result
    }

    /**
     * Persists played songs to SharedPreferences so they survive app restarts
     * and are available for all session types. Stores up to 50 unique songs.
     */
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
                Log.d("FreeHold", "selectSong pre-load: success=$success for ${track.spotifyUri}")
                if (success) {
                    // Give Spotify a moment to start, then pause — the track is now
                    // buffered and will resume instantly on the next play command.
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

    fun clearSongHistory() {
        viewModelScope.launch {
            apneaRepository.clearSongHistory()
            _uiState.update { it.copy(previousSongs = emptyList(), selectedSong = null) }
        }
    }

    private fun saveFreeHoldRecord(
        durationMs: Long,
        firstContractionMs: Long? = null,
        deviceLabel: String? = null,
        tracksPlayed: List<TrackInfo> = emptyList()
    ) {
        // Only use oximeter data when the oximeter was the primary device at hold-start.
        // When a Polar device is the primary HR source, any background-connected oximeter
        // readings are incidental resting values (typically 99 %) and must be discarded.
        val oxSnapshot = if (oximeterIsPrimary) oximeterSamples.toList() else emptyList()
        oximeterSamples.clear()

        viewModelScope.launch {
            // Only read Polar RR buffer when Polar is the primary device.
            // When the oximeter is primary, the rrBuffer may contain stale data
            // from a previous Polar session which would interleave with fresh
            // oximeter readings and produce a sawtooth pattern in the chart.
            val rrSnapshot = if (!oximeterIsPrimary) deviceManager.rrBuffer.readLast(512) else emptyList()

            // ── RR-derived HR — discard physiologically impossible beats ──────
            val rrHrValues = rrSnapshot
                .map { 60_000.0 / it }
                .filter { PhysiologicalBounds.isValidHr(it.toFloat()) }
            val minHrFromRr = rrHrValues.minOrNull()?.toFloat() ?: 0f
            val maxHrFromRr = rrHrValues.maxOrNull()?.toFloat() ?: 0f

            // ── Oximeter samples — discard glitch readings (0 bpm, 0 SpO2, etc.) ──
            val validOxHr   = oxSnapshot
                .map { it.second.heartRateBpm.toFloat() }
                .filter { PhysiologicalBounds.isValidHr(it) }
            val validOxSpO2 = oxSnapshot
                .map { it.second.spO2.toFloat() }
                .filter { PhysiologicalBounds.isValidSpO2(it) }

            val maxHrFromOx = validOxHr.maxOrNull() ?: 0f
            val lowestSpO2  = validOxSpO2.minOrNull()?.toInt()

            // Prefer Polar for HR aggregates; fall back to oximeter
            val minHr = if (minHrFromRr > 0f) minHrFromRr else validOxHr.minOrNull() ?: 0f
            val maxHr = if (maxHrFromRr > 0f) maxHrFromRr else maxHrFromOx

            val now = System.currentTimeMillis()

            // Capture guided hyper state at save time
            val guidedState = _uiState.value
            val wasGuided = guidedState.guidedHyperEnabled && isHyperPrep

            // Use the settings that were baked in at navigation time — guaranteed correct
            // Capture PB indication state at save time
            val wasPbIndicationOn = audioHapticEngine.pbIndicationEnabled

            val recordId = apneaRepository.saveRecord(
                ApneaRecordEntity(
                    timestamp              = now,
                    durationMs             = durationMs,
                    lungVolume             = lungVolume,
                    prepType               = prepType,
                    timeOfDay              = timeOfDay,
                    posture                = posture,
                    audio                  = audio,
                    minHrBpm               = minHr,
                    maxHrBpm               = maxHr,
                    lowestSpO2             = lowestSpO2,
                    tableType              = null,
                    firstContractionMs     = firstContractionMs,
                    hrDeviceId             = deviceLabel,
                    guidedAudioName        = if (isGuidedMode) _uiState.value.guidedSelectedName else null,
                    guidedHyper            = wasGuided,
                    guidedRelaxedExhaleSec = if (wasGuided) guidedState.guidedRelaxedExhaleSec else null,
                    guidedPurgeExhaleSec   = if (wasGuided) guidedState.guidedPurgeExhaleSec else null,
                    guidedTransitionSec    = if (wasGuided) guidedState.guidedTransitionSec else null,
                    newRecordIndication    = wasPbIndicationOn
                )
            )

            if (recordId <= 0) return@launch

            val samples = mutableListOf<FreeHoldTelemetryEntity>()

            // ── Polar RR → per-beat HR telemetry (only valid beats) ──────────
            if (rrSnapshot.isNotEmpty()) {
                var cumulativeMs = 0L
                for (rrMs in rrSnapshot) {
                    cumulativeMs += rrMs.toLong()
                    if (cumulativeMs > durationMs) break
                    val bpm = (60_000.0 / rrMs).toInt()
                    if (!PhysiologicalBounds.isValidHr(bpm)) continue   // skip glitch beat
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId     = recordId,
                            timestampMs  = freeHoldStartTime + cumulativeMs,
                            heartRateBpm = bpm,
                            spO2         = null
                        )
                    )
                }
            }

            // ── Oximeter → HR + SpO2 telemetry (only valid readings) ─────────
            for ((timestampMs, reading) in oxSnapshot) {
                if (timestampMs < freeHoldStartTime) continue
                if (timestampMs > freeHoldStartTime + durationMs) continue
                val validHr   = reading.heartRateBpm.takeIf { PhysiologicalBounds.isValidHr(it) }
                val validSpO2 = reading.spO2.takeIf { PhysiologicalBounds.isValidSpO2(it) }
                // Only save the row if at least one field is valid
                if (validHr != null || validSpO2 != null) {
                    samples.add(
                        FreeHoldTelemetryEntity(
                            recordId     = recordId,
                            timestampMs  = timestampMs,
                            heartRateBpm = validHr,
                            spO2         = validSpO2
                        )
                    )
                }
            }

            if (samples.isNotEmpty()) {
                apneaRepository.saveTelemetry(samples)
            }

            // ── Save Spotify song log ─────────────────────────────────────────
            if (tracksPlayed.isNotEmpty()) {
                val songs = tracksPlayed.map { track ->
                    SpotifySong(
                        title       = track.title,
                        artist      = track.artist,
                        spotifyUri  = track.spotifyUri,
                        startedAtMs = track.startedAtMs,
                        endedAtMs   = track.endedAtMs
                    )
                }
                apneaRepository.saveSongLog(recordId, songs)
                // Also persist to SharedPreferences so song history survives restarts
                // and is available for all session types (table, advanced, etc.)
                persistSongHistory(songs)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioHapticEngine.shutdown()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen breath-hold active screen.
 *
 * Settings (lungVolume, prepType, timeOfDay, showTimer) are passed as nav
 * arguments so the correct values are always saved — regardless of which
 * ViewModel instance is alive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeHoldActiveScreen(
    navController: NavController,
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    posture: String,
    showTimer: Boolean,
    viewModel: FreeHoldActiveViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SessionBackHandler(enabled = state.freeHoldActive) {
        viewModel.cancelFreeHold()
        navController.popBackStack()
    }
    // Keep screen on while hold is active OR while reviewing PB results / waiting to navigate
    KeepScreenOn(enabled = state.freeHoldActive || state.pbCheckPending || state.newPersonalBest != null)

    // True once the user taps Stop — we wait for the async PB check before navigating.
    var stopRequested by remember { mutableStateOf(false) }

    // Navigate back when:
    //   • the user tapped Stop (stopRequested), AND
    //   • the async PB check has finished (pbCheckPending is false), AND
    //   • no PB dialog is showing (newPersonalBestMs is null — either no PB or dismissed).
    LaunchedEffect(stopRequested, state.freeHoldActive, state.newPersonalBest, state.pbCheckPending) {
        if (stopRequested && !state.freeHoldActive && !state.pbCheckPending && state.newPersonalBest == null) {
            navController.popBackStack()
        }
    }

    // Show the PB celebration dialog; dismiss clears the state which triggers the
    // LaunchedEffect above to pop back.
    state.newPersonalBest?.let { pbResult ->
        NewPersonalBestDialog(
            newPbMs = pbResult.durationMs,
            categoryDescription = pbResult.description,
            category = pbResult.category,
            onDismiss = { viewModel.dismissNewPersonalBest() }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Breath Hold", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.freeHoldActive) viewModel.cancelFreeHold()
                        navController.popBackStack()
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2, onClick = { navController.navigate(WagsRoutes.SETTINGS) })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        // Song picker dialog state
        var showSongPicker by remember { mutableStateOf(false) }
        // Settings edit dialog state
        var showSettingsDialog by remember { mutableStateOf(false) }

        if (showSongPicker) {
            SongPickerDialog(
                songs = state.previousSongs,
                isLoading = state.loadingSongs,
                selectedSong = state.selectedSong,
                loadingSelectedSong = state.loadingSelectedSong,
                songCompletionStatus = state.songCompletionStatus,
                onSongSelected = { track ->
                    viewModel.selectSong(track)
                },
                onDismiss = { showSongPicker = false }
            )
        }

        // Settings edit popup
        if (showSettingsDialog) {
            FreeHoldSettingsDialog(
                lungVolume = state.currentLungVolume,
                prepType = state.currentPrepType,
                timeOfDay = state.currentTimeOfDay,
                posture = state.currentPosture,
                audio = state.currentAudio,
                onLungVolumeChange = { viewModel.updateLungVolume(it) },
                onPrepTypeChange = { viewModel.updatePrepType(it) },
                onTimeOfDayChange = { viewModel.updateTimeOfDay(it) },
                onPostureChange = { viewModel.updatePosture(it) },
                onAudioChange = { viewModel.updateAudio(it) },
                onDismiss = { showSettingsDialog = false }
            )
        }

        // Guided hyperventilation countdown dialog
        if (state.showGuidedCountdown) {
            GuidedHyperCountdownDialog(
                phases = GuidedHyperPhases(
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec
                ),
                onComplete = { viewModel.onGuidedCountdownComplete() },
                onCancel = { viewModel.onGuidedCountdownCancelled() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Settings summary — clickable to open settings popup (only when hold not active)
            ApneaSettingsSummaryBanner(
                lungVolume = state.currentLungVolume,
                prepType   = state.currentPrepType,
                timeOfDay  = state.currentTimeOfDay,
                posture    = state.currentPosture,
                audio      = state.currentAudio,
                onClick    = if (!state.freeHoldActive) {{ showSettingsDialog = true }} else null
            )

            // Hyperventilating advice — shown only when prep type is HYPER
            if (state.currentPrepType == PrepType.HYPER.name) {
                AdviceBanner(section = AdviceSection.APNEA_HYPER)
            }

            // Now-playing banner — shown only when MUSIC is selected and a song is detected
            if (state.freeHoldActive && state.isMusicMode && state.nowPlayingSong != null) {
                NowPlayingBanner(track = state.nowPlayingSong!!)
            }

            // Selected song banner — shown before hold starts when a song was picked
            if (!state.freeHoldActive && state.selectedSong != null) {
                SelectedSongBanner(track = state.selectedSong!!) {
                    viewModel.clearSelectedSong()
                }
            }

            // Song picker / connect prompt — shown when MUSIC mode + hold not active
            if (!state.freeHoldActive && state.isMusicMode) {
                if (state.spotifyConnected) {
                    SongPickerButton(
                        onClick = {
                            viewModel.loadPreviousSongs()
                            showSongPicker = true
                        }
                    )
                } else {
                    SpotifyConnectPrompt(
                        onNavigateToSettings = { navController.navigate(WagsRoutes.SETTINGS) }
                    )
                }
            }

            // Guided audio picker — shown when GUIDED mode + hold not active
            var showGuidedPicker by remember { mutableStateOf(false) }
            if (!state.freeHoldActive && state.isGuidedMode) {
                if (state.guidedSelectedName.isNotBlank()) {
                    SelectedGuidedAudioBanner(name = state.guidedSelectedName)
                }
                GuidedAudioPickerButton(onClick = {
                    showGuidedPicker = true
                })
            }
            if (showGuidedPicker) {
                LaunchedEffect(Unit) { viewModel.loadGuidedCompletionStatuses() }
                GuidedAudioPickerDialog(
                    audios = state.guidedAudios,
                    selectedId = state.guidedSelectedId,
                    completionStatuses = state.guidedCompletionStatuses,
                    onSelect = { audio -> viewModel.selectGuidedAudio(audio) },
                    onAddNew = { uri, name, url -> viewModel.addGuidedAudio(uri, name, url) },
                    onDelete = { audio -> viewModel.deleteGuidedAudio(audio) },
                    onDismiss = { showGuidedPicker = false }
                )
            }

            // Guided hyperventilation section — shown when prep is HYPER and hold not active
            var showGuidedHyperEditSheet by remember { mutableStateOf(false) }
            if (state.isHyperPrep && !state.freeHoldActive) {
                GuidedHyperSection(
                    enabled = state.guidedHyperEnabled,
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec,
                    showStartMp3WithHyper = state.isGuidedMode,
                    startMp3WithHyper = state.startMp3WithHyper,
                    onEnabledChange = { viewModel.setGuidedHyperEnabled(it) },
                    onStartMp3WithHyperChange = { viewModel.setStartMp3WithHyper(it) },
                    onEditClick = { showGuidedHyperEditSheet = true }
                )
            }

            if (showGuidedHyperEditSheet) {
                GuidedHyperEditSheet(
                    relaxedExhaleSec = state.guidedRelaxedExhaleSec,
                    purgeExhaleSec = state.guidedPurgeExhaleSec,
                    transitionSec = state.guidedTransitionSec,
                    onRelaxedExhaleChange = { viewModel.setGuidedRelaxedExhaleSec(it) },
                    onPurgeExhaleChange = { viewModel.setGuidedPurgeExhaleSec(it) },
                    onTransitionChange = { viewModel.setGuidedTransitionSec(it) },
                    onDismiss = { showGuidedHyperEditSheet = false }
                )
            }

            // ── New Record Indication settings ─────────────────────────────────
            if (!state.freeHoldActive) {
                PbIndicationSection(
                    enabled = state.pbIndicationEnabled,
                    soundEnabled = state.pbIndicationSound,
                    vibrationEnabled = state.pbIndicationVibration,
                    onEnabledChange = { viewModel.setPbIndicationEnabled(it) },
                    onSoundChange = { viewModel.setPbIndicationSound(it) },
                    onVibrationChange = { viewModel.setPbIndicationVibration(it) }
                )
            }

            // Determine whether the start button should trigger guided countdown
            val useGuidedStart = state.isHyperPrep
                && state.guidedHyperEnabled
                && !state.guidedCountdownComplete

            FreeHoldActiveContent(
                freeHoldActive = state.freeHoldActive,
                showTimer = state.showTimer,
                firstContractionMs = state.freeHoldFirstContractionMs,
                currentPbCategory = state.currentPbCategory,
                nextPbTarget = state.nextPbTarget,
                guidedHyperActive = state.isHyperPrep && state.guidedHyperEnabled,
                guidedCountdownComplete = state.guidedCountdownComplete,
                modifier = Modifier.fillMaxSize(),
                onShowTimerChange = { viewModel.setShowTimer(it) },
                onStart = {
                    if (useGuidedStart) {
                        viewModel.showGuidedCountdown()
                    } else {
                        viewModel.startFreeHold()
                    }
                },
                onFirstContraction = { viewModel.recordFreeHoldFirstContraction() },
                onStop = {
                    stopRequested = true
                    viewModel.stopFreeHold()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeHoldActiveContent(
    freeHoldActive: Boolean,
    showTimer: Boolean,
    firstContractionMs: Long?,
    currentPbCategory: PersonalBestCategory? = null,
    nextPbTarget: NextPbTarget? = null,
    guidedHyperActive: Boolean = false,
    guidedCountdownComplete: Boolean = false,
    modifier: Modifier = Modifier,
    onShowTimerChange: (Boolean) -> Unit = {},
    onStart: () -> Unit,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val holdStartWallClock = remember { mutableLongStateOf(0L) }

    LaunchedEffect(freeHoldActive) {
        if (freeHoldActive) {
            holdStartWallClock.longValue = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - holdStartWallClock.longValue
                delay(50L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Timer display ─────────────────────────────────────────────────────
        if (freeHoldActive && showTimer) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = formatElapsedMs(elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (freeHoldActive) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "HOLD",
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── PB trophy display during hold ────────────────────────────────────
        if (freeHoldActive && currentPbCategory != null) {
            Text(
                text = currentPbCategory.trophyEmojis(),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.grayscale()
            )
        }

        // ── Next PB countdown during hold ────────────────────────────────────
        if (freeHoldActive && nextPbTarget != null) {
            val countdownText = formatCountdownMs(nextPbTarget.remainingMs)
            val trophyPreview = "🏆".repeat(nextPbTarget.category.trophyCount())
            Text(
                text = "$trophyPreview in $countdownText",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        // ── Main button area ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                !freeHoldActive -> {
                    // Determine button label based on guided hyper state
                    val showHyperStart = guidedHyperActive && !guidedCountdownComplete

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onStart,
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .fillMaxHeight(0.75f),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonSuccess,
                                contentColor = TextPrimary
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "START",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                if (showHyperStart) {
                                    Text(
                                        text = "HYPER",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "Show timer",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Checkbox(checked = showTimer, onCheckedChange = onShowTimerChange)
                        }
                    }
                }

                freeHoldActive && firstContractionMs == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onFirstContraction,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ReadinessOrange,
                                contentColor = TextPrimary
                            )
                        ) {
                            Text(
                                text = "First\nContraction",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonDanger,
                                contentColor = TextPrimary
                            )
                        ) {
                            Text(
                                text = "Stop",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonDanger,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsedMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0)
        "${minutes}m ${seconds}s"
    else
        "${seconds}.${centis.toString().padStart(2, '0')}s"
}

/** Formats a countdown duration for the next-PB indicator (e.g. "1m 23s" or "45s"). */
private fun formatCountdownMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0)
        "${minutes}m ${seconds}s"
    else
        "${seconds}s"
}

// ─────────────────────────────────────────────────────────────────────────────
// Guided Hyperventilation Section
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Checkbox + edit icon for guided hyperventilation.
 * Shown above the start button when prep type is HYPER.
 * The edit icon opens a bottom sheet to configure the phase durations.
 */
@Composable
private fun GuidedHyperSection(
    enabled: Boolean,
    relaxedExhaleSec: Int,
    purgeExhaleSec: Int,
    transitionSec: Int,
    showStartMp3WithHyper: Boolean = false,
    startMp3WithHyper: Boolean = false,
    onEnabledChange: (Boolean) -> Unit,
    onStartMp3WithHyperChange: (Boolean) -> Unit = {},
    onEditClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEnabledChange(!enabled) }
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.size(32.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = BackgroundDark
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Guided Hyperventilation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            // Edit icon — always visible so user can configure even when disabled
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit guided hyperventilation settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Summary of configured durations when enabled
        if (enabled) {
            val parts = buildList {
                if (relaxedExhaleSec > 0) add("Relaxed ${relaxedExhaleSec}s")
                if (purgeExhaleSec > 0) add("Purge ${purgeExhaleSec}s")
                if (transitionSec > 0) add("Transition ${transitionSec}s")
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 36.dp, bottom = 2.dp)
                )
            }

            // "Start MP3 with Hyper" checkbox — only shown when audio is GUIDED
            if (showStartMp3WithHyper) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 32.dp)
                        .clickable { onStartMp3WithHyperChange(!startMp3WithHyper) }
                ) {
                    Checkbox(
                        checked = startMp3WithHyper,
                        onCheckedChange = onStartMp3WithHyperChange,
                        modifier = Modifier.size(28.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = TextPrimary,
                            uncheckedColor = TextSecondary,
                            checkmarkColor = BackgroundDark
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Start MP3 with Hyper",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Bottom sheet for editing the three guided hyperventilation phase durations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedHyperEditSheet(
    relaxedExhaleSec: Int,
    purgeExhaleSec: Int,
    transitionSec: Int,
    onRelaxedExhaleChange: (Int) -> Unit,
    onPurgeExhaleChange: (Int) -> Unit,
    onTransitionChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Guided Hyperventilation",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                "Set the duration (in seconds) for each phase. Set to 0 to skip a phase.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            GuidedHyperPhaseRow(
                label = "Relaxed Exhale",
                value = relaxedExhaleSec,
                onValueChange = onRelaxedExhaleChange
            )
            GuidedHyperPhaseRow(
                label = "Purge Exhale",
                value = purgeExhaleSec,
                onValueChange = onPurgeExhaleChange
            )
            GuidedHyperPhaseRow(
                label = "Transition",
                value = transitionSec,
                onValueChange = onTransitionChange
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text("Done")
            }
        }
    }
}

/**
 * A single row in the edit sheet: label on the left, numeric input on the right.
 */
@Composable
private fun GuidedHyperPhaseRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(40.dp)
                .border(1.dp, SurfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = if (value == 0) "" else value.toString(),
                onValueChange = { text ->
                    val parsed = text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                    onValueChange(parsed)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center,
                    color = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                cursorBrush = SolidColor(TextPrimary)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// New Record Indication Section
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Checkbox + sub-options for real-time PB indication during free holds.
 * When the master toggle is on, sound and vibration sub-options become available.
 */
@Composable
private fun PbIndicationSection(
    enabled: Boolean,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEnabledChange(!enabled) }
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.size(32.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = TextPrimary,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = BackgroundDark
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "New Record Indication",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }

        if (enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 32.dp)
                    .clickable { onSoundChange(!soundEnabled) }
            ) {
                Checkbox(
                    checked = soundEnabled,
                    onCheckedChange = onSoundChange,
                    modifier = Modifier.size(28.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = BackgroundDark
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Sound",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 32.dp)
                    .clickable { onVibrationChange(!vibrationEnabled) }
            ) {
                Checkbox(
                    checked = vibrationEnabled,
                    onCheckedChange = onVibrationChange,
                    modifier = Modifier.size(28.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = BackgroundDark
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Vibration",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

