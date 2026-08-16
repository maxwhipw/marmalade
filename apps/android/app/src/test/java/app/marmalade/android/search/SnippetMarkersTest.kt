package app.marmalade.android.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon wraps matched spans in two private-use codepoints; the contract is
 * that the client strips them and styles the spans itself. Getting this wrong
 * paints tofu boxes around every match, so it is worth pinning.
 */
class SnippetMarkersTest {

    private val open = SnippetMarkers.OPEN
    private val close = SnippetMarkers.CLOSE

    @Test
    fun `markers are the wire's private-use codepoints`() {
        // Mirrors SNIPPET_OPEN / SNIPPET_CLOSE in
        // marmalade/packages/protocol/src/methods.ts. If the daemon ever moves
        // them this test is the thing that notices.
        assertEquals('\uE000', open)
        assertEquals('\uE001', close)
    }

    @Test
    fun `a snippet with no markers is one plain segment`() {
        assertEquals(
            listOf(SnippetMarkers.Segment("no matches here", match = false)),
            SnippetMarkers.parse("no matches here"),
        )
    }

    @Test
    fun `an empty snippet yields no segments`() {
        assertTrue(SnippetMarkers.parse("").isEmpty())
    }

    @Test
    fun `a single match splits into three segments`() {
        val segments = SnippetMarkers.parse("merge the ${open}seen_at${close} stamp")
        assertEquals(
            listOf(
                SnippetMarkers.Segment("merge the ", match = false),
                SnippetMarkers.Segment("seen_at", match = true),
                SnippetMarkers.Segment(" stamp", match = false),
            ),
            segments,
        )
    }

    @Test
    fun `two matches in one snippet both highlight`() {
        val segments = SnippetMarkers.parse("${open}seen_at${close} and ${open}monotonic${close}")
        assertEquals(listOf(true, false, true), segments.map { it.match })
        assertEquals("seen_at and monotonic", segments.joinToString("") { it.text })
    }

    @Test
    fun `a match at each end emits no empty segments`() {
        val segments = SnippetMarkers.parse("${open}alpha${close}${open}beta${close}")
        assertEquals(
            listOf(
                SnippetMarkers.Segment("alpha", match = true),
                SnippetMarkers.Segment("beta", match = true),
            ),
            segments,
        )
    }

    @Test
    fun `an unclosed open highlights to the end of the snippet`() {
        // A snippet is a WINDOW onto a message, so the daemon can legitimately
        // cut a span in half. Highlighting the tail beats dropping it.
        val segments = SnippetMarkers.parse("truncated ${open}mid-sp")
        assertEquals(
            listOf(
                SnippetMarkers.Segment("truncated ", match = false),
                SnippetMarkers.Segment("mid-sp", match = true),
            ),
            segments,
        )
    }

    @Test
    fun `a stray close is dropped and its text kept`() {
        val segments = SnippetMarkers.parse("no open${close} here")
        assertEquals(
            listOf(SnippetMarkers.Segment("no open here", match = false)),
            segments,
        )
    }

    @Test
    fun `a repeated open does not nest or lose text`() {
        val segments = SnippetMarkers.parse("${open}a${open}b${close}c")
        assertEquals(
            listOf(
                SnippetMarkers.Segment("ab", match = true),
                SnippetMarkers.Segment("c", match = false),
            ),
            segments,
        )
    }

    @Test
    fun `parse never leaks a marker into segment text`() {
        val nasty = "$open$close${open}x$close$close${open}y"
        val text = SnippetMarkers.parse(nasty).joinToString("") { it.text }
        assertTrue(text.none { it == open || it == close })
        assertEquals("xy", text)
    }

    @Test
    fun `strip removes every marker and nothing else`() {
        assertEquals(
            "merge the seen_at stamp monotonically",
            SnippetMarkers.strip(
                "merge the ${open}seen_at${close} stamp ${open}monotonic${close}ally",
            ),
        )
    }

    @Test
    fun `strip leaves a marker-free snippet identical`() {
        val plain = "nothing to strip"
        assertEquals(plain, SnippetMarkers.strip(plain))
    }

    @Test
    fun `strip and parse agree on the visible text`() {
        val snippet = "…the ${open}unread${close} ${open}badge${close} never appears…"
        assertEquals(
            SnippetMarkers.strip(snippet),
            SnippetMarkers.parse(snippet).joinToString("") { it.text },
        )
    }
}
