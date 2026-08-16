package app.marmalade.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for NotificationChannelManager pure-function channel ID logic.
 * No Android context needed -- tests sessionChannelId determinism, format, and uniqueness.
 */
class NotificationChannelManagerTest {

    @Test
    fun sessionChannelId_startsWithPrefix() {
        val channelId = NotificationChannelManager.sessionChannelId("main")
        assertTrue(
            "Channel ID should start with 'chat_session_', got: $channelId",
            channelId.startsWith("chat_session_")
        )
    }

    @Test
    fun sessionChannelId_isDeterministic() {
        val first = NotificationChannelManager.sessionChannelId("main")
        val second = NotificationChannelManager.sessionChannelId("main")
        assertEquals("Same key should produce same channel ID", first, second)
    }

    @Test
    fun sessionChannelId_differentKeysProduceDifferentIds() {
        val idA = NotificationChannelManager.sessionChannelId("session-a")
        val idB = NotificationChannelManager.sessionChannelId("session-b")
        assertNotEquals("Different keys should produce different channel IDs", idA, idB)
    }

    @Test
    fun sessionChannelId_suffixIsPositiveInt() {
        val channelId = NotificationChannelManager.sessionChannelId("agent:claude:chat-20260315")
        val suffix = channelId.removePrefix("chat_session_")
        val parsed = suffix.toIntOrNull()
        assertTrue("Suffix should be a valid integer, got: $suffix", parsed != null)
        assertTrue("Suffix should be positive, got: $parsed", parsed!! >= 0)
    }

    @Test
    fun persistentChannelId_hasExpectedValue() {
        assertEquals("marmalade_status", NotificationChannelManager.PERSISTENT_CHANNEL_ID)
    }

    @Test
    fun persistentChannelName_hasExpectedValue() {
        assertEquals("Connection Status", NotificationChannelManager.PERSISTENT_CHANNEL_NAME)
    }
}
