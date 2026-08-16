package app.marmalade.android.notification

/**
 * Pure-function logic for deciding whether to show a chat notification.
 * Separated from Android APIs for easy unit testing.
 *
 * Decision matrix:
 * - Muted session: NEVER show (highest priority)
 * - App backgrounded: ALWAYS show
 * - App foregrounded, viewing same session: DO NOT show
 * - App foregrounded, viewing different session (or no session): SHOW
 */
object NotificationTriggerLogic {

    /**
     * Determines whether a notification should be shown for an incoming chat event.
     *
     * @param isForeground Whether the app is currently in the foreground
     * @param viewingSessionKey The session key the user is currently viewing (null if not viewing any)
     * @param eventSessionKey The session key that received the new message
     * @param isMuted Whether the target session is muted by the user
     * @return true if a notification should be shown
     */
    fun shouldShowNotification(
        isForeground: Boolean,
        viewingSessionKey: String?,
        eventSessionKey: String,
        isMuted: Boolean,
    ): Boolean {
        // Muted sessions never produce notifications
        if (isMuted) return false

        // App is backgrounded -- always notify
        if (!isForeground) return true

        // App is foregrounded -- only notify if user is viewing a different session
        return viewingSessionKey != eventSessionKey
    }

    /**
     * Determines whether a chat event represents a completed text response
     * that should trigger a notification. Only "final" state messages with
     * actual text content qualify.
     *
     * @param state The event state (e.g., "streaming", "final")
     * @param hasTextContent Whether the message contains text content
     * @return true if this is a final text response worth notifying about
     */
    fun isTextResponse(state: String?, hasTextContent: Boolean): Boolean {
        return state == "final" && hasTextContent
    }
}
