package app.marmalade.android.service

import app.marmalade.android.chat.MAIN_SESSION_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voice ALWAYS routes into THE daemon-managed main session (session.main) —
 * the same session Home binds to. There is no user-selectable assistant
 * session any more (assistant plan 2026-07-19: main is daemon-owned), so the
 * resolver just surfaces the runtime's resolved main id (or null before it
 * resolves).
 */
class VoiceSessionResolverTest {

    @Test
    fun routesToTheResolvedMainSession() {
        assertEquals(
            "20260630_090000_abc123",
            resolveVoiceSessionKey("20260630_090000_abc123"),
        )
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("main-session", resolveVoiceSessionKey("  main-session  "))
    }

    @Test
    fun bootPlaceholderIsNotAMainSession() {
        // The "main" placeholder names no real conversation — the caller opens
        // the popup degraded and surfaces the standard "no session" error.
        assertNull(resolveVoiceSessionKey(MAIN_SESSION_PLACEHOLDER))
    }

    @Test
    fun blankReturnsNull() {
        assertNull(resolveVoiceSessionKey("   "))
        assertNull(resolveVoiceSessionKey(""))
    }
}
