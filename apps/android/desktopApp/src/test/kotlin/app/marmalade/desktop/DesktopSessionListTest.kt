package app.marmalade.desktop

import app.marmalade.android.SessionUiModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two pure derivations behind the desktop session pane and chat header.
 *
 * Worth testing because both encode a contract that is easy to break silently:
 * the pin must not RE-SORT the recency order Room already produced, and the
 * header must never render a raw session key when the list hasn't caught up.
 * The composables around them are wiring and are left to the eye.
 */
class DesktopSessionListTest {

    private fun session(id: String, isMain: Boolean = false, title: String = id) = SessionUiModel(
        id = id,
        title = title,
        createdAt = 0L,
        isGateway = true,
        isMain = isMain,
    )

    @Test
    fun `pinMainFirst hoists the main session and preserves the rest of the order`() {
        val ordered = listOf(
            session("newest"),
            session("main-session", isMain = true),
            session("older"),
            session("oldest"),
        )

        val pinned = pinMainFirst(ordered)

        assertEquals(
            listOf("main-session", "newest", "older", "oldest"),
            pinned.map { it.id },
        )
    }

    @Test
    fun `pinMainFirst is a no-op when no session is main`() {
        val ordered = listOf(session("a"), session("b"), session("c"))
        assertEquals(ordered, pinMainFirst(ordered))
    }

    @Test
    fun `boundSessionTitle prefers the list row title`() {
        val bound = session("sess-123", title = "Marmalade App")
        assertEquals("Marmalade App", boundSessionTitle(bound, "sess-123"))
    }

    @Test
    fun `boundSessionTitle falls back to a friendly key when the row is missing`() {
        assertEquals("Agent Main", boundSessionTitle(null, "telegram:g-agent-main"))
    }

    @Test
    fun `boundSessionTitle falls back when the row carries a blank title`() {
        val bound = session("my-custom-session", title = "   ")
        assertEquals("My Custom Session", boundSessionTitle(bound, "my-custom-session"))
    }
}
