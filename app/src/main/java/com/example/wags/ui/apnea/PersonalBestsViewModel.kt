package com.example.wags.ui.apnea

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.PersonalBestEntry
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class PersonalBestsUiState(
    val entries: List<PersonalBestEntry> = emptyList(),
    /** The exact-settings entry for the user's current apnea settings. */
    val currentSettingsEntry: PersonalBestEntry? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class PersonalBestsViewModel @Inject constructor(
    private val apneaRepository: ApneaRepository,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonalBestsUiState())
    val uiState: StateFlow<PersonalBestsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val entries = apneaRepository.getAllPersonalBests()

            // Read current settings from SharedPreferences
            val lv = prefs.getString("setting_lung_volume", "FULL") ?: "FULL"
            val pt = runCatching {
                PrepType.valueOf(prefs.getString("setting_prep_type", "NO_PREP") ?: "NO_PREP")
            }.getOrDefault(PrepType.NO_PREP).name
            val tod = TimeOfDay.fromCurrentTime().name
            val pos = runCatching {
                Posture.valueOf(prefs.getString("setting_posture", "LAYING") ?: "LAYING")
            }.getOrDefault(Posture.LAYING).name
            val aud = runCatching {
                AudioSetting.valueOf(prefs.getString("setting_audio", "SILENCE") ?: "SILENCE")
            }.getOrDefault(AudioSetting.SILENCE).name

            // Find the exact-settings (1🏆) entry matching current settings
            val currentEntry = entries.find { e ->
                e.trophyCount == 1 &&
                    e.lungVolume == lv && e.prepType == pt &&
                    e.timeOfDay == tod && e.posture == pos && e.audio == aud
            }

            _uiState.update {
                it.copy(entries = entries, currentSettingsEntry = currentEntry, isLoading = false)
            }
        }
    }
}
