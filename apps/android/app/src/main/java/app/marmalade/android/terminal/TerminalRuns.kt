// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/ui/terminal/TerminalCanvas.kt
//
// Changed: the run-segmentation and paint-choice decisions are lifted OUT of the
// draw loop into pure functions. Upstream they are private helpers tangled with
// android.graphics.Paint and with selection state; here the glyph-coverage
// question is asked through [GlyphCoverage] so both decisions are JVM-unit-
// testable, which is the project's verification model (CLAUDE.md). Selection is
// modelled differently too: upstream replaces a selected cell's background
// outright, here the selection colour is *composited* onto it, so highlighted
// output stays legible under the wash.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

/**
 * Whether a font can draw a glyph. On Android every method is
 * `android.graphics.Paint.hasGlyph`; in tests it is a stub.
 */
interface GlyphCoverage {
    fun primaryHasGlyph(glyph: String): Boolean

    /**
     * The Nerd-Font symbols face. No symbols font is bundled yet (the licensing
     * pass comes later), so this answers false today and every PUA glyph tofus —
     * accepted, and the routing stays in place so adding the font is a drop-in.
     */
    fun symbolsHasGlyph(glyph: String): Boolean

    /** The system face — the only one that shapes emoji ZWJ/flag/VS16 clusters. */
    fun emojiHasGlyph(glyph: String): Boolean

    fun fallbackHasGlyph(glyph: String): Boolean
}

/**
 * Which of the four paints renders a glyph.
 *
 * Order of preference: primary (keeps monospace metrics) → Nerd-Font symbols
 * for private-use codepoints → the system face (color emoji + cluster shaping)
 * → the symbol fallback → the system face again, this time for its
 * missing-glyph marker.
 */
object TerminalPaintChoice {
    const val PRIMARY: Int = 0
    const val SYMBOLS: Int = 1
    const val FALLBACK: Int = 2
    const val EMOJI: Int = 3

    /**
     * Private Use Area ranges where Nerd Font glyphs live (devicons, powerline,
     * weather, file-type icons). Anything outside them that the primary font
     * cannot draw is treated as an emoji/symbol cluster.
     */
    fun isNerdFontPrivateUse(cp: Int): Boolean =
        (cp in 0xE000..0xF8FF) || (cp in 0xF0000..0xFFFFD) || (cp in 0x100000..0x10FFFD)

    fun choose(glyph: String, coverage: GlyphCoverage): Int {
        if (coverage.primaryHasGlyph(glyph)) return PRIMARY
        if (isNerdFontPrivateUse(glyph.codePointAt(0)) && coverage.symbolsHasGlyph(glyph)) {
            return SYMBOLS
        }
        if (coverage.emojiHasGlyph(glyph)) return EMOJI
        if (coverage.fallbackHasGlyph(glyph)) return FALLBACK
        return EMOJI
    }

    /** [choose] with a fast path for the printable ASCII that dominates output. */
    fun chooseCodepoint(codepoint: Int, glyph: String, coverage: GlyphCoverage): Int =
        if (codepoint in 0x21..0x7E) PRIMARY else choose(glyph, coverage)
}

/**
 * Splitting one grid row into the runs the canvas draws.
 *
 * A run is the longest span the renderer can hand to a single `drawText` /
 * `drawRect` call, so the segmentation rules ARE the rendering contract:
 *  - a plain space ends a text run; a **spacer** (the second half of a wide
 *    character, stored as a space with [TerminalSnapshot.CELL_FLAG_SPACER])
 *    does not — it contributes no glyph and the run continues through it;
 *  - a change of foreground color, of style bits, or of paint face ends a run;
 *  - `INVERSE` swaps fg and bg for that cell, which is why the colors are read
 *    through [fgOf] / [bgOf] rather than off the arrays directly.
 */
object TerminalRuns {

    val TEXT_STYLE_MASK: Int =
        TerminalSnapshot.CELL_FLAG_BOLD or
            TerminalSnapshot.CELL_FLAG_ITALIC or
            TerminalSnapshot.CELL_FLAG_UNDERLINE or
            TerminalSnapshot.CELL_FLAG_FAINT

    fun isInverse(snapshot: TerminalSnapshot, index: Int): Boolean =
        (snapshot.flags[index].toInt() and TerminalSnapshot.CELL_FLAG_INVERSE) != 0

    fun isSpacerContinuation(snapshot: TerminalSnapshot, index: Int): Boolean =
        ((snapshot.flags[index].toInt() and 0xFF) and TerminalSnapshot.CELL_FLAG_SPACER) != 0

    fun fgOf(snapshot: TerminalSnapshot, index: Int): Int =
        if (isInverse(snapshot, index)) snapshot.bgArgb[index] else snapshot.fgArgb[index]

