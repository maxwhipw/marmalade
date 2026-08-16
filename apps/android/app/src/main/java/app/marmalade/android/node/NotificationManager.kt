package app.marmalade.android.node

import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

class NotificationManager {
    private val activeNotifications = ConcurrentHashMap<String, StatusBarNotification>()

    fun onNotificationPosted(sbn: StatusBarNotification) {
        val key = sbn.key
        activeNotifications[key] = sbn
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        activeNotifications.remove(key)
    }

    fun getActiveNotifications(): List<StatusBarNotification> {
        return activeNotifications.values.toList()
    }

    fun getNotification(key: String): StatusBarNotification? {
        return activeNotifications[key]
    }
}
