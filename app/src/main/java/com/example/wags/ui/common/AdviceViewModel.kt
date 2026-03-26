package com.example.wags.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wags.data.db.entity.AdviceEntity
import com.example.wags.data.repository.AdviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdviceUiState(
    /** All advice items keyed by section. */
    val adviceBySection: Map<String, List<AdviceEntity>> = emptyMap(),
    /** Current random-index per section for the banner display. */
    val currentIndex: Map<String, Int> = emptyMap(),
    /** History of shown indices per section (for swipe-back). */
    val history: Map<String, List<Int>> = emptyMap()
)

@HiltViewModel
class AdviceViewModel @Inject constructor(
    private val repo: AdviceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AdviceUiState())
    val state: StateFlow<AdviceUiState> = _state

    init {
        // Observe all five sections
        AdviceSection.all.forEach { section ->
            viewModelScope.launch {
                repo.observeBySection(section).collect { list ->
                    _state.update { old ->
                        val newMap = old.adviceBySection.toMutableMap()
                        newMap[section] = list
                        // If current index is out of bounds, reset
                        val idx = old.currentIndex[section] ?: 0
                        val newIdx = if (list.isEmpty()) 0 else idx.coerceIn(0, list.size - 1)
                        val newIndexMap = old.currentIndex.toMutableMap()
                        newIndexMap[section] = newIdx
                        old.copy(adviceBySection = newMap, currentIndex = newIndexMap)
                    }
                }
            }
        }
    }

    /** Get the current advice text for a section, or null if none. */
    fun currentAdvice(section: String): AdviceEntity? {
        val s = _state.value
        val list = s.adviceBySection[section] ?: return null
        if (list.isEmpty()) return null
        val idx = s.currentIndex[section] ?: 0
        return list.getOrNull(idx)
    }

    /** Called on screen entry – picks a fresh random index for [section]. */
    fun randomizeOnEntry(section: String) {
        _state.update { old ->
            val list = old.adviceBySection[section] ?: return@update old
            if (list.isEmpty()) return@update old
            val newIdx = (0 until list.size).random()
            val newIndexMap = old.currentIndex.toMutableMap()
            newIndexMap[section] = newIdx
            old.copy(currentIndex = newIndexMap)
        }
    }

    /** Swipe right → show next random advice (not the same as current). */
    fun nextRandom(section: String) {
        _state.update { old ->
            val list = old.adviceBySection[section] ?: return@update old
            if (list.size <= 1) return@update old
            val currentIdx = old.currentIndex[section] ?: 0
            // Pick a random index different from current
            var newIdx: Int
            do {
                newIdx = (0 until list.size).random()
            } while (newIdx == currentIdx)
            val newIndexMap = old.currentIndex.toMutableMap()
            newIndexMap[section] = newIdx
            // Push current to history
            val sectionHistory = (old.history[section] ?: emptyList()) + currentIdx
            val newHistory = old.history.toMutableMap()
            newHistory[section] = sectionHistory
            old.copy(currentIndex = newIndexMap, history = newHistory)
        }
    }

    /** Swipe left → go back to previously shown advice. */
    fun previous(section: String) {
        _state.update { old ->
            val sectionHistory = old.history[section] ?: return@update old
            if (sectionHistory.isEmpty()) return@update old
            val prevIdx = sectionHistory.last()
            val newHistory = old.history.toMutableMap()
            newHistory[section] = sectionHistory.dropLast(1)
            val newIndexMap = old.currentIndex.toMutableMap()
            newIndexMap[section] = prevIdx
            old.copy(currentIndex = newIndexMap, history = newHistory)
        }
    }

    // ── Settings dialog operations ────────────────────────────────────────────

    fun addAdvice(section: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repo.add(section, text.trim()) }
    }

    fun updateAdvice(entity: AdviceEntity, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch { repo.update(entity.copy(text = newText.trim())) }
    }

    fun deleteAdvice(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }
}
