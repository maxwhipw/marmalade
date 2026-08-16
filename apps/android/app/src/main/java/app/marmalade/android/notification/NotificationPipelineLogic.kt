package app.marmalade.android.notification

/**
 * Pure-function orchestration of the notification decision chain.
 * Combines text-content check, state check, and trigger logic into a single call.
 *
 * This is the testable core of the notification pipeline:
 * 1. Empty/null text -> no notification (nothing to show)
 * 2. Non-final state -> no notification (only completed responses)
 * 3. Muted session -> no notification (user preference)
 * 4. Foreground + viewing same session -> no notification (user already sees it)
 * 5. Otherwise -> show notification
 */
object NotificationPipelineLogic {

    /**
     * Determines whether a notification should be fired for an incoming chat event.
     *
     * @param state The event state (e.g., "streaming", "final")
     * @param text The extracted text content from the message (null/blank = no text)
     * @param isForeground Whether the app is currently in the foreground
     * @param viewingSessionKey The session key the user is currently viewing (null if not viewing any)
     * @param eventSessionKey The session key that received the new message
     * @param isMuted Whether the target session is muted by the user
     * @return true if a notification should be shown
     */
    fun shouldFireNotification(
        state: String,
        text: String?,
        isForeground: Boolean,
        viewingSessionKey: String?,
        eventSessionKey: String,
        isMuted: Boolean,
    ): Boolean {
        if (text.isNullOrBlank()) return false
        if (!NotificationTriggerLogic.isTextResponse(state, true)) return false
        return NotificationTriggerLogic.shouldShowNotification(
            isForeground, viewingSessionKey, eventSessionKey, isMuted
        )
    }
}
