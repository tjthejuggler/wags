package com.example.wags.ui.apnea

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.data.spotify.SpotifyApiClient
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.RecordPbBadge
import com.example.wags.domain.model.SpotifySong
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class ApneaRecordDetailUiState(
    val record: ApneaRecordEntity? = null,
    val telemetry: List<FreeHoldTelemetryEntity> = emptyList(),
    val pbBadges: List<RecordPbBadge> = emptyList(),
    val songLog: List<SpotifySong> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    /** True while the edit bottom-sheet is open. */
    val showEditSheet: Boolean = false,
    /** Editable copies of the settings fields — initialised from the record when sheet opens. */
    val editLungVolume: String = "FULL",
    val editPrepType: PrepType = PrepType.NO_PREP,
    val editTimeOfDay: TimeOfDay = TimeOfDay.DAY,
    val editPosture: Posture = Posture.LAYING,
    val editAudio: AudioSetting = AudioSetting.SILENCE,
    /** Editable device label — null means "None recorded". */
    val editHrDeviceId: String? = null,
    /** All device labels ever used, for the edit dropdown. */
    val deviceLabelOptions: List<String> = emptyList(),
    /** Matching session entity for table records (null for free holds). */
    val tableSession: ApneaSessionEntity? = null
)

@HiltViewModel
class ApneaRecordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apneaRepository: ApneaRepository,
    private val sessionRepository: ApneaSessionRepository,
    private val devicePrefs: DevicePreferencesRepository,
    private val spotifyApiClient: SpotifyApiClient,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val recordId: Long = savedStateHandle.get<Long>("recordId") ?: -1L

    private val _uiState = MutableStateFlow(ApneaRecordDetailUiState())
    val uiState: StateFlow<ApneaRecordDetailUiState> = _uiState.asStateFlow()

    /** Emits Unit when the record has been deleted — the screen should pop back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch {
            val record = apneaRepository.getById(recordId)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true) }
            } else {
                val telemetry = apneaRepository.getTelemetryForRecord(recordId)
                val badges = apneaRepository.getRecordPbBadges(recordId)
                val songLog = apneaRepository.getSongLogForRecord(recordId)
                // For table records, find the matching session entity
                val tableSession = if (record.tableType != null) {
                    sessionRepository.getSessionByTimestampAndType(record.timestamp, record.tableType)
                } else null
                _uiState.update {
                    it.copy(
                        record = record,
                        telemetry = telemetry,
                        pbBadges = badges,
                        songLog = songLog,
                        tableSession = tableSession,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun deleteRecord() {
        viewModelScope.launch {
            apneaRepository.deleteRecord(recordId)
            _deleted.emit(Unit)
        }
    }

    // ── Edit sheet ────────────────────────────────────────────────────────────

    /** Opens the edit sheet, pre-populating fields from the current record. */
    fun openEditSheet() {
        val record = _uiState.value.record ?: return
        val prepType = runCatching { PrepType.valueOf(record.prepType) }.getOrDefault(PrepType.NO_PREP)
        val timeOfDay = runCatching { TimeOfDay.valueOf(record.timeOfDay) }.getOrDefault(TimeOfDay.DAY)
        val posture = runCatching { Posture.valueOf(record.posture) }.getOrDefault(Posture.LAYING)
        val audio = runCatching { AudioSetting.valueOf(record.audio) }.getOrDefault(AudioSetting.SILENCE)
        // Build the device label options: all labels from history, plus the
        // record's current label if it's not already in the list.
        val historyLabels = devicePrefs.deviceLabelHistory.toMutableList()
        record.hrDeviceId?.let { current ->
            if (current !in historyLabels) historyLabels.add(current)
        }
        _uiState.update {
            it.copy(
                showEditSheet      = true,
                editLungVolume     = record.lungVolume,
                editPrepType       = prepType,
                editTimeOfDay      = timeOfDay,
                editPosture        = posture,
                editAudio          = audio,
                editHrDeviceId     = record.hrDeviceId,
                deviceLabelOptions = historyLabels
            )
        }
    }

    fun closeEditSheet() {
        _uiState.update { it.copy(showEditSheet = false) }
    }

    fun setEditLungVolume(volume: String) {
        _uiState.update { it.copy(editLungVolume = volume) }
    }

    fun setEditPrepType(type: PrepType) {
        _uiState.update { it.copy(editPrepType = type) }
    }

    fun setEditTimeOfDay(tod: TimeOfDay) {
        _uiState.update { it.copy(editTimeOfDay = tod) }
    }

    fun setEditPosture(posture: Posture) {
        _uiState.update { it.copy(editPosture = posture) }
    }

    fun setEditAudio(audio: AudioSetting) {
        _uiState.update { it.copy(editAudio = audio) }
    }

    fun setEditHrDeviceId(deviceId: String?) {
        _uiState.update { it.copy(editHrDeviceId = deviceId) }
    }

    /** Persists the edited settings to the DB and refreshes the displayed record + PB badges. */
    fun saveEdits() {
        val record = _uiState.value.record ?: return
        val state  = _uiState.value
        viewModelScope.launch {
            val updated = record.copy(
                lungVolume = state.editLungVolume,
                prepType   = state.editPrepType.name,
                timeOfDay  = state.editTimeOfDay.name,
                posture    = state.editPosture.name,
                audio      = state.editAudio.name,
                hrDeviceId = state.editHrDeviceId
            )
            apneaRepository.updateRecord(updated)
            val badges = apneaRepository.getRecordPbBadges(recordId)
            _uiState.update { it.copy(record = updated, pbBadges = badges, showEditSheet = false) }
        }
    }

    // ── Repeat hold ───────────────────────────────────────────────────────────

    /**
     * Writes the current record's settings into SharedPreferences so the
     * FreeHoldActiveScreen picks them up. Also persists the settings so the
     * ApneaScreen stays in sync on future visits.
     * Time of Day is NOT written — it stays based on the current clock time.
     * If the record used MUSIC audio and has a song log, the first song's
     * Spotify URI / title / artist are stored as a "pending repeat song"
     * for the FreeHoldActiveViewModel to auto-load.
     * If the record used guided hyperventilation, those settings are also written.
     */
    fun prepareRepeatHold() {
        val record = _uiState.value.record ?: return
        val songLog = _uiState.value.songLog
        prefs.edit().apply {
            putString("setting_lung_volume", record.lungVolume)
            putString("setting_prep_type", record.prepType)
            putString("setting_posture", record.posture)
            putString("setting_audio", record.audio)
            // Guided hyperventilation settings
            if (record.guidedHyper) {
                putBoolean("guided_hyper_enabled", true)
                record.guidedRelaxedExhaleSec?.let { putInt("guided_relaxed_exhale_sec", it) }
                record.guidedPurgeExhaleSec?.let { putInt("guided_purge_exhale_sec", it) }
                record.guidedTransitionSec?.let { putInt("guided_transition_sec", it) }
            }
            // Store pending repeat song when audio was MUSIC and a song was logged
            if (record.audio == AudioSetting.MUSIC.name && songLog.isNotEmpty()) {
                val song = songLog.first()
                putString("pending_repeat_song_uri", song.spotifyUri ?: "")
                putString("pending_repeat_song_title", song.title)
                putString("pending_repeat_song_artist", song.artist)
            } else {
                remove("pending_repeat_song_uri")
                remove("pending_repeat_song_title")
                remove("pending_repeat_song_artist")
            }
            apply()
        }
    }

    /**
     * Returns the navigation route parameters for the FreeHoldActiveScreen
     * based on the current record's settings. Time of Day uses the current
     * clock time instead of the record's value.
     */
    fun repeatHoldRoute(): String? {
        val record = _uiState.value.record ?: return null
        val showTimer = prefs.getBoolean("setting_show_timer", true)
        return "free_hold_active/${record.lungVolume}/${record.prepType}/" +
            "${TimeOfDay.fromCurrentTime().name}/${record.posture}/$showTimer/${record.audio}"
    }

    // ── Recalculate song times ──────────────────────────────────────────────

    /**
     * Recalculates song start times for this record using actual Spotify
     * track durations. For each song that has a non-null endedAtMs and a
     * Spotify URI, looks up the real duration from the Spotify API and sets
     * startedAtMs = endedAtMs - realDurationMs. This fixes records where
     * startedAtMs was incorrectly set to the song pre-load time instead of
     * the hold start time.
     *
     * After updating the DB, refreshes the UI state's songLog.
     */
    fun recalculateSongTimes() {
        viewModelScope.launch {
            val entities = apneaRepository.getSongLogEntitiesForRecord(recordId)
            if (entities.isEmpty()) return@launch

            var anyUpdated = false
            for (entity in entities) {
                val uri = entity.spotifyUri
                val endMs = entity.endedAtMs
                if (uri.isNullOrBlank() || endMs == null) continue

                val detail = spotifyApiClient.getTrackDetail(uri)
                if (detail == null || detail.durationMs <= 0L) {
                    Log.w("RecordDetail", "Could not get duration for $uri")
                    continue
                }

                val correctedStart = endMs - detail.durationMs
                // Only update if the corrected start is meaningfully different
                // (more than 2 seconds off) to avoid unnecessary writes
                if (kotlin.math.abs(correctedStart - entity.startedAtMs) > 2_000L) {
                    apneaRepository.updateSongLogStartedAt(entity.songLogId, correctedStart)
                    anyUpdated = true
                    Log.d("RecordDetail", "Recalculated ${entity.title}: " +
                        "old start=${entity.startedAtMs}, new start=$correctedStart, " +
                        "duration=${detail.durationMs}ms")
                }
            }

            if (anyUpdated) {
                // Refresh the song log from DB
                val refreshed = apneaRepository.getSongLogForRecord(recordId)
                _uiState.update { it.copy(songLog = refreshed) }
            }
        }
    }
}
