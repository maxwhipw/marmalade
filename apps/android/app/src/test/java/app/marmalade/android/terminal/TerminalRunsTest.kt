package app.marmalade.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer's two decisions worth testing without a Canvas: which face draws
 * a glyph, and where one draw call has to stop. Both are pure by construction
 * (see `TerminalRuns.kt`), so a wrong answer shows up here rather than as a
 * subtly wrong pixel on device.
 */
class TerminalRunsTest {

    private val fg = 0xFFE7E5E4.toInt()
    private val bg = 0xFF1C1917.toInt()
    private val altFg = 0xFFF97316.toInt()

    /** One row, [text] as its base codepoints. */
    private fun row(
        text: String,
        flags: ByteArray = ByteArray(text.length),
        fgArgb: IntArray = IntArray(text.length) { fg },
        bgArgb: IntArray = IntArray(text.length) { bg },
        graphemeExtras: Map<Int, IntArray> = emptyMap(),
    ) = TerminalSnapshot(
        cols = text.length,
        rows = 1,
        cursorX = 0,
        cursorY = 0,
        cursorVisible = false,
        defaultBgArgb = bg,
        defaultFgArgb = fg,
        codepoints = IntArray(text.length) { text[it].code },
        fgArgb = fgArgb,
        bgArgb = bgArgb,
        flags = flags,
        graphemeExtras = graphemeExtras,
    )

    private fun runsOf(snapshot: TerminalSnapshot): List<TerminalRuns.TextRun> {
        val out = mutableListOf<TerminalRuns.TextRun>()
        var i = 0
        while (i < snapshot.cols) {
            val run = TerminalRuns.nextTextRun(snapshot, i, snapshot.cols) {
                TerminalPaintChoice.PRIMARY
            }
            out += run
            i = run.end
        }
        return out
    }

    @Test
    fun `a plain span is one run`() {
        val runs = runsOf(row("abc")).filter { it.text.isNotEmpty() }
        assertEquals(listOf("abc"), runs.map { it.text })
    }

    @Test
    fun `a plain space ends the run and emits nothing`() {
        val runs = runsOf(row("ab cd"))
        assertEquals(listOf("ab", "", "cd"), runs.map { it.text })
    }

    @Test
    fun `a spacer continues the run and emits no glyph`() {
        // A wide character occupies two cells: the base glyph and a SPACER cell
        // stored as a space. Breaking the run there would split every CJK line.
        val flags = ByteArray(3).also { it[1] = TerminalSnapshot.CELL_FLAG_SPACER.toByte() }
        val runs = runsOf(row("a b", flags = flags)).filter { it.text.isNotEmpty() }
        assertEquals(listOf("ab"), runs.map { it.text })
        assertEquals(3, runs.single().end)
    }

    @Test
    fun `a foreground change breaks the run`() {
        val fgs = intArrayOf(fg, fg, altFg, altFg)
        val runs = runsOf(row("abcd", fgArgb = fgs)).filter { it.text.isNotEmpty() }
        assertEquals(listOf("ab", "cd"), runs.map { it.text })
        assertEquals(listOf(fg, altFg), runs.map { it.fgArgb })
    }

    @Test
    fun `a style change breaks the run`() {
        val flags = ByteArray(4)
        flags[2] = TerminalSnapshot.CELL_FLAG_BOLD.toByte()
        flags[3] = TerminalSnapshot.CELL_FLAG_BOLD.toByte()
        val runs = runsOf(row("abcd", flags = flags)).filter { it.text.isNotEmpty() }
        assertEquals(listOf("ab", "cd"), runs.map { it.text })
        assertEquals(TerminalSnapshot.CELL_FLAG_BOLD, runs[1].styleBits)
    }

    @Test
    fun `a paint face change breaks the run`() {
        val snapshot = row("abcd")
        val out = mutableListOf<String>()
        var i = 0
        while (i < snapshot.cols) {
            val run = TerminalRuns.nextTextRun(snapshot, i, snapshot.cols) { index ->
                if (index < 2) TerminalPaintChoice.PRIMARY else TerminalPaintChoice.EMOJI
            }
            if (run.text.isNotEmpty()) out += run.text
            i = run.end
        }
        assertEquals(listOf("ab", "cd"), out)
    }

