package app.marmalade.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeyUtilsTest {

    // ── classifySessionKey ──────────────────────────────────────────────────

    @Test
    fun `mattermost channel via agent is classified as Mattermost`() {
        assertEquals(
            SessionKeyUtils.GROUP_MATTERMOST,
            SessionKeyUtils.classifySessionKey("agent:assistant:mattermost:channel:abc123def456")
        )
    }

    @Test
    fun `mattermost channel via different agent is classified as Mattermost`() {
        assertEquals(
            SessionKeyUtils.GROUP_MATTERMOST,
            SessionKeyUtils.classifySessionKey("agent:helper:mattermost:channel:xyz789")
        )
    }

    @Test
    fun `discord channel via agent is classified as Discord`() {
        assertEquals(
            SessionKeyUtils.GROUP_DISCORD,
            SessionKeyUtils.classifySessionKey("agent:assistant:discord:channel:server123")
        )
    }

    @Test
    fun `slack channel via agent is classified as Slack`() {
        assertEquals(
            SessionKeyUtils.GROUP_SLACK,
            SessionKeyUtils.classifySessionKey("agent:bot:slack:channel:workspace456")
        )
    }

    @Test
    fun `telegram channel via agent is classified as Telegram`() {
        assertEquals(
            SessionKeyUtils.GROUP_TELEGRAM,
            SessionKeyUtils.classifySessionKey("agent:relay:telegram:channel:chat789")
        )
    }

    @Test
    fun `cron job via agent is classified as Scheduled Tasks`() {
        assertEquals(
            SessionKeyUtils.GROUP_CRON,
            SessionKeyUtils.classifySessionKey("agent:assistant:cron:a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        )
    }

    @Test
    fun `cron job via different agent is classified as Scheduled Tasks`() {
        assertEquals(
            SessionKeyUtils.GROUP_CRON,
            SessionKeyUtils.classifySessionKey("agent:scheduler:cron:daily-cleanup-task")
        )
    }

    @Test
    fun `regular agent chat session is classified as Gateway`() {
        assertEquals(
            SessionKeyUtils.GROUP_GATEWAY,
            SessionKeyUtils.classifySessionKey("agent:claude:chat-20260315-143022")
        )
    }

    @Test
    fun `agent session without chat prefix is classified as Gateway`() {
        assertEquals(
            SessionKeyUtils.GROUP_GATEWAY,
            SessionKeyUtils.classifySessionKey("agent:assistant:my-project-session")
        )
    }

    @Test
    fun `direct telegram prefix is classified as Telegram`() {
        assertEquals(
            SessionKeyUtils.GROUP_TELEGRAM,
            SessionKeyUtils.classifySessionKey("telegram:user-12345")
        )
    }

    @Test
    fun `direct discord prefix is classified as Discord`() {
        assertEquals(
            SessionKeyUtils.GROUP_DISCORD,
            SessionKeyUtils.classifySessionKey("discord:guild-67890")
        )
    }

    @Test
    fun `unknown key format is classified as Other`() {
        assertEquals(
            SessionKeyUtils.GROUP_OTHER,
            SessionKeyUtils.classifySessionKey("custom-session-12345")
        )
    }

    @Test
    fun `mattermost direct message via agent is classified as Mattermost`() {
        assertEquals(
            SessionKeyUtils.GROUP_MATTERMOST,
            SessionKeyUtils.classifySessionKey("agent:assistant:mattermost:direct:user789")
        )
    }

    @Test
    fun `discord without channel segment is classified as Discord`() {
        assertEquals(
            SessionKeyUtils.GROUP_DISCORD,
            SessionKeyUtils.classifySessionKey("agent:bot:discord:guild:server123")
        )
    }

    @Test
    fun `unknown platform with channel is classified as Other`() {
        assertEquals(
            SessionKeyUtils.GROUP_OTHER,
            SessionKeyUtils.classifySessionKey("agent:bot:matrix:channel:room123")
        )
    }

    @Test
    fun `mattermost channel never classified as Gateway`() {
        val key = "agent:assistant:mattermost:channel:abc123def456"
        assertNotEquals(SessionKeyUtils.GROUP_GATEWAY, SessionKeyUtils.classifySessionKey(key))
    }

    @Test
    fun `unknown non-agent key is classified as Other`() {
        assertEquals(
            SessionKeyUtils.GROUP_OTHER,
            SessionKeyUtils.classifySessionKey("matrix:room:room123")
        )
    }

    // ── extractAgentId ──────────────────────────────────────────────────────

    @Test
    fun `extractAgentId returns agent name from chat session`() {
        assertEquals("claude", SessionKeyUtils.extractAgentId("agent:claude:chat-20260315"))
    }

    @Test
    fun `extractAgentId returns agent name from mattermost channel`() {
        assertEquals("assistant", SessionKeyUtils.extractAgentId("agent:assistant:mattermost:channel:abc"))
    }

    @Test
    fun `extractAgentId returns null for non-agent key`() {
        assertNull(SessionKeyUtils.extractAgentId("telegram:user-123"))
    }

    @Test
    fun `extractAgentId returns null for empty agent prefix`() {
        assertNull(SessionKeyUtils.extractAgentId("agent::something"))
    }

    // ── isDeletable ─────────────────────────────────────────────────────────

    @Test
    fun `user-created gateway session is deletable`() {
        assertTrue(SessionKeyUtils.isDeletable("agent:claude:chat-20260315"))
    }

    @Test
    fun `post-K1 stored_session_id key is deletable`() {
        // Post-K1 the gateway keys user sessions by a bare stored_session_id
        // with no agent: prefix — these classify as OTHER but are still the
        // user's own sessions and must be deletable.
        assertTrue(SessionKeyUtils.isDeletable("20260629_180856_779e02"))
    }

    @Test
    fun `most-recent session is still deletable`() {
        // The gateway's most-recent session id must NOT be treated as
        // undeletable — upstream lets you delete the active/newest session,
        // and it sorts to the top of the list (first row long-pressed).
        assertTrue(SessionKeyUtils.isDeletable("20260630_071413_975629"))
    }

    @Test
    fun `literal main and global sentinels are not deletable`() {
        assertFalse(SessionKeyUtils.isDeletable("main"))
        assertFalse(SessionKeyUtils.isDeletable("global"))
    }

    @Test
    fun `mattermost channel is not deletable`() {
        assertFalse(SessionKeyUtils.isDeletable("agent:assistant:mattermost:channel:abc"))
    }

    @Test
    fun `cron session is not deletable`() {
        assertFalse(SessionKeyUtils.isDeletable("agent:assistant:cron:daily-task"))
    }

    // ── matchesSubscription ─────────────────────────────────────────────────

    @Test
    fun `matchesSubscription accepts exact match`() {
        assertTrue(SessionKeyUtils.matchesSubscription("main", "main"))
        assertTrue(SessionKeyUtils.matchesSubscription("agent:x:chat", "agent:x:chat"))
    }

    @Test
    fun `matchesSubscription accepts canonical upgrade of bare key`() {
        // gateway returns agent:<id>:<name> for bare subscription <name>
        assertTrue(SessionKeyUtils.matchesSubscription("agent:main:chat-ts", "chat-ts"))
        assertTrue(SessionKeyUtils.matchesSubscription("agent:main:main", "main"))
    }

    @Test
    fun `matchesSubscription rejects substring coincidences`() {
        // "mainframe" ends with "main" characters but not as a segment
        assertFalse(SessionKeyUtils.matchesSubscription("agent:x:mainframe", "main"))
        assertFalse(SessionKeyUtils.matchesSubscription("mainland", "main"))
        assertFalse(SessionKeyUtils.matchesSubscription("chat-ts-old", "chat-ts"))
    }

    @Test
    fun `matchesSubscription rejects empty keys`() {
        assertFalse(SessionKeyUtils.matchesSubscription("", "main"))
        assertFalse(SessionKeyUtils.matchesSubscription("main", ""))
    }

    @Test
    fun `matchesSubscription accepts mid-key segment match`() {
        // segment match catches nested/platform forms too — user subscribed
        // to "main" and event arrives as agent:main:telegram:channel:x
        assertTrue(SessionKeyUtils.matchesSubscription("agent:main:telegram:channel:x", "main"))
    }
}
