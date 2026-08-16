package app.marmalade.android.search

/**
 * Parser for the match markers the daemon wraps around matched spans in
 * `SearchHit.snippet`.
 *
 * The daemon uses two Unicode private-use characters (U+E000 / U+E001) rather
 * than `<mark>` tags precisely because they can never collide with message
 * text — a chat about HTML would break a tag-based scheme. The contract is that
 * the client STRIPS them and applies its own styling; rendering a snippet raw
 * paints two tofu boxes around every match.
 *
 * Wire truth: marmalade/packages/protocol/src/methods.ts `SNIPPET_OPEN` /
 * `SNIPPET_CLOSE`.
 *
 * Robustness: a snippet is a *window* onto a message, so an unbalanced marker
 * is a normal outcome, not corruption. An unclosed OPEN highlights to the end
 * of the snippet; a CLOSE with no open marker is dropped. Either way no marker
 * character survives into the returned text.
 */
object SnippetMarkers {

    // Escapes, not literals: a private-use codepoint pasted into source is
    // invisible in every editor and diff tool.
    const val OPEN = '\uE000'
    const val CLOSE = '\uE001'

    /** One run of snippet text, either a matched span or the prose around it. */
    data class Segment(val text: String, val match: Boolean)

    /**
     * Split [snippet] into alternating plain / matched segments.
     *
     * Empty segments are never emitted, so adjacent markers collapse and the
     * result concatenates back to [strip]`(snippet)`.
     */
    fun parse(snippet: String): List<Segment> {
        if (snippet.isEmpty()) return emptyList()
        // Fast path: the common case for a hit whose match fell outside the
        // window (or a `sort=recent` page) is a snippet with no markers at all.
        if (snippet.indexOf(OPEN) < 0 && snippet.indexOf(CLOSE) < 0) {
            return listOf(Segment(snippet, match = false))
        }

        val segments = mutableListOf<Segment>()
        val buffer = StringBuilder()
        var inMatch = false

        fun flush() {
            if (buffer.isNotEmpty()) {
                segments += Segment(buffer.toString(), match = inMatch)
                buffer.clear()
            }
        }

        for (ch in snippet) {
            when (ch) {
                OPEN -> {
                    // A second OPEN inside a span is a daemon-side oddity; treat
                    // it as a no-op rather than nesting (there is no nesting in
                    // the contract, and dropping the span would lose text).
                    if (!inMatch) {
                        flush()
                        inMatch = true
                    }
                }
                CLOSE -> {
                    if (inMatch) {
                        flush()
                        inMatch = false
                    }
                    // else: stray close — drop the marker, keep the text.
                }
                else -> buffer.append(ch)
            }
        }
        flush()
        return segments
    }

    /** [snippet] with every marker removed — for copy, TTS, and accessibility. */
    fun strip(snippet: String): String =
        if (snippet.indexOf(OPEN) < 0 && snippet.indexOf(CLOSE) < 0) snippet
        else snippet.filterNot { it == OPEN || it == CLOSE }
}