    fun bgOf(snapshot: TerminalSnapshot, index: Int): Int =
        if (isInverse(snapshot, index)) snapshot.fgArgb[index] else snapshot.bgArgb[index]

    /**
     * The selected cell range and the translucent wash drawn over it.
     *
     * [argb] is expected to be translucent (`#f9731640` — the accent at 25%)
     * and is **composited onto each cell's own background** rather
     * than replacing it, so a selection over coloured `ls` output or a
     * highlighted diff still shows what is underneath. Compositing here rather
     * than relying on the canvas' alpha blending also keeps every run colour
     * opaque, which is what lets run segmentation stay a plain equality test.
     */
    data class SelectionPaint(val range: IntRange, val argb: Int) {
        fun covers(index: Int): Boolean = index in range
    }

    /** Source-over composite of a translucent [src] onto an opaque [dst]. */
    fun blend(src: Int, dst: Int): Int {
        val alpha = (src ushr 24) and 0xFF
        if (alpha == 0xFF) return src
        if (alpha == 0) return dst
        val inverse = 255 - alpha
        fun channel(shift: Int): Int =
            ((((src ushr shift) and 0xFF) * alpha + ((dst ushr shift) and 0xFF) * inverse) / 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun bgWithSelection(
        snapshot: TerminalSnapshot,
        index: Int,
        selection: SelectionPaint?,
    ): Int {
        val bg = bgOf(snapshot, index)
        return if (selection != null && selection.covers(index)) {
            blend(selection.argb, bg)
        } else {
            bg
        }
    }

    /** The cell's full grapheme cluster: base codepoint plus any extras. */
    fun glyphAt(snapshot: TerminalSnapshot, index: Int): String {
        val codepoint = snapshot.codepoints[index]
        val extras = snapshot.graphemeExtras[index]
        if (extras == null || extras.isEmpty()) return String(Character.toChars(codepoint))
        val builder = StringBuilder(1 + extras.size)
        builder.appendCodePoint(codepoint)
        for (cp in extras) builder.appendCodePoint(cp)
        return builder.toString()
    }

    /**
     * @param end exclusive index where the run stops (always > start).
     * @param fill false when the run is the default background and needs no
     *   rectangle at all — the canvas already cleared to that color.
     */
    data class BgRun(val end: Int, val argb: Int, val fill: Boolean)

    fun nextBgRun(
        snapshot: TerminalSnapshot,
        start: Int,
        rowEnd: Int,
        selection: SelectionPaint? = null,
    ): BgRun {
        val inverse = isInverse(snapshot, start)
        val bg = bgWithSelection(snapshot, start, selection)
        var j = start + 1
        while (j < rowEnd) {
            if (bgWithSelection(snapshot, j, selection) != bg) break
            if (isInverse(snapshot, j) != inverse) break
            j++
        }
        return BgRun(end = j, argb = bg, fill = inverse || bg != snapshot.defaultBgArgb)
    }

    /**
     * @param text empty when [start] is a blank cell — nothing to draw, and the
     *   caller advances to [end] as usual.
     */
    data class TextRun(
        val end: Int,
        val text: String,
        val paintChoice: Int,
        val fgArgb: Int,
        val styleBits: Int,
    )

    private val BLANK = TextRun(end = 0, text = "", paintChoice = TerminalPaintChoice.PRIMARY, fgArgb = 0, styleBits = 0)

    /**
     * @param paintChoiceOf the (memoized) face decision for a cell — injected so
     *   this stays free of `android.graphics`.
     */
    fun nextTextRun(
        snapshot: TerminalSnapshot,
        start: Int,
        rowEnd: Int,
        paintChoiceOf: (Int) -> Int,
    ): TextRun {
        val cp = snapshot.codepoints[start]
        if (cp == 0 || cp == 32) return BLANK.copy(end = start + 1)

        val fg = fgOf(snapshot, start)
        val styleBits = snapshot.flags[start].toInt() and TEXT_STYLE_MASK
        val paintChoice = paintChoiceOf(start)
        val sb = StringBuilder(16)
        sb.append(glyphAt(snapshot, start))

        var j = start + 1
        while (j < rowEnd) {
            val c = snapshot.codepoints[j]
            if (c == 0) break
            if (c == 32 && !isSpacerContinuation(snapshot, j)) break
            if (fgOf(snapshot, j) != fg) break
            if ((snapshot.flags[j].toInt() and TEXT_STYLE_MASK) != styleBits) break
            if (c == 32) {
                // A wide character's trailing spacer: no glyph, run continues.
                j++
                continue
            }
            if (paintChoiceOf(j) != paintChoice) break
            sb.append(glyphAt(snapshot, j))
            j++
        }
        return TextRun(
            end = j,
            text = sb.toString(),
            paintChoice = paintChoice,
            fgArgb = fg,
            styleBits = styleBits,
        )
    }
}
