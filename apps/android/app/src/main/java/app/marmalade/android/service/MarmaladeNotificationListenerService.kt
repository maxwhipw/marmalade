package app.marmalade.android.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.marmalade.android.node.NotificationManager

/**
 * Captures notifications for the Marmalade system.
 * Requires BIND_NOTIFICATION_LISTENER_SERVICE permission and user to enable it in Settings.
 */
class MarmaladeNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile var manager: NotificationManager? = null
        @Volatile var instance: MarmaladeNotificationListenerService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { manager?.onNotificationPosted(it) }
        Log.d("MarmaladeNotification", "Notification posted from ${sbn?.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let { manager?.onNotificationRemoved(it) }
        Log.d("MarmaladeNotification", "Notification removed from ${sbn?.packageName}")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        activeNotifications?.forEach { sbn ->
            manager?.onNotificationPosted(sbn)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }
}
