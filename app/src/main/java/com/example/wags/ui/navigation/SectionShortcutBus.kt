package com.example.wags.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bridges launcher app-shortcut intents (received by MainActivity's
 * ACTION_OPEN_SECTION handling) to the navigation graph.
 *
 * Mirrors the [com.example.wags.ui.meditation.AudioImportBus] pattern:
 * [replay][MutableSharedFlow.replay] of 1 guarantees the pulse is still
 * delivered when a shortcut triggers a cold start, before composition
 * begins collecting.
 */
object SectionShortcutBus {

    /** Intent action used by the static shortcuts in res/xml/shortcuts.xml. */
    const val ACTION_OPEN_SECTION = "com.example.wags.action.OPEN_SECTION"

    /** Intent extra carrying the section id from the static shortcut. */
    const val EXTRA_SECTION = "section"

    private val _requests = MutableSharedFlow<String>(replay = 1)

    /** Emits the section id each time a shortcut intent is handled. */
    val requests: SharedFlow<String> = _requests

    /** Maps a shortcut section id to its nav route. Returns null for unknown ids. */
    fun routeFor(section: String): String? = when (section) {
        "apnea" -> WagsRoutes.APNEA_FREE
        "morning_readiness" -> WagsRoutes.MORNING_READINESS
        "hrv_readiness" -> WagsRoutes.READINESS
        "meditation" -> WagsRoutes.MEDITATION
        "resonance_breathing" -> WagsRoutes.BREATHING
        else -> null
    }

    /** Called by MainActivity when a shortcut intent arrives. */
    fun request(section: String) {
        _requests.tryEmit(section)
    }
}
