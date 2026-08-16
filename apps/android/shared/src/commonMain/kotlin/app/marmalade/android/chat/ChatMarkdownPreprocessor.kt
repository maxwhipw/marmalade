package app.marmalade.android.chat

/**
 * Strips inbound metadata injected by the OpenClaw gateway before displaying messages.
 * Keeps in sync with iOS ChatMarkdownPreprocessor.swift and
 * `src/auto-reply/reply/strip-inbound-meta.ts`.
 */
object ChatMarkdownPreprocessor {

    private val inboundContextHeaders = listOf(
        "Conversation info (untrusted metadata):",
        "Sender (untrusted metadata):",
        "Thread starter (untrusted, for context):",
        "Replied message (untrusted, for context):",
        "Forwarded message context (untrusted metadata):",
        "Chat history since last reply (untrusted, for context):",
    )

    /** Pattern matching `[Mon 2026-02-23 21:54 GMT+9]` at start of a line */
    private val timestampPrefixRegex = Regex(
        """(?m)^\[[A-Za-z]{3}\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?\s+(?:GMT|UTC)[+-]?\d{0,2}\]\s*"""
    )

    /** A `> ` quoted line whose content starts an ordered or unordered list item. */
    private val quotedListItemRegex = Regex("""^>\s+(?:\d+\.\s|[-*+]\s)""")

    /** A `> ` quoted line of any kind (including a blank `>` continuation). */
    private val quotedLineRegex = Regex("""^>(\s|$)""")

    /**
     * GFM task list item: list marker + `[ ]` / `[x]` / `[X]` + space.
     * Anchored at line start (allowing leading indent for nested lists).
     * The marker stays in the output; the bracketed state becomes a
     * Unicode glyph (see [renderTaskListMarkers]).
     */
    private val taskListItemRegex = Regex(
        """^(\s*(?:[-*+]|\d+\.))\s+\[([ xX])\]\s+"""
    )

    /** A line that opens or closes a fenced code block. */
    private val codeFenceLineRegex = Regex("""^\s*(```|~~~)""")

    fun preprocess(raw: String): String {
        val withoutContextBlocks = stripInboundContextBlocks(raw)
        val withoutTimestamps = stripPrefixedTimestamps(withoutContextBlocks)
        val withSeparatedQuoteLists = normalizeBlockquoteLists(withoutTimestamps)
        val withTaskCheckboxes = renderTaskListMarkers(withSeparatedQuoteLists)
        val withTables = renderPipeTables(withTaskCheckboxes)
        return normalize(withTables)
    }

    /** A line that looks like a GFM table separator: `| --- | --- |` */
    private val tableSeparatorRegex = Regex("""^\s*\|?(\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?\s*$""")
    /** A line that looks like a GFM table row: starts/ends with `|` or
     *  contains at least one `|` outside an obvious context. */
    private val tableRowRegex = Regex("""^\s*\|.*\|\s*$""")

    /**
     * Convert GFM-style pipe tables into fixed-width fenced code blocks.
     * compose-richtext's alpha03 commonmark parser doesn't include the
     * tables extension; rather than render `| Header |` as broken
     * paragraph text, pre-format the table with column padding inside
     * a ```` ``` ```` fence so it shows in the existing code-block
     * surface — readable, copy-pasteable, alignment preserved. Lines
     * inside an existing fenced block are passed through untouched.
     */
    // Public (not internal): verified directly by ChatMarkdownPreprocessorTest,
    // which stays in :app while this preprocessor moved to :shared (increment 3d)
    // — `internal` doesn't cross the Gradle module boundary.
    fun renderPipeTables(raw: String): String {
        if (raw.isEmpty() || !raw.contains('|')) return raw
        val lines = raw.split("\n")
        val out = ArrayList<String>(lines.size)
        var i = 0
        var inFence = false
        while (i < lines.size) {
            val line = lines[i]
            if (codeFenceLineRegex.containsMatchIn(line)) {
                inFence = !inFence
                out.add(line)
                i++
                continue
            }
            if (inFence) {
                out.add(line)
                i++
                continue
            }
            // A table needs at least header + separator + one row, three
            // consecutive lines that all look like pipe rows.
            if (
                i + 1 < lines.size &&
                tableRowRegex.matches(line) &&
                tableSeparatorRegex.matches(lines[i + 1])
            ) {
                val header = splitTableRow(line)
                // Collect data rows until we hit a non-row line.
                var j = i + 2
                val dataRows = mutableListOf<List<String>>()
                while (j < lines.size && tableRowRegex.matches(lines[j])) {
                    dataRows.add(splitTableRow(lines[j]))
                    j++
                }
                out.add("```")
                out.addAll(formatTable(header, dataRows))
                out.add("```")
                i = j
                continue
            }
            out.add(line)
            i++
        }
        return out.joinToString("\n")
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split("|").map { it.trim() }
    }

