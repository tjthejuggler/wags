package com.example.wags.ui.meditation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bridges share-sheet YouTube URLs (received by MainActivity's ACTION_SEND
 * handling) to the navigation graph.
 *
 * The URL itself travels in [pendingUrl] rather than being encoded into the
 * nav route (avoids URL double-decoding issues); [requests] pulses once per
 * share so the nav graph knows when to navigate to the import screen.
 *
 * [replay][MutableSharedFlow.replay] of 1 guarantees the pulse is still
 * delivered when the very first share arrives before composition starts
 * (cold start via the share sheet).
 */
object AudioImportBus {

    /** The most recently shared YouTube URL; consumed by AudioImportScreen. */
    var pendingUrl: String? = null
        private set

    private val _requests = MutableSharedFlow<Unit>(replay = 1)
    val requests: SharedFlow<Unit> = _requests

    fun request(url: String) {
        pendingUrl = url
        _requests.tryEmit(Unit)
    }

    /** Returns the pending URL and clears it (called when the user picks a category). */
    fun consumePendingUrl(): String? = pendingUrl.also { pendingUrl = null }
}
