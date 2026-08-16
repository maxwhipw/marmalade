package app.marmalade.android.terminal

/**
 * The cursor's *visual* shape, as the running program asked for it via
 * DECSCUSR (`CSI Ps SP q`).
 *
 * Distinct from [TerminalSnapshot.cursorVisible], which is the DECTCEM
 * show/hide mode: a hidden cursor has a shape too, it is simply not drawn.
 */
enum class TerminalCursorShape {
    /** DECSCUSR 5/6 — a thin vertical bar at the left edge of the cell. */
    BAR,

    /** DECSCUSR 1/2 — a filled cell. The terminal's power-on default. */
    BLOCK,

    /** DECSCUSR 3/4 — a thin bar along the bottom of the cell. */
    UNDERLINE,

    /** No DECSCUSR code; a ghostty extension, reported back as block. */
    BLOCK_HOLLOW,
}

/**
 * Cursor shape plus blink mode, as read from libghostty's render state.
 *
 * ## Wire format
 *
 * The native side packs both into one `jint`:
 *
 * ```
 * bits 0-2 (mask 0x7)  shape ordinal: 0 bar, 1 block, 2 underline, 3 hollow
 * bit  3   (mask 0x8)  blinking
 * ```
 *
 * The producer is `Java_app_marmalade_android_terminal_GhosttyBridge_nativeCursorStyle`
 * in `native/src/bridge/cursor_style.zig`, which assigns the shape ordinals
 * with an explicit `switch` rather than from ghostty's enum order. That file
 * and this one are one contract — change them together.
 */
data class TerminalCursorStyle(
    val shape: TerminalCursorShape = TerminalCursorShape.BLOCK,
    val blinking: Boolean = false,
) {
    companion object {
        /** Steady block: the power-on state, and the pre-2026-07 behaviour. */
        val Default = TerminalCursorStyle()

        private const val SHAPE_MASK = 0x7
        private const val BLINK_FLAG = 0x8

        /**
         * Decode a packed value from `nativeCursorStyle`.
         *
         * An unrecognised shape ordinal falls back to [TerminalCursorShape.BLOCK]
         * rather than throwing: a native/Kotlin version skew should degrade to
         * the historical cursor, not crash the terminal screen.
         */
        fun decode(packed: Int): TerminalCursorStyle {
            val shape = when (packed and SHAPE_MASK) {
                0 -> TerminalCursorShape.BAR
                1 -> TerminalCursorShape.BLOCK
                2 -> TerminalCursorShape.UNDERLINE
                3 -> TerminalCursorShape.BLOCK_HOLLOW
                else -> TerminalCursorShape.BLOCK
            }
            return TerminalCursorStyle(shape, (packed and BLINK_FLAG) != 0)
        }

        /** The inverse of [decode]. Exists so a test can prove the round trip. */
        fun encode(style: TerminalCursorStyle): Int {
            val shape = when (style.shape) {
                TerminalCursorShape.BAR -> 0
                TerminalCursorShape.BLOCK -> 1
                TerminalCursorShape.UNDERLINE -> 2
                TerminalCursorShape.BLOCK_HOLLOW -> 3
            }
            return shape or (if (style.blinking) BLINK_FLAG else 0)
        }
    }
}

/**
 * Where to paint the cursor, in canvas pixels, and how.
 *
 * @param stroked outline the rect instead of filling it (hollow block).
 * @param invertGlyph repaint the covered character in the cursor's contrast
 *   colour. Only a filled block hides the glyph, so only a filled block needs
 *   it — a bar or underline sits clear of the character.
 */
data class TerminalCursorDraw(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val stroked: Boolean,
    val invertGlyph: Boolean,
)

/**
 * Turns a [TerminalCursorShape] plus a cell rect into the rect to paint.
 *
 * Split out of the canvas so it can be unit tested: the drawing itself needs
 * an Android `Canvas`, but *which rectangle* each shape occupies is arithmetic,
 * and it is the part that gets subtly wrong (a bar on the wrong edge, an
 * underline outside its cell).
 */
object TerminalCursorGeometry {

    /**
     * @param thickness requested bar/underline thickness in pixels. Clamped to
     *   at least 1px and to at most the cell's extent along that axis, so a
     *   large value degrades to a block instead of bleeding into neighbours.
     */
    fun forShape(
        shape: TerminalCursorShape,
        cellLeft: Float,
        cellTop: Float,
        cellWidth: Float,
        cellHeight: Float,
        thickness: Float,
    ): TerminalCursorDraw {
        val right = cellLeft + cellWidth
        val bottom = cellTop + cellHeight
        return when (shape) {
            TerminalCursorShape.BLOCK -> TerminalCursorDraw(
                cellLeft, cellTop, right, bottom, stroked = false, invertGlyph = true,
            )

            TerminalCursorShape.BLOCK_HOLLOW -> TerminalCursorDraw(
                cellLeft, cellTop, right, bottom, stroked = true, invertGlyph = false,
            )

            TerminalCursorShape.BAR -> TerminalCursorDraw(
                cellLeft,
                cellTop,
                cellLeft + thickness.coerceIn(1f, cellWidth),
                bottom,
                stroked = false,
                invertGlyph = false,
            )

            TerminalCursorShape.UNDERLINE -> TerminalCursorDraw(
                cellLeft,
                bottom - thickness.coerceIn(1f, cellHeight),
                right,
                bottom,
                stroked = false,
                invertGlyph = false,
            )
        }
    }
}