    @Test
    fun `inverse swaps foreground and background`() {
        val flags = ByteArray(1) { TerminalSnapshot.CELL_FLAG_INVERSE.toByte() }
        val snapshot = row("a", flags = flags)
        assertEquals(bg, TerminalRuns.fgOf(snapshot, 0))
        assertEquals(fg, TerminalRuns.bgOf(snapshot, 0))
    }

    @Test
    fun `a grapheme cluster is drawn as one glyph`() {
        // 'e' + COMBINING ACUTE — one cell, two codepoints.
        val snapshot = row("e", graphemeExtras = mapOf(0 to intArrayOf(0x0301)))
        assertEquals("é", TerminalRuns.glyphAt(snapshot, 0))
        assertEquals("é", runsOf(snapshot).single().text)
    }

    @Test
    fun `default background needs no rectangle, a set one does`() {
        val bgs = intArrayOf(bg, bg, altFg, altFg)
        val snapshot = row("abcd", bgArgb = bgs)
        val first = TerminalRuns.nextBgRun(snapshot, 0, 4)
        assertEquals(2, first.end)
        assertTrue(!first.fill)
        val second = TerminalRuns.nextBgRun(snapshot, 2, 4)
        assertEquals(4, second.end)
        assertTrue(second.fill)
        assertEquals(altFg, second.argb)
    }

    @Test
    fun `an inverse cell is always filled even at the default background`() {
        val flags = ByteArray(1) { TerminalSnapshot.CELL_FLAG_INVERSE.toByte() }
        val snapshot = row("a", flags = flags)
        val run = TerminalRuns.nextBgRun(snapshot, 0, 1)
        assertTrue(run.fill)
        assertEquals(fg, run.argb)
    }
}

/** [TerminalPaintChoice] routing: the font fallback chain, without any fonts. */
class TerminalPaintChoiceTest {

    private fun coverage(
        primary: Set<String> = emptySet(),
        symbols: Set<String> = emptySet(),
        emoji: Set<String> = emptySet(),
        fallback: Set<String> = emptySet(),
    ) = object : GlyphCoverage {
        override fun primaryHasGlyph(glyph: String) = glyph in primary
        override fun symbolsHasGlyph(glyph: String) = glyph in symbols
        override fun emojiHasGlyph(glyph: String) = glyph in emoji
        override fun fallbackHasGlyph(glyph: String) = glyph in fallback
    }

    @Test
    fun `the primary face wins whenever it has the glyph`() {
        val c = coverage(primary = setOf("a"), emoji = setOf("a"))
        assertEquals(TerminalPaintChoice.PRIMARY, TerminalPaintChoice.choose("a", c))
    }

    @Test
    fun `printable ascii skips the lookup entirely`() {
        // The fast path matters: hasGlyph is a font-table hit per cell per frame.
        val c = coverage()
        assertEquals(TerminalPaintChoice.PRIMARY, TerminalPaintChoice.chooseCodepoint('x'.code, "x", c))
    }

    @Test
    fun `a nerd font private-use glyph goes to the symbols face when it exists`() {
        val glyph = "" // powerline separator
        val c = coverage(symbols = setOf(glyph), emoji = setOf(glyph))
        assertEquals(TerminalPaintChoice.SYMBOLS, TerminalPaintChoice.choose(glyph, c))
    }

    @Test
    fun `without a symbols face a private-use glyph falls through the chain`() {
        // This is today's shipping state — no symbols font is bundled yet.
        val glyph = ""
        assertEquals(TerminalPaintChoice.EMOJI, TerminalPaintChoice.choose(glyph, coverage()))
    }

    @Test
    fun `emoji beat the symbol fallback so colour and cluster shaping survive`() {
        val glyph = "🧑‍💻" // technologist ZWJ sequence
        val c = coverage(emoji = setOf(glyph), fallback = setOf(glyph))
        assertEquals(TerminalPaintChoice.EMOJI, TerminalPaintChoice.choose(glyph, c))
    }

