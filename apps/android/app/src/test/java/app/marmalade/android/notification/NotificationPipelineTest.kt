package app.marmalade.android.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for NotificationPipelineLogic -- the full notification decision chain.
 *
 * Validates the composite logic that combines:
 * 1. Text content check (empty/null -> no notification)
 * 2. State check (only "final" triggers notification)
 * 3. NotificationTriggerLogic (mute, foreground, session matching)
 *
 * Pure function tests -- no Android context needed.
 */
class NotificationPipelineTest {

    @Test
    fun `final state with text, backgrounded, not muted - should fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "Hello",
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertTrue("Backgrounded final text message should fire notification", result)
    }

    @Test
    fun `final state with empty text - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "",
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertFalse("Empty text should not fire notification", result)
    }

    @Test
    fun `final state with null text - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = null,
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertFalse("Null text should not fire notification", result)
    }

    @Test
    fun `streaming state with text - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "streaming",
            text = "Hello",
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertFalse("Streaming state should not fire notification", result)
    }

    @Test
    fun `final state, foreground, viewing same session - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "Hello",
            isForeground = true,
            viewingSessionKey = "sess1",
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertFalse("Viewing same session should not fire notification", result)
    }

    @Test
    fun `final state, foreground, viewing different session - should fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "Hello",
            isForeground = true,
            viewingSessionKey = "sess1",
            eventSessionKey = "sess2",
            isMuted = false,
        )
        assertTrue("Viewing different session should fire notification", result)
    }

    @Test
    fun `final state, backgrounded, muted session - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "Hello",
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = true,
        )
        assertFalse("Muted session should never fire notification", result)
    }

    @Test
    fun `final state with whitespace-only text - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "   \n  ",
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertFalse("Whitespace-only text should not fire notification", result)
    }

    @Test
    fun `error state with text - should not fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "error",
            text = "Something went wrong",
            isForeground = false,
            viewingSessionKey = null,
            eventSessionKey = "sess1",
            isMuted = false,
        )
        assertFalse("Error state should not fire notification", result)
    }

    @Test
    fun `final state, foreground, no viewing session - should fire`() {
        val result = NotificationPipelineLogic.shouldFireNotification(
            state = "final",
            text = "Task completed successfully",
            isForeground = true,
            viewingSessionKey = null,
            eventSessionKey = "agent:cron:daily-report",
            isMuted = false,
        )
        assertTrue("Foreground with no active session should fire notification", result)
    }
}
