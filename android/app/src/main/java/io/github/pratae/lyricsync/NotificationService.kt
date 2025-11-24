package io.github.pratae.lyricsync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        syncFromActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        if (sbn == null) return
        
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getString(Notification.EXTRA_TEXT)
        val packageName = sbn.packageName

        // Basic filter: ignore system notifications or empty ones
        if (title.isNullOrBlank() || text.isNullOrBlank()) return
        if (packageName == "android" || packageName == "com.android.systemui") return

        // Heuristic: Music notifications often are ongoing and might have media session
        // For now, we log everything that looks like it might be a song.
        // In a production app, allowlist specific music player packages (Spotify, YouTube Music, etc.)
        
        Log.d("LyricSync", "Notification from $packageName: $title - $text")

        // Send to SyncManager
        SyncManager.updateTrackInfo(title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun syncFromActiveNotifications() {
        val snapshot = activeNotifications ?: return
        for (index in snapshot.indices.reversed()) {
            val sbn = snapshot[index]
            val extras = sbn.notification.extras
            val title = extras?.getString(Notification.EXTRA_TITLE)
            val text = extras?.getString(Notification.EXTRA_TEXT)
            if (title.isNullOrBlank() || text.isNullOrBlank()) continue
            if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") continue
            SyncManager.updateTrackInfo(title, text)
            break
        }
    }
}
