package app.marmalade.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Centralized notification channel management for Marmalade.
 *
 * - Per-session channels: created on-demand when a notification fires for a session.
 *   Gives users granular Android-native notification control per conversation.
 * - Persistent channel: low-importance channel for the combined foreground service
 *   notification (gateway connection + wake word status).
 */
object NotificationChannelManager {

    const val PERSISTENT_CHANNEL_ID = "marmalade_status"
    const val PERSISTENT_CHANNEL_NAME = "Connection Status"

    /**
     * Generates a deterministic, unique channel ID for a given session key.
     * Uses the session key's hashCode (masked to positive int) as a suffix.
     */
    fun sessionChannelId(sessionKey: String): String {
        return "chat_session_${sessionKey.hashCode().and(0x7FFFFFFF)}"
    }

    /**
     * Ensures a per-session notification channel exists. Creates or updates it.
     * Returns the channel ID for use in NotificationCompat.Builder.
     *
     * @param context Application or service context
     * @param sessionKey Gateway session key
     * @param displayName Human-readable session name for the channel
     * @return The channel ID string
     */
    fun ensurePerSessionChannel(context: Context, sessionKey: String, displayName: String): String {
        val channelId = sessionChannelId(sessionKey)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            displayName.ifBlank { sessionKey },
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Messages from $displayName"
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        return channelId
    }

    /**
     * Deletes the notification channel for a session.
     * Called when a session is deleted to clean up Android system channels.
     */
    fun deleteSessionChannel(context: Context, sessionKey: String) {
        val channelId = sessionChannelId(sessionKey)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(channelId)
    }

    /**
     * Ensures the persistent (foreground service) notification channel exists.
     * Low importance, no badge -- used for the combined gateway + wake word status notification.
     */
    fun ensurePersistentChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            PERSISTENT_CHANNEL_ID,
            PERSISTENT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Gateway connection and voice wake status"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
