package app.marmalade.desktop.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Notification body shaping — the part of the notify-send path that can be
 * wrong in a way a user sees. The process launch itself is not tested: it
 * proves nothing offline that the fail-soft branch doesn't already state.
 */
class NotificationTextTest {

    @Test
    fun `flattens markdown whitespace to a single line`() {
        val raw = "Done.\n\n```\ncode block\n```\n\n  trailing  "
        assertEquals("Done. ``` code block ``` trailing", NotificationText.snippet(raw, 200))
    }

    @Test
    fun `short text passes through untouched`() {
        assertEquals("all good", NotificationText.snippet("all good", 80))
    }

    @Test
    fun `truncation cuts at a word boundary and marks the elision`() {
        val raw = "the quick brown fox jumps over the lazy dog"
        val cut = NotificationText.snippet(raw, 20)

        assertEquals("the quick brown…", cut)
        assertTrue(raw.startsWith(cut.dropLast(1)), "'$cut' is not a prefix of the source")
    }

    @Test
    fun `a single long word is cut mid-word rather than dropped`() {
        // No space to fall back to — the budget still has to hold.
        val cut = NotificationText.snippet("x".repeat(50), 10)
        assertEquals("x".repeat(10) + "…", cut)
    }

    @Test
    fun `libnotify markup characters are escaped`() {
        assertEquals(
            "a &amp; b &lt;tag&gt;",
            NotificationText.escape("a & b <tag>"),
        )
    }

    @Test
    fun `escaping happens after truncation so the budget is about visible text`() {
        val out = NotificationText.forNotifySend("&&&&&&&&&&&&", max = 4)
        // Four visible ampersands plus the ellipsis, entity-encoded.
        assertEquals("&amp;&amp;&amp;&amp;…", out)
    }
}
