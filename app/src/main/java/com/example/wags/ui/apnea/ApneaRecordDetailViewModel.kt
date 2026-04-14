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
    val tableSession: ApneaSessionEntity? = null,
    /** All record IDs ordered oldest-first, for swipe navigation.
     *  Swipe right (lower index) = newer; swipe left (higher index) = older. */
    val allRecordIds: List<Long> = emptyList(),
    /** Index of the currently displayed record in [allRecordIds]. */
    val currentIndex: Int = 0
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

    /** Emits Unit when the last record has been deleted — the screen should pop back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch {
            // Oldest-first: swipe right = newer (lower index), swipe left = older (higher index)
            // getAllRecordsOnce() returns ASC (oldest first) — no reversal needed
            val allRecords = apneaRepository.getAllRecordsOnce()
            val allIds = allRecords.map { it.recordId }
            val startIndex = allIds.indexOf(recordId).coerceAtLeast(0)

            val record = apneaRepository.getById(recordId)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true, allRecordIds = allIds, currentIndex = startIndex) }
            } else {
                val telemetry = apneaRepository.getTelemetryForRecord(recordId)
                val badges = apneaRepository.getRecordPbBadges(recordId)
                val songLog = apneaRepository.getSongLogForRecord(recordId)
                val tableSession = if (record.tableType != null) {
                    sessionRepository.getSessionByTimestampAndType(record.timestamp, record.tableType)
                } else null
                _uiState.update {
                    it.copy(
                        record       = record,
                        telemetry    = telemetry,
                        pbBadges     = badges,
                        songLog      = songLog,
                        tableSession = tableSession,
                        isLoading    = false,
                        allRecordIds = allIds,
                        currentIndex = startIndex
                    )
                }
            }
        }
    }

    /** Called when the user swipes to a different page index. */
    fun navigateToIndex(index: Int) {
        val ids = _uiState.value.allRecordIds
        if (index < 0 || index >= ids.size) return
        if (index == _uiState.value.currentIndex) return

        _uiState.update { it.copy(isLoading = true, currentIndex = index) }
        viewModelScope.launch {
            val id = ids[index]
            val record = apneaRepository.getById(id)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true) }
            } else {
                val telemetry = apneaRepository.getTelemetryForRecord(id)
                val badges = apneaRepository.getRecordPbBadges(id)
                val songLog = apneaRepository.getSongLogForRecord(id)
                val tableSession = if (record.tableType != null) {
                    sessionRepository.getSessionByTimestampAndType(record.timestamp, record.tableType)
                } else null
                _uiState.update {
                    it.copy(
                        record       = record,
                        telemetry    = telemetry,
                        pbBadges     = badges,
                        songLog      = songLog,
                        tableSession = tableSession,
                        isLoading    = false
                    )
                }
            }
        }
    }

    fun deleteRecord() {
        val id = _uiState.value.record?.recordId ?: recordId
        val currentIds = _uiState.value.allRecordIds
        val currentIdx = _uiState.value.currentIndex

        viewModelScope.launch {
            apneaRepository.deleteRecord(id)

            val newIds = currentIds.filter { it != id }
            if (newIds.isEmpty()) {
                _deleted.emit(Unit)
                return@launch
            }

            val newIndex = currentIdx.coerceAtMost(newIds.size - 1)
            val nextId = newIds[newIndex]
            val record = apneaRepository.getById(nextId)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true, allRecordIds = newIds, currentIndex = newIndex) }
            } else {
                val telemetry = apneaRepository.getTelemetryForRecord(nextId)
                val badges = apneaRepository.getRecordPbBadges(nextId)
                val songLog = apneaRepository.getSongLogForRecord(nextId)
                val tableSession = if (record.tableType != null) {
                    sessionRepository.getSessionByTimestampAndType(record.timestamp, record.tableType)
                } else null
                _uiState.update {
                    it.copy(
                        record       = record,
                        telemetry    = telemetry,
                        pbBadges     = badges,
                        songLog      = songLog,
                        tableSession = tableSession,
                        isLoading    = false,
                        allRecordIds = newIds,
                        currentIndex = newIndex
                    )
                }
            }
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
            val badges = apneaRepository.getRecordPbBadges(record.recordId)
            _uiState.update { it.copy(record = updated, pbBadges = badges, showEditSheet = false) }
        }
    }

    // ── Repeat hold ───────────────────────────────────────────────────────────

    fun prepareRepeatHold() {
        val record = _uiState.value.record ?: return
        val songLog = _uiState.value.songLog
        prefs.edit().apply {
            putString("setting_lung_volume", record.lungVolume)
            putString("setting_prep_type", record.prepType)
            putString("setting_posture", record.posture)
            putString("setting_audio", record.audio)
            if (record.guidedHyper) {
                putBoolean("guided_hyper_enabled", true)
                record.guidedRelaxedExhaleSec?.let { putInt("guided_relaxed_exhale_sec", it) }
                record.guidedPurgeExhaleSec?.let { putInt("guided_purge_exhale_sec", it) }
                record.guidedTransitionSec?.let { putInt("guided_transition_sec", it) }
            }
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

    fun repeatHoldRoute(): String? {
        val record = _uiState.value.record ?: return null
        val showTimer = prefs.getBoolean("setting_show_timer", true)
        return "free_hold_active/${record.lungVolume}/${record.prepType}/" +
            "${TimeOfDay.fromCurrentTime().name}/${record.posture}/$showTimer/${record.audio}"
    }

    // ── Recalculate song times ──────────────────────────────────────────────

    fun recalculateSongTimes() {
        viewModelScope.launch {
            val currentRecordId = _uiState.value.record?.recordId ?: recordId
            val entities = apneaRepository.getSongLogEntitiesForRecord(currentRecordId)
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
                if (kotlin.math.abs(correctedStart - entity.startedAtMs) > 2_000L) {
                    apneaRepository.updateSongLogStartedAt(entity.songLogId, correctedStart)
                    anyUpdated = true
                    Log.d("RecordDetail", "Recalculated ${entity.title}: " +
                        "old start=${entity.startedAtMs}, new start=$correctedStart, " +
                        "duration=${detail.durationMs}ms")
                }
            }

            if (anyUpdated) {
                val refreshed = apneaRepository.getSongLogForRecord(currentRecordId)
                _uiState.update { it.copy(songLog = refreshed) }
            }
        }
    }
}
