package com.example.wags.data.spotify

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * NotificationListenerService subclass that:
 *  1. Provides the [MediaSessionManager] token required to call
 *     [MediaSessionManager.getActiveSessions] — this is the only way to read
 *     MediaSession metadata from other apps on Android 5+.
 *  2. Forwards active session changes to [SpotifyManager] so it can attach a
 *     [MediaController.Callback] to Spotify's session for real-time track detection.
 *
 * The user must grant Notification Access to this app in:
 *   Settings → Apps → Special app access → Notification access
 *
 * Without this grant, song detection is unavailable (play command still works).
 */
@AndroidEntryPoint
class MediaNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var spotifyManager: SpotifyManager

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Register for session changes and do an initial read
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionManager?.addOnActiveSessionsChangedListener(
            ::onSessionsChanged,
            android.content.ComponentName(this, MediaNotificationListener::class.java)
        )
        // Initial read of current sessions
        try {
            val controllers = sessionManager?.getActiveSessions(
                android.content.ComponentName(this, MediaNotificationListener::class.java)
            ) ?: emptyList()
            spotifyManager.onActiveSessionsChanged(controllers)
        } catch (_: SecurityException) { /* permission not yet granted */ }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionManager?.removeOnActiveSessionsChangedListener(::onSessionsChanged)
    }

    private fun onSessionsChanged(controllers: List<MediaController>?) {
        spotifyManager.onActiveSessionsChanged(controllers ?: emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
