package dev.customhud.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.customhud.CustomHudApplication

/**
 * Optional, user-enabled notification access. Used only to read media notifications and,
 * where direct session access is denied, as the component for getActiveSessions().
 */
class MediaNotificationListener : NotificationListenerService() {
    private val monitor get() = (application as CustomHudApplication).media

    override fun onListenerConnected() {
        val active = try {
            activeNotifications
        } catch (_: RuntimeException) {
            null
        }
        monitor.onListenerConnected(active)
    }

    override fun onListenerDisconnected() {
        monitor.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(monitor::onNotification)
    }
}
