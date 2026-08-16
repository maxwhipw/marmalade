package app.marmalade.android.node

import android.content.Context
import android.provider.Settings
import app.marmalade.android.PermissionRequester
import app.marmalade.android.rpc.InvokeResult
import app.marmalade.android.service.MarmaladeNotificationListenerService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class NotificationsHandler(
    private val context: Context,
    private val notificationManager: NotificationManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    fun isServiceEnabled(): Boolean {
        val enabledPackages = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabledPackages?.contains(context.packageName) == true
    }

    suspend fun handleList(): InvokeResult {
        if (!isServiceEnabled()) {
            permissionRequester?.requestNotificationAccess()
            return InvokeResult.error(
                code = "NOTIFICATIONS_PERMISSION_REQUIRED",
                message = "NOTIFICATIONS_PERMISSION_REQUIRED: enable notification access in Settings > Notification Access, then try again"
            )
        }

        val notifications = notificationManager.getActiveNotifications()
        val payload = buildJsonObject {
            put("notifications", buildJsonArray {
                notifications.forEach { sbn ->
                    add(buildJsonObject {
                        put("key", JsonPrimitive(sbn.key))
                        put("packageName", JsonPrimitive(sbn.packageName))
                        put("title", JsonPrimitive(sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()))
                        put("text", JsonPrimitive(sbn.notification.extras.getCharSequence("android.text")?.toString().orEmpty()))
                        put("postTime", JsonPrimitive(sbn.postTime))
                    })
                }
            })
        }
        return InvokeResult.ok(payload.toString())
    }

    suspend fun handleActions(paramsJson: String?): InvokeResult {
        if (!isServiceEnabled()) {
            permissionRequester?.requestNotificationAccess()
            return InvokeResult.error(
                code = "NOTIFICATIONS_PERMISSION_REQUIRED",
                message = "NOTIFICATIONS_PERMISSION_REQUIRED: enable notification access in Settings > Notification Access, then try again"
            )
        }

        val service = MarmaladeNotificationListenerService.instance
            ?: return InvokeResult.error("SERVICE_UNAVAILABLE", "Notification Listener Service not running")

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val key = (params["key"] as? JsonPrimitive)?.content ?: ""
        val action = (params["action"] as? JsonPrimitive)?.content ?: ""

        if (key.isEmpty() || action.isEmpty()) {
            return InvokeResult.error("INVALID_REQUEST", "Key and action are required")
        }

        return when (action.lowercase()) {
            "dismiss" -> {
                try {
                    service.cancelNotification(key)
                    InvokeResult.ok("""{"ok":true}""")
                } catch (e: Exception) {
                    InvokeResult.error("ACTION_FAILED", "Failed to dismiss: ${e.message}")
                }
            }
            "dismiss_all" -> {
                try {
                    service.cancelAllNotifications()
                    InvokeResult.ok("""{"ok":true}""")
                } catch (e: Exception) {
                    InvokeResult.error("ACTION_FAILED", "Failed to dismiss all: ${e.message}")
                }
            }
            "open" -> {
                val sbn = notificationManager.getNotification(key)
                if (sbn != null) {
                    try {
                        sbn.notification.contentIntent.send()
                        InvokeResult.ok("""{"ok":true}""")
                    } catch (e: Exception) {
                        InvokeResult.error("ACTION_FAILED", "Failed to open: ${e.message}")
                    }
                } else {
                    InvokeResult.error("NOT_FOUND", "Notification not found")
                }
            }
            "reply" -> InvokeResult.error("NOT_IMPLEMENTED", "Reply action not yet implemented")
            else -> InvokeResult.error("INVALID_REQUEST", "Unsupported action: $action")
        }
    }
}