    @Test
    fun `the symbol fallback catches what nothing else covers`() {
        val glyph = "■"
        assertEquals(TerminalPaintChoice.FALLBACK, TerminalPaintChoice.choose(glyph, coverage(fallback = setOf(glyph))))
    }

    @Test
    fun `an uncovered glyph ends on the system face for its tofu box`() {
        assertEquals(TerminalPaintChoice.EMOJI, TerminalPaintChoice.choose("☃", coverage()))
    }

    @Test
    fun `private use ranges are the nerd font ones`() {
        assertTrue(TerminalPaintChoice.isNerdFontPrivateUse(0xE000))
        assertTrue(TerminalPaintChoice.isNerdFontPrivateUse(0xF8FF))
        assertTrue(TerminalPaintChoice.isNerdFontPrivateUse(0xF0000))
        assertTrue(!TerminalPaintChoice.isNerdFontPrivateUse('a'.code))
        assertTrue(!TerminalPaintChoice.isNerdFontPrivateUse(0xFFFF))
    }
}

/**
 * Selection is drawn by the background pass rather than by a pass of its own,
 * so the wash and the run segmentation have to agree. A composite that got the
 * arithmetic wrong would show as a selection that hides the text under it.
 */
class TerminalRunsSelectionTest {

    private val fg = 0xFFE7E5E4.toInt()
    private val bg = 0xFF1C1917.toInt()

    private fun row(text: String, bgArgb: IntArray = IntArray(text.length) { bg }) =
        TerminalSnapshot(
            cols = text.length,
            rows = 1,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = bg,
            defaultFgArgb = fg,
            codepoints = IntArray(text.length) { text[it].code },
            fgArgb = IntArray(text.length) { fg },
            bgArgb = bgArgb,
            flags = ByteArray(text.length),
        )

    @Test
    fun `an opaque source replaces the destination and a transparent one does not`() {
        assertEquals(0xFF112233.toInt(), TerminalRuns.blend(0xFF112233.toInt(), 0xFFAABBCC.toInt()))
        assertEquals(0xFFAABBCC.toInt(), TerminalRuns.blend(0x00112233, 0xFFAABBCC.toInt()))
    }

    @Test
    fun `a half-alpha source lands halfway, and the result stays opaque`() {
        val blended = TerminalRuns.blend(0x80000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0xFF, (blended ushr 24) and 0xFF)
        // 255 * 128/255 = 128 remaining from white; rounding down gives 127.
        assertEquals(127, blended and 0xFF)
    }

    @Test
    fun `selected cells wash over their own background rather than replacing it`() {
        val coloured = row("abcd", bgArgb = intArrayOf(bg, bg, 0xFF003300.toInt(), bg))
        val selection = TerminalRuns.SelectionPaint(1..2, 0x40F97316)

        val first = TerminalRuns.nextBgRun(coloured, 0, 4, selection)
        // Cell 0 is unselected default background: no rectangle needed.
        assertEquals(1, first.end)
        assertTrue(!first.fill)

        val second = TerminalRuns.nextBgRun(coloured, 1, 4, selection)
        // Cells 1 and 2 are both selected but sit on different backgrounds, so
        // the composite differs and the run has to break between them.
        assertEquals(2, second.end)
        assertTrue(second.fill)
        assertEquals(TerminalRuns.blend(0x40F97316, bg), second.argb)

        val third = TerminalRuns.nextBgRun(coloured, 2, 4, selection)
        assertEquals(3, third.end)
        assertEquals(TerminalRuns.blend(0x40F97316, 0xFF003300.toInt()), third.argb)
    }

    @Test
    fun `a run of identically selected cells stays one draw call`() {
        val plain = row("abcd")
        val run = TerminalRuns.nextBgRun(plain, 0, 4, TerminalRuns.SelectionPaint(0..3, 0x40F97316))
        assertEquals(4, run.end)
        assertTrue(run.fill)
    }

    @Test
    fun `no selection leaves the background pass exactly as it was`() {
        val plain = row("abcd")
        assertEquals(TerminalRuns.nextBgRun(plain, 0, 4), TerminalRuns.nextBgRun(plain, 0, 4, null))
    }
}
