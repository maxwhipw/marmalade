package app.marmalade.android.ui.home

import app.marmalade.android.chat.MAIN_SESSION_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Home is ALWAYS THE daemon-managed main session (session.main) — resolved on
 * connect, seeded from the persisted id on an offline cold start (assistant
 * plan 2026-07-19: main is daemon-owned, no user pick). The resolver just
 * surfaces the runtime's resolved main id, treating the boot placeholder /
 * empty as "not resolved yet" (the brief connecting state).
 */
class ResolveAssistantSessionKeyTest {

    @Test
    fun `a resolved main id is returned as-is`() {
        assertEquals(
            "20260629_180856_779e02",
            resolveAssistantSessionKey("20260629_180856_779e02"),
        )
    }

    @Test
    fun `the boot placeholder returns null (connecting state)`() {
        // The "main" placeholder names no real conversation — binding it would
        // render an empty phantom chat, so Home shows the connecting state and
        // waits for the runtime to install the real session.main id.
        assertNull(resolveAssistantSessionKey(MAIN_SESSION_PLACEHOLDER))
    }

    @Test
    fun `empty main returns null (never connected)`() {
        assertNull(resolveAssistantSessionKey(""))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("main-session-id", resolveAssistantSessionKey("  main-session-id  "))
    }

    @Test
    fun `whitespace-only returns null`() {
        assertNull(resolveAssistantSessionKey("   "))
    }
}
