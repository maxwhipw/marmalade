package app.marmalade.android.notification

/**
 * Data Flow: Chat Notifications
 *
 * ChatController.handleChatEvent() (incoming "chat" event, state="final")
 *   -> ChatController detects sessionKey != current viewing session
 *     -> ChatNotificationHelper.showChatNotification()
 *       -> NotificationCompat.Builder + MessagingStyle + RemoteInput
 *         -> Tap -> PendingIntent -> MainActivity (navigate_to_session)
 *         -> Reply -> PendingIntent -> ChatNotificationReceiver
 *
 * ChatScreen enters composition for sessionKey
 *   -> ChatNotificationHelper.cancelNotification(sessionKey)
 *     -> Clears notification for the session the user is now viewing
 */

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.marmalade.android.MainActivity
import app.marmalade.android.R
import app.marmalade.android.chat.PendingPrompt

object ChatNotificationHelper {

    private const val TAG = "ChatNotification"
    private const val GROUP_KEY = "app.marmalade.CHAT_MESSAGES"
    const val REMOTE_INPUT_KEY = "reply_text"
    const val EXTRA_SESSION_KEY = "session_key"
    const val EXTRA_SESSION_DISPLAY_NAME = "session_display_name"

    // Tag prefixes used in notify(tag, id, …) / cancel(tag, id) calls.
    // Android disambiguates notifications by (tag, id) pair, so two notifications with the
    // same numeric id but different tags are entirely independent entries.
    // This eliminates any cross-session collision between the chat-message and prompt
    // id spaces regardless of hash values.
    internal const val TAG_CHAT_MSG_PREFIX = "chat-msg:"
    internal const val TAG_PROMPT_PREFIX = "prompt:"

    // Notification ID spaces:
    //   Foreground service: ID = 1 (NodeForegroundService)
    //   Chat messages:      hashCode(sessionKey) + 1_000  (notificationIdForSession)
    //   Prompt requests:    hashCode(sessionKey) + 2_000_000  (promptNotificationIdForSession)
    // The +2_000_000 offset is kept as defense-in-depth even though tag disambiguation
    // above already prevents collisions between the two spaces — it costs nothing to keep.

    /**
     * Shows a chat notification with MessagingStyle and inline reply support.
     * Creates a per-session notification channel on-demand via NotificationChannelManager.
     *
     * @param context Application context
     * @param sessionKey The gateway session key for this message
     * @param sessionDisplayName Human-readable session name for the notification
     * @param messageText The message text to display
     * @param senderName Name of the message sender (defaults to "Assistant")
     */
    fun showChatNotification(
        context: Context,
        sessionKey: String,
        sessionDisplayName: String,
        messageText: String,
        senderName: String = "Assistant",
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure per-session channel exists (creates on first notification, idempotent after)
        val channelId = NotificationChannelManager.ensurePerSessionChannel(
            context, sessionKey, sessionDisplayName
        )

        val notificationId = notificationIdForSession(sessionKey)
        val notificationTag = "$TAG_CHAT_MSG_PREFIX$sessionKey"

        // Build the "person" for MessagingStyle
        val sender = Person.Builder()
            .setName(senderName)
            .build()

        // Stack messages: check for existing notification and append new message.
        // Match by (tag, id) so we don't accidentally pick up the prompt notification
        // for the same session (same id, different tag).
        val existingNotif = notificationManager.activeNotifications
            .find { it.tag == notificationTag && it.id == notificationId }?.notification
        val existingStyle = existingNotif?.let {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)
        }

        // MessagingStyle for conversation-style notification
        val messagingStyle = (existingStyle ?: NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").build()
        ).setConversationTitle(sessionDisplayName))
            .addMessage(messageText, System.currentTimeMillis(), sender)

        // Tap action: open MainActivity and navigate to the session
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("navigate_to_session", sessionKey)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Reply action: RemoteInput for inline reply
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("Reply")
            .build()

        val replyIntent = Intent(context, ChatNotificationReceiver::class.java).apply {
            putExtra(EXTRA_SESSION_KEY, sessionKey)
            putExtra(EXTRA_SESSION_DISPLAY_NAME, sessionDisplayName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Build and show the notification using per-session channel
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(messagingStyle)
            .setContentIntent(tapPendingIntent)
            .addAction(replyAction)
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            notificationManager.notify(notificationTag, notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
            Log.w(TAG, "Cannot show notification: ${e.message}")
        }
    }

    /**
     * Shows a notification indicating that a background session is waiting for user input
     * (clarify / approval / secret / sudo prompt from the agent).
     *
     * Uses the prompt notification ID space (offset +2_000_000) so it is independent of
     * the chat-message notification for the same session and can be cancelled separately
     * when the user responds to the prompt.
     *
     * v1: no inline-reply RemoteInput — the user taps to open the session and responds
     * via the in-app prompt card.
     *
     * @param context Application context
     * @param sessionKey The gateway session key for this prompt
     * @param sessionDisplayName Human-readable session name for the notification
     * @param prompt The [PendingPrompt] carrying title and detail text
     */
    fun showPromptNotification(
        context: Context,
        sessionKey: String,
        sessionDisplayName: String,
        prompt: PendingPrompt,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = NotificationChannelManager.ensurePerSessionChannel(
            context, sessionKey, sessionDisplayName
        )

        val notificationId = promptNotificationIdForSession(sessionKey)
        val notificationTag = "$TAG_PROMPT_PREFIX$sessionKey"

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("navigate_to_session", sessionKey)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val bodyText = prompt.detail?.takeIf { it.isNotBlank() } ?: prompt.title

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$sessionDisplayName — input needed")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(tapPendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            notificationManager.notify(notificationTag, notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot show prompt notification: ${e.message}")
        }
    }

    /**
     * Cancels the prompt-request notification for a specific session.
     * Called when the user responds to any pending prompt in that session.
     */
    fun cancelPromptNotification(context: Context, sessionKey: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel("$TAG_PROMPT_PREFIX$sessionKey", promptNotificationIdForSession(sessionKey))
    }

    /**
     * Cancels the notification for a specific session.
     * Called when the user opens or resumes that session.
     */
    fun cancelNotification(context: Context, sessionKey: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel("$TAG_CHAT_MSG_PREFIX$sessionKey", notificationIdForSession(sessionKey))
    }

    /**
     * Deletes the notification channel for a session.
     * Called when a session is deleted to clean up Android system channels.
     */
    fun deleteSessionChannel(context: Context, sessionKey: String) {
        NotificationChannelManager.deleteSessionChannel(context, sessionKey)
    }

    /**
     * Generates a stable, consistent notification ID from a session key.
     * Uses the hashCode of the session key, offset to avoid collision with
     * the foreground service notification (ID=1).
     */
    internal fun notificationIdForSession(sessionKey: String): Int {
        // Use absolute value and add offset to avoid 0 and collision with foreground service ID=1
        return (sessionKey.hashCode() and 0x7FFFFFFF) + 1000
    }

    /**
     * Generates a stable notification ID for prompt-request notifications.
     * Uses a +2_000_000 offset to keep them in a separate space from chat-message
     * notifications (offset +1_000), so both can coexist for the same session.
     */
    internal fun promptNotificationIdForSession(sessionKey: String): Int {
        return (sessionKey.hashCode() and 0x7FFFFFFF) + 2_000_000
    }
}
