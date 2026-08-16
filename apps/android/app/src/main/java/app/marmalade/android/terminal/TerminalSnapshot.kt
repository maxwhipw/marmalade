// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/service/terminal/TerminalSnapshot.kt
//
// Changed: package (ours), this header, and one structural split — chuchu's
// single file also held `ImagePlacement` and `parseImages`, which touch
// android.graphics.Bitmap and android.util.Log. Those moved to
// TerminalImages.kt so that THIS file stays JVM-pure: the grid decode is the
// part worth a digital-twin test (CLAUDE.md, "Tests where they earn their
// keep"), and a plain JUnit test cannot load a class that drags in the
// Android framework. The `images` field survives as a plain List, so nothing
// about the decoded shape changed.
//
// The wire format is written by `chuchu_build_text_snapshot` in
// native/src/bridge/chuchu_snapshot.zig — that function, not this comment, is
// the contract.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TerminalSnapshot(
    val cols: Int,
    val rows: Int,
    /** -1 when the terminal has no cursor in the viewport (scrolled away). */
    val cursorX: Int,
    /** -1 when the terminal has no cursor in the viewport (scrolled away). */
    val cursorY: Int,
    val cursorVisible: Boolean,
    val defaultBgArgb: Int,
    val defaultFgArgb: Int,
    val codepoints: IntArray,
    val fgArgb: IntArray,
    val bgArgb: IntArray,
    val flags: ByteArray,
    /**
     * Sparse map: cell index -> extra grapheme codepoints (appended after the
     * base codepoint stored in [codepoints]). Present when the corresponding
     * cell has [CELL_FLAG_HAS_GRAPHEME] set.
     */
    val graphemeExtras: Map<Int, IntArray> = emptyMap(),
    val images: List<ImagePlacement> = emptyList(),
    /**
     * Stable screen.y of the content currently at viewport row 0. Changes
     * monotonically as the viewport scrolls, so the host can subtract it
     * across snapshots to remap a content-tracking selection anchor.
     */
    val viewportScrollY: Int = 0,
    /**
     * True when the running app has enabled a drag-reporting mouse mode
     * (DECSET 1002/1003). When set, the host forwards long-press drag
     * gestures to the app so a multiplexer (tmux/zellij/...) can perform
     * its own pane-scoped selection in copy mode instead of the host
     * building a grid-wide client-side selection that crosses pane borders.
     */
    val appHandlesSelectionDrag: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalSnapshot) return false
        return cols == other.cols && rows == other.rows &&
            cursorX == other.cursorX && cursorY == other.cursorY &&
            cursorVisible == other.cursorVisible &&
            defaultBgArgb == other.defaultBgArgb &&
            defaultFgArgb == other.defaultFgArgb &&
            codepoints.contentEquals(other.codepoints) &&
            fgArgb.contentEquals(other.fgArgb) &&
            bgArgb.contentEquals(other.bgArgb) &&
            flags.contentEquals(other.flags) &&
            graphemeExtrasEquals(graphemeExtras, other.graphemeExtras) &&
            images == other.images &&
            viewportScrollY == other.viewportScrollY &&
            appHandlesSelectionDrag == other.appHandlesSelectionDrag
    }

    override fun hashCode(): Int {
        var result = cols
        result = 31 * result + rows
        result = 31 * result + cursorX
        result = 31 * result + cursorY
        result = 31 * result + cursorVisible.hashCode()
        result = 31 * result + defaultBgArgb
        result = 31 * result + defaultFgArgb
        result = 31 * result + codepoints.contentHashCode()
        result = 31 * result + fgArgb.contentHashCode()
        result = 31 * result + bgArgb.contentHashCode()
        result = 31 * result + flags.contentHashCode()
        result = 31 * result + graphemeExtras.entries.fold(0) { acc, (key, arr) ->
            acc + key + arr.contentHashCode()
        }
        result = 31 * result + images.hashCode()
        result = 31 * result + viewportScrollY
        result = 31 * result + appHandlesSelectionDrag.hashCode()
        return result
    }

    /** The text of one viewport row, base codepoints only (no grapheme extras). */
    fun rowText(row: Int): String {
        require(row in 0 until rows) { "row $row out of 0 until $rows" }
        val sb = StringBuilder(cols)
        val base = row * cols
        for (x in 0 until cols) {
            val cp = codepoints[base + x]
            sb.appendCodePoint(if (cp <= 0) ' '.code else cp)
        }
        return sb.toString()
    }

    companion object {
        const val CELL_FLAG_HAS_GRAPHEME: Int = 0x40
        const val CELL_FLAG_SPACER: Int = 0x80
        const val CELL_FLAG_BOLD: Int = 0x01
        const val CELL_FLAG_ITALIC: Int = 0x02
        const val CELL_FLAG_UNDERLINE: Int = 0x04
        const val CELL_FLAG_INVERSE: Int = 0x08
        const val CELL_FLAG_BLINK: Int = 0x10
        const val CELL_FLAG_FAINT: Int = 0x20
        const val HEADER_I32_COUNT = 14
        const val CELL_SIZE_BYTES = 11

        private fun graphemeExtrasEquals(
            a: Map<Int, IntArray>,
            b: Map<Int, IntArray>,
        ): Boolean {
            if (a.size != b.size) return false
            for ((k, v) in a) {
                val o = b[k] ?: return false
                if (!v.contentEquals(o)) return false
            }
            return true
        }

        private fun packArgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun fromByteBuffer(
            buffer: ByteBuffer,
            images: List<ImagePlacement> = emptyList(),
        ): TerminalSnapshot {
            val wrapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            wrapped.position(0)

            val cols = wrapped.int
            val rows = wrapped.int
            val cursorX = wrapped.int
            val cursorY = wrapped.int
            val cursorVisible = wrapped.int == 1
            val defaultBgR = wrapped.int
            val defaultBgG = wrapped.int
            val defaultBgB = wrapped.int
            val defaultFgR = wrapped.int
            val defaultFgG = wrapped.int
            val defaultFgB = wrapped.int
            val extrasOffset = wrapped.int
            val viewportScrollY = wrapped.int
            val appHandlesSelectionDrag = wrapped.int == 1

            val cellCount = cols * rows
            val expectedSize = (HEADER_I32_COUNT * 4) + (cellCount * CELL_SIZE_BYTES)
            require(buffer.capacity() >= expectedSize) {
                "Snapshot buffer too small: cap=${buffer.capacity()} expected=$expectedSize"
            }

            // Bulk-read all cell bytes in one operation, then parse from the
            // byte array to avoid thousands of virtual-dispatch ByteBuffer
            // calls that dominate the parse cost on Android.
            // Allocate fresh arrays each frame — the old arrays are held by the
            // previous TerminalSnapshot visible to the UI thread, so reusing
            // them would cause a data race.
            val cellDataLen = cellCount * CELL_SIZE_BYTES
            val cellBytes = ByteArray(cellDataLen)
            wrapped.get(cellBytes, 0, cellDataLen)

            val codepoints = IntArray(cellCount)
            val fgArgb = IntArray(cellCount)
            val bgArgb = IntArray(cellCount)
            val flags = ByteArray(cellCount)

            var off = 0
            for (i in 0 until cellCount) {
                // codepoint: little-endian i32 from 4 bytes
                codepoints[i] = (cellBytes[off].toInt() and 0xFF) or
                    ((cellBytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((cellBytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((cellBytes[off + 3].toInt() and 0xFF) shl 24)
                off += 4
                val fgR = cellBytes[off].toInt() and 0xFF; off++
                val fgG = cellBytes[off].toInt() and 0xFF; off++
                val fgB = cellBytes[off].toInt() and 0xFF; off++
                val bgR = cellBytes[off].toInt() and 0xFF; off++
                val bgG = cellBytes[off].toInt() and 0xFF; off++
                val bgB = cellBytes[off].toInt() and 0xFF; off++
                fgArgb[i] = packArgb(fgR, fgG, fgB)
                bgArgb[i] = packArgb(bgR, bgG, bgB)
                flags[i] = cellBytes[off]; off++
            }

            val graphemeExtras: Map<Int, IntArray> =
                if (extrasOffset > 0 && extrasOffset < wrapped.capacity()) {
                    val extras = wrapped.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    extras.position(extrasOffset)
                    if (extras.remaining() < 4) {
                        emptyMap()
                    } else {
                        val recordCount = extras.int
                        val parsed = HashMap<Int, IntArray>(recordCount.coerceAtLeast(0))
                        var valid = true
                        for (record in 0 until recordCount) {
                            if (extras.remaining() < 8) {
                                valid = false
                                break
                            }
                            val cellIndex = extras.int
                            val count = extras.int
                            if (cellIndex !in 0 until cellCount || count < 0 || extras.remaining() < count * 4) {
                                valid = false
                                break
                            }
                            val cps = IntArray(count)
                            for (j in 0 until count) cps[j] = extras.int
                            parsed[cellIndex] = cps
                        }
                        if (valid) parsed else emptyMap()
                    }
                } else {
                    emptyMap()
                }

            return TerminalSnapshot(
                cols = cols,
                rows = rows,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorVisible = cursorVisible,
                defaultBgArgb = packArgb(defaultBgR, defaultBgG, defaultBgB),
                defaultFgArgb = packArgb(defaultFgR, defaultFgG, defaultFgB),
                codepoints = codepoints,
                fgArgb = fgArgb,
                bgArgb = bgArgb,
                flags = flags,
                graphemeExtras = graphemeExtras,
                images = images,
                viewportScrollY = viewportScrollY,
                appHandlesSelectionDrag = appHandlesSelectionDrag,
            )
        }
    }
}
