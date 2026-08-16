package app.marmalade.android.notification

/**
 * Data Flow: Notification Inline Reply
 *
 * User types reply in notification shade
 *   -> ChatNotificationReceiver.onReceive()
 *     -> Extract RemoteInput text + session key
 *     -> Send via MarmaladeRuntime.sendChatToSession(sessionKey, text)
 *     -> Update notification to show "Sending..." then cancel
 */

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.marmalade.android.MarmaladeApplication
import app.marmalade.android.R

class ChatNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChatNotifyReply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionKey = intent.getStringExtra(ChatNotificationHelper.EXTRA_SESSION_KEY)
        if (sessionKey.isNullOrBlank()) {
            Log.w(TAG, "Received reply intent with no session key")
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput == null) {
            Log.w(TAG, "No RemoteInput results in reply intent")
            return
        }

        val replyText = remoteInput.getCharSequence(ChatNotificationHelper.REMOTE_INPUT_KEY)?.toString()
        if (replyText.isNullOrBlank()) {
            Log.w(TAG, "Empty reply text")
            return
        }

        val sessionDisplayName = intent.getStringExtra(ChatNotificationHelper.EXTRA_SESSION_DISPLAY_NAME) ?: sessionKey

        Log.d(TAG, "Received reply for session=$sessionKey: ${replyText.take(50)}")

        // Get MarmaladeRuntime from Application
        val app = context.applicationContext as? MarmaladeApplication
        if (app == null) {
            Log.e(TAG, "Application is not MarmaladeApplication")
            return
        }
        val marmaladeRuntime = app.marmaladeRuntime

        // Send the reply message to the specific session via MarmaladeRuntime.
        // sendChatToSession switches to the target session, sends the message,
        // and does not affect the user's currently viewed session in the UI.
        marmaladeRuntime.sendChatToSession(sessionKey, replyText)

        // Update the notification to show the sent reply, then cancel.
        val notificationId = ChatNotificationHelper.notificationIdForSession(sessionKey)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val you = Person.Builder().setName("You").build()

        val updatedStyle = NotificationCompat.MessagingStyle(you)
            .setConversationTitle(sessionDisplayName)
            .addMessage(replyText, System.currentTimeMillis(), you)

        val channelId = NotificationChannelManager.sessionChannelId(sessionKey)
        val updatedNotification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(updatedStyle)
            .setAutoCancel(true)
            .build()

        val notificationTag = "${ChatNotificationHelper.TAG_CHAT_MSG_PREFIX}$sessionKey"
        try {
            notificationManager.notify(notificationTag, notificationId, updatedNotification)
            // Auto-dismiss after a short delay to clear the "Sending..." state
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(notificationTag, notificationId)
            }, 2000L)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update notification: ${e.message}")
        }
    }
}