    private fun formatTable(
        header: List<String>,
        rows: List<List<String>>,
    ): List<String> {
        val columnCount = (listOf(header) + rows).maxOf { it.size }
        val widths = IntArray(columnCount)
        fun normaliseRow(r: List<String>): List<String> {
            if (r.size == columnCount) return r
            return r + List(columnCount - r.size) { "" }
        }
        val normalisedHeader = normaliseRow(header)
        val normalisedRows = rows.map(::normaliseRow)
        for (row in listOf(normalisedHeader) + normalisedRows) {
            row.forEachIndexed { idx, cell ->
                if (cell.length > widths[idx]) widths[idx] = cell.length
            }
        }
        fun render(row: List<String>): String =
            row.mapIndexed { idx, cell -> cell.padEnd(widths[idx]) }.joinToString(" | ")
        val separator = widths.joinToString("-+-") { "-".repeat(it) }
        return buildList {
            add(render(normalisedHeader))
            add(separator)
            normalisedRows.forEach { add(render(it)) }
        }
    }

    /**
     * Render GFM task-list checkbox markers as Unicode glyphs.
     *
     * commonmark-java (compose-richtext's parser) does not include the
     * task-list extension by default, and alpha03 doesn't expose a
     * Parser-builder hook to add it. A read-only chat display has no
     * use for the interactive semantics of a "real" task-list AST
     * node — the entire UX is the visual checkbox — so we substitute
     * at the text layer where the rule sits next to the existing
     * blockquote-list normaliser.
     *
     * Substitutions:
     *   - `- [ ]` / `* [ ]` / `+ [ ]` / `1. [ ]` → marker + `☐`
     *   - `- [x]` / `- [X]` / etc.              → marker + `☑`
     *
     * Skipped inside fenced code blocks (` ``` ` or `~~~`) so a
     * markdown sample showing literal task syntax still renders the
     * brackets verbatim. Indent is preserved so nested task lists
     * render at the right depth.
     */
    // Public (not internal): see the note on renderPipeTables — cross-module
    // test visibility after the :shared move.
    fun renderTaskListMarkers(raw: String): String {
        if (raw.isEmpty()) return raw
        val lines = raw.split("\n")
        if (lines.isEmpty()) return raw
        var inFence = false
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (codeFenceLineRegex.containsMatchIn(line)) {
                inFence = !inFence
                out.add(line)
                continue
            }
            if (inFence) {
                out.add(line)
                continue
            }
            val match = taskListItemRegex.find(line)
            if (match == null) {
                out.add(line)
                continue
            }
            val marker = match.groupValues[1]
            val state = match.groupValues[2]
            val glyph = if (state == " ") "☐" else "☑"
            val tail = line.substring(match.range.last + 1)
            out.add("$marker $glyph $tail")
        }
        return out.joinToString("\n")
    }

    /**
     * Insert a blank `>` continuation line before a quoted list item that
     * follows a quoted paragraph line (no blank separator). intellij-markdown
     * (mikepenz' parser) treats `> 1. item` immediately after `> intro` as
     * lazy paragraph continuation instead of starting a list, so the list
     * items disappear in the rendered output.
     *
     * Input:
     *   ```
     *   > Read `foo.kt` and answer:
     *   > 1. one
     *   > 2. two
     *   ```
     * Output:
     *   ```
     *   > Read `foo.kt` and answer:
     *   >
     *   > 1. one
     *   > 2. two
     *   ```
     *
     * No-op on already-separated input or non-blockquote text.
     */
    // Public (not internal): see the note on renderPipeTables — cross-module
    // test visibility after the :shared move.
    fun normalizeBlockquoteLists(raw: String): String {
        val lines = raw.split("\n")
        if (lines.size < 2) return raw
        val out = ArrayList<String>(lines.size + 4)
        for (i in lines.indices) {
            val line = lines[i]
            if (i > 0 && quotedListItemRegex.containsMatchIn(line)) {
                val prev = lines[i - 1]
                val prevIsQuotedListItem = quotedListItemRegex.containsMatchIn(prev)
                val prevIsBlankQuote = prev.trimEnd() == ">" || prev.isBlank()
                val prevIsQuoted = quotedLineRegex.containsMatchIn(prev)
                if (prevIsQuoted && !prevIsQuotedListItem && !prevIsBlankQuote) {
                    out.add(">")
                }
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun stripInboundContextBlocks(raw: String): String {
        if (inboundContextHeaders.none { raw.contains(it) }) return raw

        val normalized = raw.replace("\r\n", "\n")
        val outputLines = mutableListOf<String>()
        var inMetaBlock = false
        var inFencedJson = false

        for (line in normalized.split("\n")) {
            if (!inMetaBlock && inboundContextHeaders.any { line.startsWith(it) }) {
                inMetaBlock = true
                inFencedJson = false
                continue
            }

            if (inMetaBlock) {
                if (!inFencedJson && line.trim() == "```json") {
                    inFencedJson = true
                    continue
                }
                if (inFencedJson) {
                    if (line.trim() == "```") {
                        inMetaBlock = false
                        inFencedJson = false
                    }
                    continue
                }
                if (line.trim().isEmpty()) {
                    continue
                }
                inMetaBlock = false
            }

            outputLines.add(line)
        }

        return outputLines.joinToString("\n")
            .replace(Regex("^\\n+"), "")
    }

    private fun stripPrefixedTimestamps(raw: String): String =
        timestampPrefixRegex.replace(raw, "")

    private fun normalize(raw: String): String {
        var out = raw.replace("\r\n", "\n")
        out = out.replace("\n\n\n", "\n\n")
        out = out.replace("\n\n\n", "\n\n")
        return out.trim()
    }
}
