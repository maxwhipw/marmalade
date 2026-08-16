package app.marmalade.android.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for NotificationTriggerLogic pure functions.
 * Covers background detection, mute filtering, foreground same/different session,
 * and text-only response filtering.
 */
class NotificationTriggerTest {

    // --- shouldShowNotification ---

    @Test
    fun shouldShow_backgrounded_alwaysTrue() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "session-a",
            isMuted = false,
        )
        assertTrue("Background app should always show notification", result)
    }

    @Test
    fun shouldShow_backgrounded_differentSession_true() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = false,
            viewingSessionKey = "session-b",
            eventSessionKey = "session-a",
            isMuted = false,
        )
        assertTrue("Background with different session should show notification", result)
    }

    @Test
    fun shouldShow_foreground_viewingSameSession_false() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = true,
            viewingSessionKey = "session-a",
            eventSessionKey = "session-a",
            isMuted = false,
        )
        assertFalse("Foreground viewing same session should NOT show notification", result)
    }

    @Test
    fun shouldShow_foreground_viewingDifferentSession_true() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = true,
            viewingSessionKey = "session-a",
            eventSessionKey = "session-b",
            isMuted = false,
        )
        assertTrue("Foreground viewing different session should show notification", result)
    }

    @Test
    fun shouldShow_foreground_noViewingSession_true() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = true,
            viewingSessionKey = null,
            eventSessionKey = "session-b",
            isMuted = false,
        )
        assertTrue("Foreground with no active session should show notification", result)
    }

    @Test
    fun shouldShow_muted_backgrounded_false() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "session-a",
            isMuted = true,
        )
        assertFalse("Muted session should NEVER show notification (even when backgrounded)", result)
    }

    @Test
    fun shouldShow_muted_foreground_differentSession_false() {
        val result = NotificationTriggerLogic.shouldShowNotification(
            isForeground = true,
            viewingSessionKey = "session-a",
            eventSessionKey = "session-b",
            isMuted = true,
        )
        assertFalse("Muted session should NEVER show notification", result)
    }

    // --- isTextResponse ---

    @Test
    fun isTextResponse_final_withText_true() {
        val result = NotificationTriggerLogic.isTextResponse(
            state = "final",
            hasTextContent = true,
        )
        assertTrue("Final state with text should be a text response", result)
    }

    @Test
    fun isTextResponse_streaming_withText_false() {
        val result = NotificationTriggerLogic.isTextResponse(
            state = "streaming",
            hasTextContent = true,
        )
        assertFalse("Streaming state should NOT be treated as text response", result)
    }

    @Test
    fun isTextResponse_final_noText_false() {
        val result = NotificationTriggerLogic.isTextResponse(
            state = "final",
            hasTextContent = false,
        )
        assertFalse("Final state without text should NOT be treated as text response", result)
    }

    @Test
    fun isTextResponse_null_state_false() {
        val result = NotificationTriggerLogic.isTextResponse(
            state = null,
            hasTextContent = true,
        )
        assertFalse("Null state should NOT be treated as text response", result)
    }

    @Test
    fun isTextResponse_emptyState_false() {
        val result = NotificationTriggerLogic.isTextResponse(
            state = "",
            hasTextContent = true,
        )
        assertFalse("Empty state should NOT be treated as text response", result)
    }
}
